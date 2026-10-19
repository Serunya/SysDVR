package com.tailspin.sysdvr.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun parsesHello() {
        val hello = TestPackets.hello("03")
        assertEquals(Protocol.HELLO_SIZE, hello.size)
        assertEquals("03", Protocol.parseHello(hello, hello.size))
    }

    @Test
    fun rejectsInvalidHello() {
        val noTerminator = "SysDVR|030".toByteArray()
        assertNull(Protocol.parseHello(noTerminator, noTerminator.size))
        val wrongMagic = "SysDVX|03".toByteArray() + byteArrayOf(0)
        assertNull(Protocol.parseHello(wrongMagic, wrongMagic.size))
        val hello = TestPackets.hello("03")
        assertNull(Protocol.parseHello(hello, 9))
    }

    @Test
    fun encodesRequestLikeTheSysmodule() {
        val req = Protocol.encodeRequest(
            "03",
            StreamConfig(audioBatching = 2, useNalReplay = true, nalReplayOnlyKeyframes = true, turnOffConsoleScreen = true),
        )
        val expected = byteArrayOf(
            0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(),
            '0'.code.toByte(), '3'.code.toByte(),
            0b011, // video | audio
            0b111, // nal hash | inject pps/sps | hash only idr
            2,     // audio batching
            0b1,   // turn off screen
            0, 0, 0, 0, 0, 0,
        )
        assertArrayEquals(expected, req)
    }

    @Test
    fun featureFlagsAreNotSentToProtocol02() {
        val req = Protocol.encodeRequest("02", StreamConfig(turnOffConsoleScreen = true))
        assertEquals(0, req[9].toInt())
    }

    @Test
    fun encodesJpegRequest() {
        val fallback = Protocol.encodeRequest("03", StreamConfig(jpegMode = JpegMode.FALLBACK, jpegMaxFps = 25))
        assertEquals(Protocol.FEATURE_JPEG_FALLBACK, fallback[9].toInt())
        assertEquals(25, fallback[10].toInt())

        val only = Protocol.encodeRequest("03", StreamConfig(jpegMode = JpegMode.ONLY, turnOffConsoleScreen = true))
        assertEquals(Protocol.FEATURE_JPEG_ONLY or Protocol.FEATURE_TURN_OFF_SCREEN, only[9].toInt())

        val off = Protocol.encodeRequest("03", StreamConfig())
        assertEquals(0, off[10].toInt())

        val systemAudio = Protocol.encodeRequest("03", StreamConfig(systemAudio = true))
        assertEquals(Protocol.FEATURE_SYSTEM_AUDIO, systemAudio[9].toInt())
        val dock = Protocol.encodeRequest("03", StreamConfig(turnOffConsoleScreen = true, muteConsoleSpeaker = true, keepConsoleAwake = true))
        assertEquals(
            Protocol.FEATURE_TURN_OFF_SCREEN or Protocol.FEATURE_MUTE_SPEAKER or Protocol.FEATURE_KEEP_AWAKE,
            dock[9].toInt() and 0xFF,
        )

        // Pointless without audio
        assertEquals(0, Protocol.encodeRequest("03", StreamConfig(audio = false, systemAudio = true))[9].toInt())
    }

    @Test
    fun jpegHeaderValidation() {
        val h = PacketHeader()
        assertTrue(h.parse(TestPackets.jpeg(ByteArray(10)), 0))
        assertTrue(h.isJpeg)
        assertTrue(h.isVideo)
        // JPEG audio or JPEG replay are invalid
        assertFalse(h.parse(TestPackets.packet(Protocol.PKT_TYPE_AUDIO or Protocol.PKT_CONTENT_DATA or Protocol.PKT_CONTENT_JPEG, ByteArray(1)), 0))
        assertFalse(h.parse(TestPackets.packet(Protocol.PKT_TYPE_VIDEO or Protocol.PKT_CONTENT_REPLAY or Protocol.PKT_CONTENT_JPEG, ByteArray(0)), 0))
    }

    @Test
    fun parsesDeviceDescriptor() {
        val info = SysDvrDeviceInfo.parse("SysDVR|6.3|03|XAW10000000000\u0000")!!
        assertEquals("6.3", info.sysmoduleVersion)
        assertEquals("03", info.protocolVersion)
        assertEquals("XAW10000000000", info.serial)
        assertTrue(info.isProtocolSupported)

        assertFalse(SysDvrDeviceInfo.parse("SysDVR|5.0|01|X")!!.isProtocolSupported)
        assertNull(SysDvrDeviceInfo.parse("Pixel fastboot"))
        assertNull(SysDvrDeviceInfo.parse(null))
    }

    @Test
    fun headerValidation() {
        val h = PacketHeader()
        assertTrue(h.parse(TestPackets.video(byteArrayOf(1, 2, 3), timestamp = 0x1_0000_0002L), 0))
        assertEquals(3, h.dataSize)
        assertEquals(0x1_0000_0002L, h.timestampUs)
        assertTrue(h.isVideo)

        // both stream types
        assertFalse(h.parse(TestPackets.packet(0b111, ByteArray(0)), 0))
        // replay with a payload
        assertFalse(h.parse(TestPackets.packet(Protocol.PKT_TYPE_VIDEO or Protocol.PKT_CONTENT_REPLAY, ByteArray(4)), 0))
        // replay of audio
        assertFalse(h.parse(TestPackets.packet(Protocol.PKT_TYPE_AUDIO or Protocol.PKT_CONTENT_REPLAY, ByteArray(0)), 0))
        // reserved bits
        assertFalse(h.parse(TestPackets.packet(0x80 or Protocol.PKT_TYPE_VIDEO or Protocol.PKT_CONTENT_DATA, ByteArray(0)), 0))
        // too big
        val big = TestPackets.video(ByteArray(0)).also { it.putIntLE(4, Protocol.MAX_DATA_SIZE + 1) }
        assertFalse(h.parse(big, 0))
    }

    @Test
    fun readChunkIsUsbAligned() {
        assertEquals(0, Protocol.READ_CHUNK % 1024)
        assertTrue(Protocol.READ_CHUNK >= Protocol.MAX_PACKET_SIZE)
    }
}
