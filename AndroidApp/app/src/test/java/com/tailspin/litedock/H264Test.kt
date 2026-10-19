package com.tailspin.litedock

import com.tailspin.litedock.video.H264
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H264Test {
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0, 0, 3, 1)
    private val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x11, 0x22)

    @Test
    fun stripsInjectedParameterSets() {
        val packet = H264.SPS + H264.PPS + idr
        assertEquals(H264.SPS.size + H264.PPS.size, H264.leadingParameterSetsLength(packet, 0, packet.size))
    }

    @Test
    fun handlesOffsetAndThreeByteStartCodes() {
        val sps3 = H264.SPS.copyOfRange(1, H264.SPS.size)
        val packet = byteArrayOf(9, 9) + sps3 + idr
        assertEquals(sps3.size, H264.leadingParameterSetsLength(packet, 2, packet.size - 2))
    }

    @Test
    fun leavesPlainFramesAlone() {
        assertEquals(0, H264.leadingParameterSetsLength(idr, 0, idr.size))
        assertEquals(0, H264.leadingParameterSetsLength(pFrame, 0, pFrame.size))
    }

    @Test
    fun onlyParameterSets() {
        val packet = H264.SPS + H264.PPS
        assertEquals(packet.size, H264.leadingParameterSetsLength(packet, 0, packet.size))
    }

    @Test
    fun detectsIdr() {
        assertTrue(H264.containsIdr(H264.SPS + H264.PPS + idr, 0, H264.SPS.size + H264.PPS.size + idr.size))
        assertFalse(H264.containsIdr(pFrame, 0, pFrame.size))
    }
}
