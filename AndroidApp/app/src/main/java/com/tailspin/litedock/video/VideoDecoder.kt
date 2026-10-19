package com.tailspin.litedock.video

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.tailspin.litedock.diag.StatWindow
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hardware H.264 decoder rendering straight into a Surface, tuned for latency:
 * frames are queued as soon as they arrive and rendered as soon as they are decoded.
 *
 * [submit] and [reset] are called from the USB reader thread, [release] from the main thread.
 */
class VideoDecoder(private val surface: Surface) {

    private val lock = Any()
    private val callbackThread = HandlerThread("VideoDecoder").apply { start() }
    private val freeInputs = ArrayBlockingQueue<Int>(64)

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var failed = false
    @Volatile private var lastOutputNs = 0L
    private var waitingForKeyframe = true

    @Volatile var decoderName = ""; private set
    @Volatile var renderedFrames = 0L; private set
    @Volatile var droppedFrames = 0L; private set

    /** Frames that actually reached the screen (OnFrameRendered), can be lower than [renderedFrames]. */
    @Volatile var shownFrames = 0L; private set

    /** Called on the codec thread after each frame is sent to the screen. */
    @Volatile var frameListener: (() -> Unit)? = null

    /** A/B switch: timestamped release (SurfaceFlinger may drop stale frames) vs plain render=true. */
    @Volatile var renderWithTimestamp = true

    // Latency diagnostics, all in ms
    val inputWaitMs = StatWindow()   // waiting for a free decoder input buffer
    val decodeMs = StatWindow()      // queueInputBuffer -> output buffer available
    val displayMs = StatWindow()     // queueInputBuffer -> frame actually rendered (OnFrameRendered)
    @Volatile private var queuedFrames = 0L
    @Volatile private var outputFrames = 0L
    val framesInDecoder get() = queuedFrames - outputFrames

    // pts -> System.nanoTime() when the frame was queued
    private val ringPts = LongArray(RING_SIZE)
    private val ringTime = LongArray(RING_SIZE)
    private var ringPos = 0

    private fun recordQueued(pts: Long, now: Long) = synchronized(ringPts) {
        ringPts[ringPos] = pts
        ringTime[ringPos] = now
        ringPos = (ringPos + 1) % RING_SIZE
    }

    private fun queuedAt(pts: Long): Long = synchronized(ringPts) {
        for (i in 0 until RING_SIZE) if (ringPts[i] == pts) return ringTime[i]
        return 0
    }

    init {
        synchronized(lock) { codec = createCodec() }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (c === codec) freeInputs.offer(index)
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (c !== codec) return
            outputFrames++
            lastOutputNs = System.nanoTime()
            val queued = queuedAt(info.presentationTimeUs)
            if (queued != 0L) decodeMs.add((System.nanoTime() - queued) / 1_000_000)
            try {
                // A timestamp of "now" lets SurfaceFlinger drop stale frames instead of queueing them
                if (renderWithTimestamp) c.releaseOutputBuffer(index, System.nanoTime())
                else c.releaseOutputBuffer(index, true)
                renderedFrames++
                frameListener?.invoke()
            } catch (e: IllegalStateException) {
                // Codec is being released
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Decoder error", e)
            if (c === codec) failed = true
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Output format: $format")
        }
    }

    private fun createCodec(): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, H264.WIDTH, H264.HEIGHT).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(if (USE_LOW_LATENCY_SPS) H264.SPS_LOW_LATENCY else H264.SPS))
            setByteBuffer("csd-1", ByteBuffer.wrap(H264.PPS))
            // The default for 720p is too small for the biggest IDR frames
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, H264.MAX_FRAME_SIZE)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

        freeInputs.clear()
        queuedFrames = 0
        outputFrames = 0
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val handler = Handler(callbackThread.looper)
        c.setCallback(callback, handler)
        c.setOnFrameRenderedListener({ _, pts, renderNs ->
            shownFrames++
            val queued = queuedAt(pts)
            if (queued != 0L) displayMs.add((renderNs - queued) / 1_000_000)
        }, handler)
        c.configure(format, surface, null, 0)
        decoderName = c.name
        Log.i(TAG, "Using decoder $decoderName")
        c.start()

        failed = false
        lastOutputNs = System.nanoTime()
        waitingForKeyframe = true
        return c
    }

    private fun destroyCodec() {
        val c = codec ?: return
        codec = null
        try {
            c.stop()
        } catch (e: IllegalStateException) {
            // Already in error state
        }
        c.release()
        freeInputs.clear()
    }

    /**
     * Feeds one Annex-B packet. Never blocks the USB thread for more than [INPUT_TIMEOUT_MS]:
     * stalling it makes the console halve the frame rate and then drop the connection.
     */
    fun submit(data: ByteArray, packetOffset: Int, packetLength: Int, timestampUs: Long): Unit = synchronized(lock) {
        var offset = packetOffset
        var length = packetLength
        if (USE_LOW_LATENCY_SPS) {
            // In-band SPS would replace our patched one, the csd already has the same parameters
            val skip = H264.leadingParameterSetsLength(data, offset, length)
            offset += skip
            length -= skip
            if (length <= 0) return
        }

        // Watchdog: the decoder can get stuck (seen when the surface isn't on screen yet at startup)
        if (!failed && framesInDecoder > 0 && System.nanoTime() - lastOutputNs > STALL_TIMEOUT_NS) {
            Log.w(TAG, "Decoder produced no output for ${STALL_TIMEOUT_NS / 1_000_000} ms with $framesInDecoder frames queued")
            failed = true
        }

        if (failed) {
            Log.w(TAG, "Recreating decoder after an error")
            destroyCodec()
            codec = createCodec()
        }
        val c = codec ?: return

        // Decoding from a P-frame without its references only produces garbage
        if (waitingForKeyframe) {
            if (!H264.containsIdr(data, offset, length)) return
            waitingForKeyframe = false
        }

        val waitStart = System.nanoTime()
        val index = freeInputs.poll(INPUT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        inputWaitMs.add((System.nanoTime() - waitStart) / 1_000_000)
        if (index == null) {
            // Following P-frames would reference the missing one, resume at the next IDR (every ~250 ms)
            droppedFrames++
            waitingForKeyframe = true
            return
        }

        try {
            val buf = c.getInputBuffer(index) ?: return
            if (length > buf.capacity()) {
                droppedFrames++
                c.queueInputBuffer(index, 0, 0, timestampUs, 0)
                return
            }
            buf.clear()
            buf.put(data, offset, length)
            recordQueued(timestampUs, System.nanoTime())
            c.queueInputBuffer(index, 0, length, timestampUs, 0)
            queuedFrames++
        } catch (e: IllegalStateException) {
            Log.w(TAG, "queueInputBuffer failed", e)
            failed = true
        }
    }

    /** Drop packets until the next IDR, e.g. when H264 resumes after JPEG frames. */
    fun requestKeyframe() = synchronized(lock) { waitingForKeyframe = true }

    /** Drops all decoder state, for a new stream session. */
    fun reset() = synchronized(lock) {
        if (codec == null) return
        destroyCodec()
        codec = createCodec()
    }

    fun release() = synchronized(lock) {
        destroyCodec()
        callbackThread.quitSafely()
    }

    /** One line of latency diagnostics, resets the windows. Format of each value: avg/max ms. */
    fun describe() =
        "inputWait=${inputWaitMs.take()} decode=${decodeMs.take()} display=${displayMs.take()} " +
            "release=${if (renderWithTimestamp) "timestamp" else "immediate"} " +
            "inDecoder=$framesInDecoder"

    private companion object {
        const val TAG = "VideoDecoder"
        const val INPUT_TIMEOUT_MS = 4L
        const val STALL_TIMEOUT_NS = 1_000_000_000L
        const val RING_SIZE = 64

        // Flip to false to compare with the stock SPS
        const val USE_LOW_LATENCY_SPS = true

        init {
            // Lists alternatives worth trying if the default decoder buffers too much.
            // Off the main thread: enumerating codecs takes hundreds of ms on this box.
            Thread {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                    .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                    .forEach { Log.i(TAG, "Available AVC decoder: ${it.name}") }
            }.start()
        }
    }
}
