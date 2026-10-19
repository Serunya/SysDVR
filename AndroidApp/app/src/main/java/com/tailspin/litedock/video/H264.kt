package com.tailspin.litedock.video

/** Properties of the stream produced by the console's grc encoder, see Client/Core/StreamInfo.cs. */
internal object H264 {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val MAX_FRAME_SIZE = 0x54000

    // Hardcoded in the sysmodule as well, grc only emits them once when a game starts
    val SPS = byteArrayOf(
        0x00, 0x00, 0x00, 0x01, 0x67, 0x64, 0x0C, 0x20, 0xAC.toByte(), 0x2B, 0x40, 0x28,
        0x02, 0xDD.toByte(), 0x35, 0x01, 0x0D, 0x01, 0xE0.toByte(), 0x80.toByte(),
    )
    val PPS = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68, 0xEE.toByte(), 0x3C, 0xB0.toByte())

    /**
     * [SPS] with a VUI bitstream_restriction added: max_num_reorder_frames=0, max_dec_frame_buffering=1.
     * Without it decoders must assume up to MaxDpbFrames (5 at 720p level 3.2) of reordering and hold
     * frames back, even though the stream has no B-frames (pic_order_cnt_type=2). Same trick as Moonlight.
     */
    val SPS_LOW_LATENCY = byteArrayOf(
        0x00, 0x00, 0x00, 0x01, 0x67, 0x64, 0x0C, 0x20, 0xAC.toByte(), 0x2B, 0x40, 0x28,
        0x02, 0xDD.toByte(), 0x35, 0x01, 0x0D, 0x01, 0xE1.toByte(), 0xB4.toByte(), 0x11, 0x08, 0xD4.toByte(),
    )

    private const val NAL_IDR = 5
    private const val NAL_SEI = 6
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
    private const val NAL_AUD = 9

    /**
     * Length of the SPS/PPS NALs at the start of the buffer (the sysmodule prepends them to some IDR
     * frames), 0 if there are none. Used to drop them so the decoder keeps our patched SPS.
     */
    fun leadingParameterSetsLength(data: ByteArray, offset: Int, length: Int): Int {
        val end = offset + length
        var pos = offset
        while (true) {
            val nal = startCodeAt(data, pos, end)
            if (nal < 0) return pos - offset
            val type = data[nal].toInt() and 0x1F
            if (type != NAL_SPS && type != NAL_PPS) return pos - offset

            var i = nal + 1
            while (i < end && startCodeAt(data, i, end) < 0) i++
            if (i >= end) return length
            pos = i
        }
    }

    /** Header byte of the first slice NAL (skipping SPS/PPS/SEI/AUD), or -1. */
    fun firstSliceHeader(data: ByteArray, offset: Int, length: Int): Int {
        val end = offset + length
        var i = offset
        while (i < end) {
            val nal = startCodeAt(data, i, end)
            if (nal >= 0) {
                val header = data[nal].toInt() and 0xFF
                when (header and 0x1F) {
                    NAL_SEI, NAL_SPS, NAL_PPS, NAL_AUD -> i = nal
                    else -> return header
                }
            }
            i++
        }
        return -1
    }

    /** If a 3 or 4 byte start code begins at [i], returns the index of the NAL header byte, else -1. */
    private fun startCodeAt(data: ByteArray, i: Int, end: Int): Int {
        if (i + 3 < end && data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
            if (data[i + 2].toInt() == 1) return i + 3
            if (i + 4 < end && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) return i + 4
        }
        return -1
    }

    /**
     * True if the first slice in the Annex-B buffer is an IDR. grc emits one slice per packet,
     * optionally preceded by SPS/PPS, so we stop at the first slice instead of scanning the whole frame.
     */
    fun containsIdr(data: ByteArray, offset: Int, length: Int): Boolean {
        val end = offset + length
        var i = offset
        while (i + 3 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                when (val type = data[i + 3].toInt() and 0x1F) {
                    NAL_SEI, NAL_SPS, NAL_PPS, NAL_AUD -> Unit
                    else -> return type == NAL_IDR
                }
                i += 3
            } else {
                i++
            }
        }
        return false
    }
}
