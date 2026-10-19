package com.tailspin.sysdvr.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SysDvrSessionTest {
    private val ok03 = ByteArray(Protocol.RESPONSE_SIZE_V03).also { it.putIntLE(0, Protocol.HANDSHAKE_OK) }
    private val sink = RecordingSink()
    private val stats = ConnectionStats()

    private var slept = 0L

    private fun session(transport: FakeTransport, config: StreamConfig = StreamConfig(), clock: () -> Long = { 0 }) =
        SysDvrSession(transport, config, sink, stats, clock) { slept += it }

    @Test
    fun handshakeSkipsStaleDataAndTimeouts() {
        val t = FakeTransport(TestPackets.video(byteArrayOf(1)), null, TestPackets.hello("03"), ok03)
        val config = StreamConfig()
        assertEquals("03", session(t, config).handshake { true })
        assertArrayEquals(Protocol.encodeRequest("03", config), t.writes.single())
    }

    @Test
    fun stopsReadingWhenTheConsoleKeepsStreamingTheOldSession() {
        var now = 0L
        val stale = Array<ByteArray?>(10) { TestPackets.video(byteArrayOf(1)) }
        val t = FakeTransport(*stale, TestPackets.hello("03"), ok03)
        assertEquals("03", session(t, clock = { now += 200; now }).handshake { true })
        // The pause must be long enough for the console's 1.5 s send timeout
        assertEquals(true, slept >= 1500)
    }

    @Test
    fun handshakeProtocol02() {
        val ok02 = ByteArray(4).also { it.putIntLE(0, Protocol.HANDSHAKE_OK) }
        val t = FakeTransport(TestPackets.hello("02"), ok02)
        assertEquals("02", session(t).handshake { true })
    }

    @Test(expected = HandshakeException::class)
    fun handshakeRejected() {
        val rejected = ByteArray(Protocol.RESPONSE_SIZE_V03).also { it.putIntLE(0, 1) }
        session(FakeTransport(TestPackets.hello("03"), rejected)).handshake { true }
    }

    @Test(expected = HandshakeException::class)
    fun unsupportedVersion() {
        session(FakeTransport(TestPackets.hello("09"))).handshake { true }
    }

    @Test
    fun handshakeCancelled() {
        assertNull(session(FakeTransport()).handshake { false })
    }

    @Test
    fun streamsAndReplays() {
        val t = FakeTransport(
            TestPackets.video(byteArrayOf(1, 2), slot = 5, timestamp = 100),
            TestPackets.audio(byteArrayOf(3, 4)),
            TestPackets.replay(5),
            TestPackets.replay(6), // unknown slot, dropped
        )
        session(t).stream { !t.exhausted }

        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(1, 2)), sink.video.map { it.first })
        assertEquals(100L, sink.video[0].second)
        assertEquals(listOf(listOf<Byte>(3, 4)), sink.audio)
        assertEquals(1, stats.replayedPackets)
        assertEquals(1, stats.replayMisses)
    }

    @Test
    fun jpegGoesToItsOwnCallback() {
        val t = FakeTransport(TestPackets.jpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte())), TestPackets.video(byteArrayOf(1)))
        session(t).stream { !t.exhausted }
        assertEquals(listOf(listOf(0xFF.toByte(), 0xD8.toByte())), sink.jpegs)
        assertEquals(1, sink.video.size)
        assertEquals(1, stats.jpegPackets)
    }

    @Test
    fun consoleErrorsAreReported() {
        val body = ByteArray(32).also { it.putIntLE(0, 1); it.putIntLE(4, 0x1234) }
        val t = FakeTransport(TestPackets.packet(Protocol.PKT_TYPE_VIDEO or Protocol.PKT_CONTENT_ERROR, body))
        session(t).stream { !t.exhausted }
        assertEquals(1, sink.errors.size)
        assertEquals(0, sink.video.size)
    }

    @Test(expected = StallException::class)
    fun detectsStall() {
        var now = 0L
        session(FakeTransport(), clock = { now += 1000; now }).stream { true }
    }
}
