package com.tailspin.sysdvr.usb

import java.io.IOException

internal class HandshakeException(message: String) : IOException(message)

internal class StallException(message: String) : IOException(message)

/**
 * One USB connection to the console: handshake + packet loop. Runs entirely on the caller thread.
 * Both [handshake] and [stream] can be called again on the same transport after a failure,
 * the console goes back to sending the hello packet ~1.5 s after it stops being able to send data.
 */
internal class SysDvrSession(
    private val transport: UsbTransport,
    private val config: StreamConfig,
    private val sink: PacketSink,
    private val stats: ConnectionStats,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) : PacketParser.Handler {

    private val parser = PacketParser()
    private val replay = ReplayTable()

    /** Returns the negotiated protocol version, or null if [isActive] turned false while waiting. */
    fun handshake(isActive: () -> Boolean): String? {
        parser.reset()
        replay.clear()
        val buf = parser.buffer

        // Wait for "SysDVR|03\0". Anything else is stale stream data from a previous session.
        var hello: String? = null
        var staleSince = 0L
        while (hello == null) {
            if (!isActive()) return null
            val n = transport.read(buf, 0, Protocol.READ_CHUNK, HELLO_TIMEOUT_MS)
            if (n <= 0) {
                staleSince = 0
                continue
            }
            hello = Protocol.parseHello(buf, n)
            if (hello != null) break

            // If we reconnect faster than the console's send timeout (~1.5 s) it never notices: we keep
            // draining the old session and it never sends a hello. Stop reading so its writes time out.
            val now = clockMs()
            if (staleSince == 0L) staleSince = now
            else if (now - staleSince > STALE_DATA_MS) {
                var waited = 0L
                while (waited < CONSOLE_TIMEOUT_WAIT_MS && isActive()) {
                    sleepMs(100)
                    waited += 100
                }
                staleSince = 0
            }
        }
        val version = hello ?: return null

        if (version !in Protocol.SUPPORTED_VERSIONS)
            throw HandshakeException("Unsupported protocol version $version, update the app or the sysmodule")

        val request = Protocol.encodeRequest(version, config)
        if (transport.write(request, 0, request.size, IO_TIMEOUT_MS) != request.size)
            throw HandshakeException("Failed to send the handshake request")

        val expected = Protocol.responseSize(version)
        val n = transport.read(buf, 0, Protocol.READ_CHUNK, IO_TIMEOUT_MS)
        if (n != expected)
            throw HandshakeException("Invalid handshake response size $n, expected $expected")

        val code = buf.intLE(0)
        if (code != Protocol.HANDSHAKE_OK)
            throw HandshakeException("Console rejected the handshake: ${Protocol.describeHandshakeCode(code)}")

        return version
    }

    /** Reads packets until [isActive] turns false. Throws on stall or I/O errors. */
    fun stream(isActive: () -> Boolean) {
        parser.reset()
        var lastData = clockMs()

        while (isActive()) {
            val offset = parser.prepareWrite()
            val n = transport.read(parser.buffer, offset, Protocol.READ_CHUNK, READ_TIMEOUT_MS)
            if (n <= 0) {
                if (clockMs() - lastData > STALL_TIMEOUT_MS)
                    throw StallException("No data from the console for $STALL_TIMEOUT_MS ms")
                continue
            }

            lastData = clockMs()
            stats.bytesReceived += n
            parser.commit(n)
            parser.drain(this)
            stats.resyncs = parser.resyncs
        }
    }

    override fun onPacket(header: PacketHeader, buf: ByteArray, dataOffset: Int) {
        val size = header.dataSize
        when {
            header.isError -> {
                stats.consoleErrors++
                sink.onConsoleError(Protocol.describeConsoleError(buf, dataOffset, size))
            }

            header.isJpeg -> {
                stats.jpegPackets++
                sink.onJpeg(buf, dataOffset, size, header.timestampUs)
            }

            header.isAudio -> {
                stats.audioPackets++
                sink.onAudio(buf, dataOffset, size, header.timestampUs)
            }

            header.isReplay -> {
                val slot = header.replaySlot
                val cached = if (slot == Protocol.NO_REPLAY_SLOT) null else replay.get(slot)
                if (cached == null) {
                    stats.replayMisses++
                    return
                }
                stats.videoPackets++
                stats.replayedPackets++
                sink.onVideo(cached, 0, replay.lengthOf(slot), header.timestampUs)
            }

            else -> {
                if (config.useNalReplay && header.replaySlot != Protocol.NO_REPLAY_SLOT)
                    replay.store(header.replaySlot, buf, dataOffset, size)
                stats.videoPackets++
                sink.onVideo(buf, dataOffset, size, header.timestampUs)
            }
        }
    }

    companion object {
        // The console retries the hello every second
        const val HELLO_TIMEOUT_MS = 1000
        const val IO_TIMEOUT_MS = 1500

        // Stream data instead of a hello for this long means the console still runs the old session
        const val STALE_DATA_MS = 500L
        const val CONSOLE_TIMEOUT_WAIT_MS = 2500L
        const val READ_TIMEOUT_MS = 500

        // Audio alone arrives every ~21-100 ms, so a few seconds of silence means a dead stream
        const val STALL_TIMEOUT_MS = 3000L
    }
}
