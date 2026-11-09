# CarPlay Ultra：实现与交接说明

**读者**：接手 CarPlay Ultra 部分的开发者。
**范围**：只讲 Ultra。普通 CarPlay 的连接、投屏、音频、方控不在本文范围内，见 [`README.md`](README.md)。

**一句话现状**：Ultra 准入、主题资产、面板下发、仪表出图全部打通；中控在主题模式下只填顶部 66%，
四角出现圆角，这两件是当前唯一的阻塞项。

> 更新于 2026-11-09。所有结论来自一台车（领克 05 2020 / 亿咖通 DCY11 / 骁龙 820A / Android 9）的实车调试。

---

## 一、Ultra 比普通 CarPlay 多了什么

普通 CarPlay 是「一块屏 + 一套 UI」。Ultra 在原理上多了三样，缺任何一样都只有黑屏：

| 东西 | 作用 | 在哪实现 |
|---|---|---|
| **车辆状态配件**（vehicle state accessories） | 告诉 iPhone 这台车有哪些能力：空调、座椅、油量、轮胎、行程、音频、发动机、仪表内容、通知 | `shared/.../ultra/UltraCatalog.kt` |
| **主题资产**（CarDDP asset） | 一个 10 位十六进制标识 + 版本号 + SDK 版本；iOS 靠它决定给仪表画哪套主题 | `transport/Iap2IdentificationClient.kt`、`Iap2Endpoint.kt` |
| **面板与布局**（displayPanels + uiSync） | 规定哪块屏放什么面板、仪表用哪个布局、地图占哪个 zone | `airplay/AirPlayInfoPlist.kt`、`ultra/UltraUiSync.kt` |

**关键认识**：Ultra 的仪表不是「投屏」，也不是「URL」。仪表那块 `Cluster_Display` 面板是由
**iOS 端的 display plugin 自己渲染**的，车机只负责告诉它「用哪个布局、什么配色调、地图放哪」。
所以车机拿到的是一条**视频流**，但这条流的内容由手机按主题合成——这直接决定了后面 §6.1 的结论。

---

## 二、完整链路（从 iPhone 连上到仪表出图）

### 2.1 iAP2 阶段：先报身份与主题

```
iPhone ──0x4300 Identification──▶ 车机
车机 ──Identification response──▶ iPhone     ← 参数 6 = theme_asset_identifier
车机 ──0x4301 CarPlayStartSession──▶ iPhone   ← 参数 6 = SDK 版本，参数 7 = {asset, version}
```

- 主题标识放在 **Identification 的 transport 组件参数 6**（`theme_asset_identifier`）。
  iOS 只认这个字段来解析仪表主题；为 null 时，主题化的仪表**永远是黑的**。
- `0x4301` 会**再发一次**资产：参数 6 = SDK 版本（`"19.0"`），参数 7 = `{assetIdentifier, assetVersion}`（`985`）。
  资产与 SDK 版本必须成对，只发一个会被拒。
- 这一层是「有或全无」：标识对不上，后面的 `/info` 再正确也不会出主题。

### 2.2 AirPlay `/info`：声明面板与通道

只有当 **Ultra 开 且 主题开** 时才下发这三样：

```kotlin
info["vehicleStateProtocolInfo"] = UltraCatalog.infoEntry(VEHICLE_STYLE)  // 车辆身份
info["displayPanels"] = displayPanels(displays)                          // 面板
info["uiSyncInfo"] = emptyMap()                                          // 布局通道
```

`displayPanels` 的当前形状（主题版）——**两块，按主题命名**：

| 面板 uid | 对应 display | 属性 | 内容 |
|---|---|---|---|
| `Center_Display` | 中控（`type=110`） | 无 | `initialVideoStreams` 指向本屏流 |
| `Cluster_Display` | 仪表（`type=111`） | `showsInstruments` | `initialVideoStreams` + `displayPlugins` |

两点必须遵守：

1. **`initialVideoStreams` 不能为空**。空流的 panel 会被 iPhone 当成「等插件来填」，
   结果就是一块永远不会被填的黑区。
2. **`displayPlugins` 只加在仪表那块上**，`uid` 与面板 uid 相同。参考实现里仪表区是手机自己填的，
   中控不是。

### 2.3 SETUP：三条数据流

| 流 | UUID 用途 |
|---|---|
| `FF4A6720-F2BE-4F56-A3E1-DB3B4E37D634` | 车辆状态高优先级（值更新） |
| `3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E` | 车辆状态常规 |
| `07D9F906-8D64-4B54-A808-A20BCA2C51C2` | **uiSync**（布局） |

`uiSync` 是否被打开，取决于 `/info` 里有没有回声 `uiSyncInfo`。没有 → 手机不开这条流 →
仪表面板有名字、有 zone、有流，但**没有内容可画**（表现为 2–8 kbit/s 的空帧，很容易误判成「已出图」）。

### 2.4 视频流

| 流 | 归属 | 编码尺寸 | 谁渲染 |
|---|---|---|---|
| 110 | 中控 | `1920×1088`（含 8 行对齐填充） | 普通 CarPlay UI 或 Ultra 皮肤 |
| 111 | 仪表 | `1920×720`（8:3） | iOS 的 display plugin，按主题布局合成 |

流归属由 **display uuid** 决定，不是 `type` 字段。111 在车机固件侧的 `activeStreams` 里平时不存在，
是 App 打开 `loadClusterMapEnabled` 之后才建立映射的。

### 2.5 uiSync：告诉 iPhone 画哪个布局

消息是 OPACK 信封 `{displayUUID, data: bplist}`，内层 header 镜像车辆状态通道的事务字段：

| 字段 | 含义 |
|---|---|
| `type` | 0 reset / 1 reset complete / 2 command / 3 command ack |
| `ssn` | 会话号，旧会话的消息直接丢 |
| `psn` | 本端已发序号 |
| `asn` | 已收序号，原样回显 |
| `id` | 手机的不透明标识，版本协商后回显 |

流程：**收到 reset → 应答 → 收到 reset complete → 请求布局（requestLayout）→ 收到 command → ack**。
布局用 Apple 自己 CarDDP 资产里的 id：

| 布局 | id | 说明 |
|---|---|---|
| `DigiRing` | `8BC9ABB1-CB20-595E-92BF-D8F099BF3515` | 当前默认，配 `green` 调色 |
| `Modular` | 同资产内 | 三表盘 + 地图区的朴素布局，参考实现测试用 |

**每条应答必须原样带回 `displayUUID`**。回错 display，整个会话的语气就变了（手机按那块屏的规则等响应）。

### 2.6 车机侧上屏

仪表那块 display 1 用自建 `Presentation` 装流：

```
EcarxClusterPresentation.show()      ← 先建窗口并上屏
setPresentationToDimSwitch(true)     ← 再把这条通路接到仪表
```

`setPresentationToDimSwitch` 不是镜像开关，是**通路开关**：`false` 摘掉 → 车机立刻盖回自己的仪表页；
`true` 才真正显示。收尾必须**先 `false` 再 `dismiss()`**。顺序颠倒会闪一下或留残影。

该状态存在车机侧、**跨进程存活**：进程被杀后仍是「已接通」，所以启动时要主动释放一次。

---

## 三、代码地图

| 文件 | 行数 | 职责 |
|---|---|---|
| `shared/.../ultra/UltraCatalog.kt` | 732 | 车辆状态配件目录：10 个配件、126 个特征，ID 段见 `ACCESSORY_*_IID` |
| `ultra/UltraSession.kt` | 255 | 三条数据流的封装与加密、协议常量 |
| `ultra/UltraUiSync.kt` | 254 | 布局通道，reset/command 状态机 |
| `ultra/UltraVehicleState.kt` | 325 | 车辆状态值的编码与上报 |
| `ultra/VehicleStateChannel.kt` | 402 | 通道收发与事务 |
| `ultra/UltraTelemetry.kt` | 527 | 调试遥测（探针、帧统计、命令记录） |
| `ultra/UltraModel.kt` | 232 | 数据模型 |
| `ultra/Opack.kt` | 284 | OPACK 编解码 |
| `ultra/EcarxUltraCarLink.kt` | 85 | 与车机侧（ECARX）的桥 |
| `airplay/AirPlayInfoPlist.kt` | — | `/info`：`vehicleStateProtocolInfo` / `displayPanels` / `uiSyncInfo` |
| `transport/Iap2IdentificationClient.kt` | — | 主题资产（Identification 参数 6、0x4301 参数 6/7） |
| `iap2/catalog/Iap2Endpoint.kt` | — | iAP2 参数 schema（**加参数必须同步改这里**） |

---

## 四、三道门：缺一即黑

这是本项目最贵的教训，请按顺序检查。

### 门 1：主题资产（iAP2）

`themeAssetIdentifier` 必须非空，且与车厂主题包一致。三件一起发：标识、资产版本、SDK 版本。
只发标识不发版本，或版本对不上 → 主题不生效。

### 门 2：参数 schema 同步

iAP2 的参数是**强 schema**，不是「多了就忽略」。往 theme 组里加一个字段，必须同时在
`Iap2Endpoint.kt` 的对应条目里登记 children，否则整条请求被拒（报 `Unknown body parameter`），
连原本能用的字段一起失效。

⇒ **任何 Ultra 参数实验都必须同时改两处**，否则一轮上车全废。

### 门 3：两块 display 都要换主题名

只改仪表那块的名字，中控仍用普通名字 → 手机会按两套不同的规则处理，表现为仪表出图、中控异常
（或反过来）。主题模式下 **`Center_Display` 与 `Cluster_Display` 必须成对出现**。

### 附加约束：开关必须成对

`ultra=on / theme=off` 这种半开状态，如果仍然下发 `displayPanels`，iPhone 会拿到「要被插件填满却
没有视频流」的屏 → **整个中控黑屏**，而且报错为零。

解法不是「发一份无主题的 panels」，而是——**主题没开就完全不发 panels**。此时会话与普通 CarPlay
无差别，车辆状态配件单独存在是无害的。

---

## 五、现状

| 项 | 状态 | 判据 |
|---|---|---|
| Ultra 准入（iPhone 认这台车是 Ultra 车） | ✅ | SETUP 里出现 `vehicleStateProtocol` 特征 |
| 主题资产生效 | ✅ | 仪表出图，且不是空帧 |
| 面板下发 | ✅ | `panels=2` |
| 仪表主题出图 | ✅ | 111 流 30 fps，`coded=1920x720` |
| uiSync 布局 | ✅ | 收到 reset/command，并回 ack |
| **中控主题画面** | ⚠️ | 只填顶部 **66%**（`box=0,0..100,66`） |
| **四角圆角** | ⚠️ | 主题会话出现圆角，且未下发 `cornerMasks` |
| 仪表上的车速/转速/胎压 | ❌ | VHAL 里没有这些信号 |
| 仪表只显示地图 | ⚠️ | `activeComponents` 按 zone 覆盖 |
| 普通模式（Ultra 关）| ✅ | 全屏 `drawn=67~70%`、`box=0,0..100,100` |

**普通模式是基线**：任何 Ultra 改动都必须保证关掉这两个开关后，普通 CarPlay 仍然全屏可用。

---

## 六、未解问题

### 6.1 主题模式中控只填顶部 66%

**现象**：主题模式下中控正常渲染，但内容只占顶部 720 行（1080 的 66%），下面 360 行全黑。
像素探针稳定输出 `box=0,0..100,66`、`drawn≈11%`。

**已排除**（重走没有意义）：

| 假设 | 反证 |
|---|---|
| 窗口尺寸不对 | 换成 1920×880 的窗口，比例仍是 66% |
| safeArea / 遮罩 | 主题模式下这两项已被显式忽略、全幅下发 |
| 视频流不对 | 110 流 `coded=1920x1088`（全高），111 流 `coded=1920x720` |
| 码率低 = 黑屏 | 静止时 1 kbps 是保活帧，不是黑屏证据 |

**当前判断**：Ultra 主题场景的**规范画布就是 8:3**。四条独立来源一致——本机实测 720/1080、
111 流 1920×720、同平台另一分支固定 1920×720、车厂参考实现 2.69 与 1.67。真实 Ultra 车是贯穿式
宽屏，主题场景按宽幅设计，iPhone 把一块 8:3 内容**顶部对齐**放进 16:9 画布，剩下自然留黑。

⇒ 这是**内容几何**，不是可用区。改 safeArea、viewArea、窗口参数都无效。

**下一步（按成本排序，未实施）**：

1. **零代码 A/B**：主题模式下把中控声明的尺寸改成 `1920×720`，看是铺满还是等比缩放到宽度。
   这一步能把「手机按画布合成」与「手机按车机声明的尺寸合成」区分开。
2. **接受 66%，下方自绘**：把 360 行做成信息条（媒体/车控/能耗），即「宽幅画布 + 车机补充」。
3. **放弃 Apple 主题仪表、改自绘宽幅**：本车缺仪表端 native 注入，只能喂中控，成本最高。

### 6.2 主题模式四角变圆角

**现象**：主题会话开始后中控四角出现圆角。此时会话是**全幅 safeArea 且没有下发 `cornerMasks`**。

**当前判断**：`cornerMasks` 字段**缺席**时，iPhone 按主题资产里的默认值自己画圆角
（参考实现里默认 13 像素角）。同平台另一个开源实现是每个 display 都**显式下发 `false`**。

**下一步**：显式下发 `cornerMasks = false`。

⚠️ **必须单独一版验收**。不要与 6.1 的画布实验同版——圆角会干扰对画面边界的目视判断，
而 6.1 的判据正是「边界在哪」。

### 6.3 仪表要不到车辆数据

**现象**：主题仪表可以放车速、转速、胎压，但这台车取不到值。

**已定位**：全车数据走 VHAL（`CarPropertyManager`），APK 侧不做 CAN 解析。可枚举的 sensor 属性
共 43 项，**只有续航里程与总里程有值**，转速 / 挡位 / 胎压恒空。

**含义**：不是权限问题，是车机没有把这些信号放进 VHAL。除非固件侧另有通道（DIM 或其他私有服务），
否则这条路封死。**不建议填假值**——仪表上的车速必须是真值。

### 6.4 仪表只显示地图，没有其它组件

**现象**：主题仪表出图，但只有地图，没有表盘/媒体/行程。

**已定位**：`activeComponents` 是**按 zone 覆盖**的——传了什么就只画什么，没传的 zone 留空。
当前传了地图 zone，其余为空。

**下一步**：`activeComponents` 传空（让手机按布局默认值画全部组件），再单独指定地图 zone；
布局用 `DigiRing` + `green`。

---

## 七、调试手段

Ultra 的问题**不能靠看屏幕判断**。三个探针是必须的：

| 手段 | 输出 | 用来判什么 |
|---|---|---|
| 帧探针（每 5 s） | `frame probe <label> 64x36 view=WxH drawn=…% box=x0,y0..x1,y1 mean=…` | **画面有没有铺满**——看 `box`，不看肉眼 |
| 亮度图（每 30 s） | `frame map <label> 32x18` + 18 行字符图 | 黑在哪一块 |
| uiSync 寻址 | `ultra: uiSync frames address display=<uuid> keys=[…]` | 布局消息发给哪块屏 |
| 手机命令 | `ultra: phone command type=N data=k=v …` | 手机要求什么布局/调色 |

两个注意点：

- **判黑别看码率**。110/111 静止时 0.8 fps / 1 kbps 是保活帧。
- 亮度图的字符集刻意**不含十六进制字母**，避免被日志脱敏规则整行吃掉（本项目曾因此误判「日志缺失」）。

---

## 八、踩过的坑（改代码前必读）

| 坑 | 后果 |
|---|---|
| 半开（ultra on / theme off）仍发 panels | 中控整块黑，零报错 |
| `initialVideoStreams` 留空 | 面板永远等不到填充 |
| 加 iAP2 参数不改 `Iap2Endpoint.kt` children | 整条请求被拒，原有字段一起失效 |
| 只改一块 display 的主题名 | 两块屏按两套规则处理 |
| `setPresentationToDimSwitch` 顺序颠倒 | 闪一下 / 留残影 |
| 没在启动时释放残留投屏状态 | 仪表被中控镜像盖住 |
| 用码率判断出图 | 静止画面被误判为黑屏 |
| 主题模式仍套 safeArea/圆角遮罩 | 画面被二次裁剪，叠加真实问题 |

---

## 九、参考资料

都是公开仓库，**只读结论，不要复制代码**（GPL 系）：

| 仓库 | 与本项目的关系 | 价值 |
|---|---|---|
| [f-io/LIVI](https://github.com/f-io/LIVI) | 独立实现，Rust + 多平台 | 唯一另一个公开实现 `displayPanels` 的项目；`cornerMasks` 显式 `false` 即出自它 |
| [zgaiss/OP-Ultra](https://github.com/zgaiss/OP-Ultra) | **与本项目同一上游分支**（同 `xcertplay` 根工程、同包名体系、同 820A/Android 9） | Ultra 宽幅固定 1920×720，是 8:3 结论的来源之一；`resolvePhysicalSizeMm()` 按「当前像素÷最大像素」缩放毫米 |
| [omonob/MHI2-Carplay-Maps](https://github.com/omonob/MHI2-Carplay-Maps) | 不同平台（MIB2 High / QNX） | 方法论：画布与 viewArea 分离、流新鲜度看门狗、积压上限 |
| [luka-dev/mib2q-carplay-rgi](https://github.com/luka-dev/mib2q-carplay-rgi) | 同协议栈逆向研究 | 110/111 由 uuid 判定、altScreen 与 displays 数量必须配对、`initialVideoStreams` 非空 |

Apple 侧的事实来源是 `com.apple.MobileAsset.CarDDPAssets`（资产标识 `44C9FA31EC`、版本 `985`）——
仪表布局 id 就在这个包里，主题风格名跨厂商稳定。

---

## 十、接手建议

1. **先跑一遍现状**，确认 §5 的判据能复现。不要相信文档，要相信 `frame probe` 的 `box`。
2. **一次只改一个变量**。Ultra 的失败方式是「全黑且零报错」，两个变量一起动的结果无法归因。
3. **改 iAP2 参数前**先看 `Iap2Endpoint.kt`，两处同时改。
4. **保持普通模式可用**。Ultra 的所有改动都要在「关掉两个开关」后回归一次。
5. 最省的第一步是 §6.1 的零代码 A/B（中控声明 1920×720）。它能一次性回答
   「66% 是画布决定的，还是车机声明决定的」——这个问题决定后面所有路线。
