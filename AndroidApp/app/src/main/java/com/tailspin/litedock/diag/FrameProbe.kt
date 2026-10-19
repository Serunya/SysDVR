package com.tailspin.litedock.diag

import android.util.Log
import com.tailspin.litedock.video.H264

/**
 * Checks what the 60 packets/s actually are: the readme says the encoder is 30 fps, so every
 * other packet could be a tiny "nothing changed" frame. Logs the first packets of each session
 * and counts small / non-reference frames per second for the overlay.
 */
class FrameProbe {
    private var logged = 0
    private var lastTimestampUs = 0L

    @Volatile var smallFrames = 0L; private set
    @Volatile var nonRefFrames = 0L; private set

    fun reset() {
        logged = 0
        lastTimestampUs = 0
    }

    /** Called from the USB thread for every video packet. */
    fun onVideo(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        val header = H264.firstSliceHeader(data, offset, length)
        val type = if (header < 0) -1 else header and 0x1F
        val refIdc = if (header < 0) -1 else (header shr 5) and 0x3

        if (length < SMALL_FRAME_BYTES) smallFrames++
        if (refIdc == 0) nonRefFrames++

        if (logged < LOG_PACKETS) {
            logged++
            val dt = if (lastTimestampUs == 0L) 0 else timestampUs - lastTimestampUs
            Log.i(TAG, "len=$length type=$type ref_idc=$refIdc dt=${dt}us")
        }
        lastTimestampUs = timestampUs
    }

    private companion object {
        const val TAG = "Frames"
        const val LOG_PACKETS = 180
        const val SMALL_FRAME_BYTES = 1024
    }
}
