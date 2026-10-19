package com.tailspin.sysdvr.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class PacketParserTest {
    private val parser = PacketParser()
    private val payloads = mutableListOf<List<Byte>>()
    private val handler = PacketParser.Handler { h, buf, off ->
        payloads += buf.copyOfRange(off, off + h.dataSize).toList()
    }

    private fun feed(bytes: ByteArray) {
        val off = parser.prepareWrite()
        bytes.copyInto(parser.buffer, off)
        parser.commit(bytes.size)
        parser.drain(handler)
    }

    @Test
    fun onePacketPerTransfer() {
        feed(TestPackets.video(byteArrayOf(1, 2, 3)))
        feed(TestPackets.audio(byteArrayOf(4, 5)))
        assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4, 5)), payloads)
        assertEquals(0, parser.buffered)
    }

    @Test
    fun packetSplitAcrossTransfers() {
        val p = TestPackets.video(ByteArray(1000) { it.toByte() })
        feed(p.copyOfRange(0, 7))
        feed(p.copyOfRange(7, 500))
        assertEquals(0, payloads.size)
        feed(p.copyOfRange(500, p.size))
        assertEquals(1, payloads.size)
        assertEquals(1000, payloads[0].size)
    }

    @Test
    fun mergedTransfers() {
        feed(TestPackets.concat(TestPackets.video(byteArrayOf(1)), TestPackets.audio(byteArrayOf(2)), TestPackets.replay(3)))
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2), listOf()), payloads)
    }

    @Test
    fun resyncsAfterGarbage() {
        feed(TestPackets.concat(byteArrayOf(9, 9, 0xCC.toByte(), 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9), TestPackets.video(byteArrayOf(7))))
        assertEquals(listOf(listOf<Byte>(7)), payloads)
        assertEquals(true, parser.resyncs > 0)
    }

    @Test
    fun keepsPartialMagicWhileResyncing() {
        val p = TestPackets.video(byteArrayOf(42))
        // 20 garbage bytes ending with the first 3 magic bytes, then the rest of the packet
        val garbage = ByteArray(17) { 1 } + p.copyOfRange(0, 3)
        feed(garbage)
        assertEquals(0, payloads.size)
        feed(p.copyOfRange(3, p.size))
        assertEquals(listOf(listOf<Byte>(42)), payloads)
    }

    @Test
    fun maxSizePacketsFit() {
        val big = TestPackets.video(ByteArray(Protocol.MAX_DATA_SIZE))
        // Worst case: a partial packet is buffered when a full read arrives
        feed(big.copyOfRange(0, big.size - 1))
        assertEquals(true, parser.writableLength + parser.buffered >= Protocol.READ_CHUNK)
        feed(TestPackets.concat(big.copyOfRange(big.size - 1, big.size), big))
        assertEquals(2, payloads.size)
    }
}
