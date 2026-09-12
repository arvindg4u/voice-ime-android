package com.voiceime

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal RFC 6455 WebSocket client over a raw TLS socket.
 *
 * Device-proven against OkHttp on this exact endpoint: OkHttp's handshake
 * succeeds (101) but its writer never gets the setup frame onto the wire
 * (send=true, zero application bytes in tcpdump, zero server replies), while
 * this client — same setup bytes, same key, same network — receives
 * setupComplete + transcripts. No extensions, no compression, no connection
 * pool: masked client frames out, server frames in, one reader thread.
 *
 * Threading: the caller owns [onText] delivery (reader thread — never touch
 * views, just forward). All public methods are safe to call from any thread
 * and never throw into the caller for normal teardown; terminal callbacks
 * ([onClosed]/[onFailure]) fire at most once. The API key travels only in
 * the handshake request line and is never logged.
 */
internal class LiveSocket(
    private val host: String,
    private val path: String,
    private val onText: (String) -> Unit,
    private val onClosed: (code: Int, reason: String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val log: (String, String) -> Unit,
) {
    private val sendQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var terminal = false
    private var writerThread: Thread? = null
    private var readerThread: Thread? = null

    /** Connects, handshakes, and starts the reader/writer threads. */
    @Throws(LiveSocketException::class)
    fun connect() {
        val keyBytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val wsKey = java.util.Base64.getEncoder().encodeToString(keyBytes)
        val sock = try {
            SSLSocketFactory.getDefault().createSocket(host, 443).apply {
                soTimeout = 0
                tcpNoDelay = true
            }
        } catch (e: Exception) {
            throw LiveSocketException("Network error — check connection", e)
        }
        socket = sock
        val input = try {
            sock.getInputStream()
        } catch (e: Exception) {
            closeSocket()
            throw LiveSocketException("Network error — check connection", e)
        }
        val output = try {
            sock.getOutputStream()
        } catch (e: Exception) {
            closeSocket()
            throw LiveSocketException("Network error — check connection", e)
        }
        out = output
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }
        try {
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (e: Exception) {
            closeSocket()
            throw LiveSocketException("Network error — check connection", e)
        }
        val statusLine = try {
            readHttpHeaders(input)
        } catch (e: LiveSocketException) {
            closeSocket()
            throw e
        } catch (e: Exception) {
            closeSocket()
            throw LiveSocketException("Network error — check connection", e)
        }
        log("live-wire", "handshake " + statusLine.take(60))
        if (!statusLine.contains("101")) {
            closeSocket()
            throw LiveSocketException(mapHandshakeError(statusLine))
        }
        log("live-wire", "handshake ok")
        writerThread = Thread({ writeLoop() }, "VoiceImeLiveWriter").apply {
            isDaemon = true
            start()
        }
        readerThread = Thread({ readLoop(input) }, "VoiceImeLiveReader").apply {
            isDaemon = true
            start()
        }
    }

    /** Enqueues one UTF-8 text frame. Returns false when already terminal. */
    fun send(text: String): Boolean {
        if (terminal) return false
        return try {
            sendQueue.put(encodeTextFrame(text))
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** Sends a close frame and tears the socket down. Idempotent. */
    fun close(code: Int = 1000, reason: String = "") {
        if (terminal) return
        terminal = true
        try {
            sendQueue.clear()
            out?.let { writeFrame(it, 0x8, closePayload(code, reason)) }
        } catch (_: Exception) {
            // Best effort; the socket close below is what matters.
        } finally {
            closeSocket()
            interruptThreads()
            try {
                onClosed(code, reason)
            } catch (_: Exception) {
                // Listener must never throw into us.
            }
        }
    }

    private fun fail(t: Throwable) {
        if (terminal) return
        terminal = true
        closeSocket()
        interruptThreads()
        try {
            onFailure(t)
        } catch (_: Exception) {
            // Listener must never throw into us.
        }
    }

    private fun writeLoop() {
        try {
            while (!terminal) {
                val frame = sendQueue.take()
                val stream = out ?: return
                try {
                    stream.write(frame)
                    stream.flush()
                } catch (e: Exception) {
                    if (!terminal) fail(LiveSocketException("Network error — check connection", e))
                    return
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun readLoop(input: InputStream) {
        try {
            while (!terminal) {
                val message = readFrame(input) ?: return
                if (message.opcode == 0x8) {
                    if (!terminal) {
                        terminal = true
                        closeSocket()
                        try {
                            onClosed(message.closeCode, message.closeReason)
                        } catch (_: Exception) {
                            // Listener must never throw into us.
                        }
                    }
                    return
                }
                if (message.opcode == 0x9) {
                    // Ping: answer with pong carrying the same payload.
                    try {
                        out?.let { writeFrame(it, 0xA, message.payload) }
                    } catch (_: Exception) {
                        // Pong is best-effort.
                    }
                    continue
                }
                if (message.opcode == 0x1 || message.opcode == 0x2) {
                    val text = try {
                        String(message.payload, Charsets.UTF_8)
                    } catch (e: Exception) {
                        continue
                    }
                    try {
                        onText(text)
                    } catch (_: Exception) {
                        // Listener must never throw into us.
                    }
                }
                // 0xA pong and reserved opcodes: ignore.
            }
        } catch (e: EOFException) {
            finishQuietly(normalClose = true)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (!terminal) {
                // A bare socket error here usually means the server went
                // away mid-turn; surface it so the owner can commit fallback.
                fail(LiveSocketException("Network error — check connection", e))
            }
        }
    }

    private fun finishQuietly(normalClose: Boolean) {
        if (terminal) return
        terminal = true
        closeSocket()
        try {
            if (normalClose) onClosed(1000, "") else onFailure(LiveSocketException("Network error — check connection"))
        } catch (_: Exception) {
            // Listener must never throw into us.
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
            // Already gone.
        } finally {
            socket = null
            out = null
        }
    }

    private fun interruptThreads() {
        try {
            writerThread?.interrupt()
        } catch (_: Exception) {
            // Already gone.
        }
        try {
            readerThread?.interrupt()
        } catch (_: Exception) {
            // Already gone.
        }
        writerThread = null
        readerThread = null
    }

    /**
     * Reads the HTTP status line, consuming all headers. Returns the status
     * line (e.g. "HTTP/1.1 101 Switching Protocols"). Throws
     * [LiveSocketException] with a user-safe message on rejection.
     */
    @Throws(LiveSocketException::class)
    private fun readHttpHeaders(input: InputStream): String {
        val raw = ByteArrayOutputStream()
        val window = ByteArray(4)
        var filled = 0
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw LiveSocketException("Network error — check connection")
            }
            val byte = try {
                input.read()
            } catch (e: Exception) {
                throw LiveSocketException("Network error — check connection", e)
            }
            if (byte < 0) throw LiveSocketException("Network error — check connection")
            raw.write(byte)
            if (raw.size() > MAX_HEADER_BYTES) {
                throw LiveSocketException("Server error — try again")
            }
            window[filled % 4] = byte.toByte()
            filled++
            if (filled >= 4 &&
                window[(filled - 4) % 4] == '\r'.code.toByte() &&
                window[(filled - 3) % 4] == '\n'.code.toByte() &&
                window[(filled - 2) % 4] == '\r'.code.toByte() &&
                window[(filled - 1) % 4] == '\n'.code.toByte()
            ) {
                break
            }
        }
        val head = raw.toString(Charsets.US_ASCII.name())
        val statusLine = head.lineSequence().firstOrNull().orEmpty()
        if (!statusLine.contains("101")) {
            throw LiveSocketException(mapHandshakeError(statusLine + " " + head.take(300)))
        }
        return statusLine
    }

    private data class WsMessage(val opcode: Int, val payload: ByteArray, val closeCode: Int = 1000, val closeReason: String = "")

    /** Reads one complete message, assembling fragmented continuation frames. */
    @Throws(EOFException::class)
    private fun readFrame(input: InputStream): WsMessage? {
        var opcode = -1
        val assembled = ByteArrayOutputStream()
        while (true) {
            val header = readExactly(input, 2)
            val first = header[0].toInt() and 0xFF
            val second = header[1].toInt() and 0xFF
            val fin = (first ushr 7) and 1
            // Servers MUST NOT mask; a masked server frame is a protocol error.
            val masked = (second ushr 7) and 1
            var length = (second and 0x7F).toLong()
            if (masked != 0) throw EOFException("masked server frame")
            when (length) {
                126L -> length = readUShort(input).toLong()
                127L -> {
                    length = readULong(input)
                    if (length < 0 || length > MAX_FRAME_BYTES) throw EOFException("frame too large")
                }
            }
            if (length > MAX_FRAME_BYTES) throw EOFException("frame too large")
            val payload = if (length > 0) readExactly(input, length.toInt()) else ByteArray(0)
            val frameOp = first and 0x0F
            if (frameOp == 0x8) {
                // Close: optional 2-byte code + UTF-8 reason.
                var code = 1000
                var reason = ""
                if (payload.size >= 2) {
                    code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                    if (payload.size > 2) {
                        reason = try {
                            String(payload, 2, payload.size - 2, Charsets.UTF_8)
                        } catch (e: Exception) {
                            ""
                        }
                    }
                }
                return WsMessage(0x8, ByteArray(0), code, reason)
            }
            if (frameOp == 0x9 || frameOp == 0xA) {
                // Ping/pong are standalone control frames (never fragmented).
                if (fin == 0) throw EOFException("fragmented control frame")
                return WsMessage(frameOp, payload)
            }
            if (frameOp == 0x0) {
                if (opcode < 0) throw EOFException("stray continuation")
            } else {
                if (opcode >= 0) throw EOFException("interleaved frame")
                opcode = frameOp
            }
            assembled.write(payload)
            if (assembled.size() > MAX_FRAME_BYTES) throw EOFException("message too large")
            if (fin == 1) return WsMessage(opcode, assembled.toByteArray())
        }
    }

    @Throws(EOFException::class)
    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val got = try {
                input.read(out, read, count - read)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw EOFException("interrupted")
            } catch (e: EOFException) {
                throw e
            } catch (e: Exception) {
                throw EOFException(e.message)
            }
            if (got < 0) throw EOFException("closed")
            read += got
        }
        return out
    }

    @Throws(EOFException::class)
    private fun readUShort(input: InputStream): Int {
        val bytes = readExactly(input, 2)
        return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    }

    @Throws(EOFException::class)
    private fun readULong(input: InputStream): Long {
        val bytes = readExactly(input, 8)
        var value = 0L
        for (byte in bytes) value = (value shl 8) or (byte.toLong() and 0xFF)
        return value
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L
        private const val MAX_HEADER_BYTES = 16_384
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024

        /** One masked client text frame, exactly like the proven raw path. */
        internal fun encodeTextFrame(text: String): ByteArray {
            return encodeFrame(0x1, text.toByteArray(Charsets.UTF_8))
        }

        /** Encodes and writes one masked client frame to [stream]. */
        @Throws(java.io.IOException::class)
        internal fun writeFrame(stream: OutputStream, opcode: Int, payload: ByteArray) {
            val frame = encodeFrame(opcode, payload)
            stream.write(frame)
            stream.flush()
        }

        internal fun encodeFrame(opcode: Int, payload: ByteArray): ByteArray {
            val mask = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
            val header = ByteArrayOutputStream()
            header.write((0x80 or (opcode and 0x0F)))
            val length = payload.size
            when {
                length < 126 -> header.write(0x80 or length)
                length < 65536 -> {
                    header.write(0x80 or 126)
                    header.write((length ushr 8) and 0xFF)
                    header.write(length and 0xFF)
                }
                else -> {
                    header.write(0x80 or 127)
                    for (shift in 56 downTo 0 step 8) {
                        header.write(((length.toLong() ushr shift) and 0xFF).toInt())
                    }
                }
            }
            header.write(mask)
            val masked = ByteArray(length) { index ->
                (payload[index] xor mask[index % 4])
            }
            val frame = ByteArrayOutputStream(header.size() + length)
            frame.write(header.toByteArray())
            frame.write(masked)
            return frame.toByteArray()
        }

        internal fun closePayload(code: Int, reason: String): ByteArray {
            val reasonBytes = reason.toByteArray(Charsets.UTF_8)
            val out = ByteArray(2 + reasonBytes.size)
            out[0] = ((code shr 8) and 0xFF).toByte()
            out[1] = (code and 0xFF).toByte()
            reasonBytes.copyInto(out, 2)
            return out
        }

        /** Maps a non-101 handshake reply to a user-safe message. */
        internal fun mapHandshakeError(statusAndHead: String): String {
            val code = Regex("HTTP/\\S+\\s+(\\d{3})").find(statusAndHead)?.groupValues?.getOrNull(1)
            return when (code) {
                "401", "403" -> mapHttpError(code.toInt(), statusAndHead)
                "429" -> "Rate limited — retry"
                else -> if (code != null) "Server error (HTTP $code)" else "Network error — check connection"
            }
        }
    }
}

/** User-safe Live socket failure; message is safe to show. */
internal class LiveSocketException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
