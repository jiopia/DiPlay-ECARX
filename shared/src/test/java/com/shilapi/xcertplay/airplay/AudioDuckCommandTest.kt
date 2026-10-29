package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** duckAudio and unduckAudio mark each navigation prompt (#946); they reach the sink as one duck state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AudioDuckCommandTest {
    @Test fun duckCommandsReachTheMediaHandlerAndOtherCommandsDoNot() {
        val ducks = mutableListOf<Pair<Boolean, Float>>()
        val commands = mutableListOf<String>()
        val session = session(
            object : AirPlayMediaHandler {
                override fun onAudioDuck(session: AirPlaySession, ducked: Boolean, volumeDb: Float) {
                    ducks += ducked to volumeDb
                }
            },
            object : AirPlaySessionListener {
                override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) { commands += type }
            },
        )
        try {
            // duckAudio carries the level the iPhone wants the car's media at; unduckAudio carries none.
            command(session, "duckAudio", volume = -14f)
            for (type in listOf("modesChanged", "unduckAudio", "requestUI")) command(session, type)
        } finally { session.close() }
        assertEquals(listOf(true to -14f, false to 0f), ducks)
        assertEquals(listOf("duckAudio", "modesChanged", "unduckAudio", "requestUI"), commands)
    }

    @Test fun engineForwardsDucksAndEndsAPromptWhoseSessionCloses() {
        val ducks = mutableListOf<Boolean>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioDuck(ducked: Boolean, volumeDb: Float) { ducks += ducked }
        })
        val first = session(engine)
        val other = session(engine)
        try {
            engine.onAudioDuck(first, true)
            engine.onAudioDuck(first, false)
            engine.onSessionClosed(first)
            assertEquals(listOf(true, false), ducks)

            engine.onAudioDuck(first, true)
            engine.onSessionClosed(other)
            assertEquals(listOf(true, false, true), ducks)
            engine.onSessionClosed(first)
            assertEquals(listOf(true, false, true, false), ducks)
        } finally {
            first.close()
            other.close()
        }
    }

    private fun command(session: AirPlaySession, type: String, volume: Float? = null) {
        val params = mutableMapOf<String, Any?>("durationMs" to 500L)
        if (volume != null) params["volume"] = volume
        val body = BplistCodec.encode(mapOf("type" to type, "params" to params))
        AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
            .apply { isAccessible = true }
            .invoke(session, RtspMessage.Request("POST", "/command", "RTSP/1.0", emptyMap(), body))
    }

    private fun session(
        media: AirPlayMediaHandler,
        listener: AirPlaySessionListener = object : AirPlaySessionListener {},
    ): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
            main = AirPlayDisplayConfig(800, 480)),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = listener,
        media = media,
    )
}
