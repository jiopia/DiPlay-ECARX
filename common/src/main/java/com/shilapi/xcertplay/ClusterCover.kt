package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.shilapi.xcertplay.hud.NavigationOutputs
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The album cover the cluster's media card shows, delivered the only way that card accepts one.
 *
 * The card's art is loaded by the head unit's own `com.nostra13.universalimageloader` from a
 * **URL** — `http`, `file` or `content`, nothing else. A bitmap in hand is therefore worth nothing
 * to it, and a track that only exists inside CarPlay's stream has no URL of its own to offer. This
 * object makes one: the cover CarPlay already decoded is compressed to JPEG and served by a socket
 * bound to `127.0.0.1`, and the URL goes to the media bridge, which puts it in the payload the card
 * reads. The revision in the path is what defeats the loader's cache — a URL that never changed
 * would be answered from memory and the card would keep the previous track's picture.
 *
 * Loopback only, one route prefix (`/cover/`), read-only, and it never serves anything but the
 * current cover: nothing outside this device can reach the socket, and nothing inside it can make
 * this app serve a file of its choosing.
 */
internal object ClusterCover {
    private const val TAG = "DiPlay-CLUSTER-COVER"

    /**
     * The card draws a small square, and the cluster's loader keeps what it decodes in a bitmap
     * cache of its own. Sending a full-size transfer wastes both the JPEG and that cache.
     */
    private const val MAX_EDGE = 480

    /** Enough for a 480 px square; the card is a fraction of that on the panel. */
    private const val JPEG_QUALITY = 82

    private var context: Context? = null
    private var server: CoverServer? = null

    /** The bitmap whose bytes the server holds, compared by identity — the cache reuses bitmaps. */
    private var published: Bitmap? = null
    private var revision = 0

    /** Called once the app has a context; nothing is served before. Safe to call again. */
    fun attach(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * A cover arrived for the track on screen. May run on any thread; called for every metadata
     * update, so the common cases are "same image" and "no image" and both return immediately.
     */
    fun offer(artwork: Bitmap?) {
        if (artwork == null || artwork.isRecycled || artwork === published) return
        if (context == null) return
        val bytes = encode(artwork)
        if (bytes == null) {
            Log.i(TAG, "cover encode failed; keeping the last one")
            return
        }
        var target = server
        if (target == null) {
            target = CoverServer()
            server = target
            Log.i(TAG, "cover served on 127.0.0.1:${target.port}")
        }
        published = artwork
        revision += 1
        target.put(bytes)
        NavigationOutputs.onArtwork(target.url(revision))
    }

    /** JPEG bytes for the card, scaled down to [MAX_EDGE]; `null` if the encoder refused. */
    private fun encode(artwork: Bitmap): ByteArray? {
        val longest = maxOf(artwork.width, artwork.height)
        val scaled = if (longest > MAX_EDGE) {
            val ratio = MAX_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(
                artwork,
                (artwork.width * ratio).toInt().coerceAtLeast(1),
                (artwork.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            artwork
        }
        val out = ByteArrayOutputStream()
        val compressed = scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== artwork) scaled.recycle()
        return if (compressed) out.toByteArray() else null
    }

    /**
     * Serves the current cover over loopback.
     *
     * One thread, one connection at a time, with a read timeout: the only client is the head unit's
     * image loader asking for one small file, and a client that stalls must not be able to hold the
     * socket open. Any request the server cannot parse is answered `404` and closed.
     */
    private class CoverServer {
        private val socket = ServerSocket(0, 4, InetAddress.getByName(LOOPBACK))
        val port: Int = socket.localPort

        @Volatile private var payload: ByteArray = EMPTY

        init {
            val thread = Thread(this::listen, "diplay-cluster-cover")
            thread.isDaemon = true
            thread.start()
        }

        fun put(bytes: ByteArray) {
            payload = bytes
        }

        fun url(revision: Int): String = "http://$LOOPBACK:$port/cover/$revision.jpg"

        private fun listen() {
            while (true) {
                val client = try {
                    socket.accept()
                } catch (error: IOException) {
                    // Closed socket: nothing left to serve, and nothing to report.
                    return
                }
                try {
                    respond(client)
                } catch (error: IOException) {
                    // A client that vanished mid-request is the loader giving up, not a fault here.
                } finally {
                    try {
                        client.close()
                    } catch (error: IOException) {
                        // Already closed.
                    }
                }
            }
        }

        private fun respond(client: Socket) {
            client.soTimeout = READ_TIMEOUT_MILLIS
            val input = client.getInputStream()
            val request = readLine(input) ?: return
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
            }
            val path = request.split(' ').getOrNull(1).orEmpty()
            val body = if (path.startsWith(COVER_PATH)) payload else EMPTY
            val out = client.getOutputStream()
            if (body.isEmpty()) {
                out.write(NOT_FOUND.toByteArray())
            } else {
                out.write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: " + body.size + "\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(),
                )
                out.write(body)
            }
            out.flush()
        }

        private fun readLine(input: InputStream): String? {
            val builder = StringBuilder()
            while (true) {
                val next = input.read()
                if (next == -1) return if (builder.isEmpty()) null else builder.toString()
                if (next == NEWLINE) return builder.toString().trimEnd('\r')
                builder.append(next.toChar())
                // A request with no line ends is dropped rather than buffered without bound.
                if (builder.length > MAX_LINE) return null
            }
        }

        private companion object {
            const val LOOPBACK = "127.0.0.1"
            const val COVER_PATH = "/cover/"
            const val READ_TIMEOUT_MILLIS = 2000
            const val MAX_LINE = 512
            const val NEWLINE = '\n'.code
            val EMPTY = ByteArray(0)
            const val NOT_FOUND = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        }
    }
}
