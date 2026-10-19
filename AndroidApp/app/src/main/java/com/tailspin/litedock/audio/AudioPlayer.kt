package com.tailspin.litedock.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.tailspin.litedock.diag.StatWindow
import kotlin.math.abs

/**
 * Plays the console PCM stream (s16le, 48 kHz, stereo) with as little buffering as possible.
 *
 * There is no queue: packets are written non-blocking straight from the USB thread, and the
 * AudioTrack effective buffer size (setBufferSizeInFrames) is the latency cap. Whatever doesn't fit
 * is dropped. The buffer grows when the track underruns while data is flowing and shrinks back
 * after a quiet period, so a single hiccup doesn't cost latency forever.
 *
 * If the low latency path doesn't actually play on this device (playback head stuck), the track
 * is recreated in normal mode.
 */
class AudioPlayer {
    @Volatile private var track: AudioTrack
    private var lowLatency = true
    private var started = false
    private var targetFrames = INITIAL_BUFFER_FRAMES
    private var lastUnderruns = 0
    private var lastHead = 0
    private var headStuckSince = 0L
    private var framesWritten = 0L
    private var lastSubmitMs = 0L
    private var lastGrowMs = 0L
    private var startedAtMs = 0L
    private var minFrames = MIN_BUFFER_FRAMES

    // Diagnostics, read by the overlay
    @Volatile var receivedPackets = 0L; private set
    @Volatile var receivedBytes = 0L; private set
    @Volatile var droppedBytes = 0L; private set
    @Volatile var writeErrors = 0L; private set
    @Volatile private var peak = 0
    val arrivalGapMs = StatWindow()

    init {
        track = createTrack()
    }

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            // Capacity only, the effective size is lowered below
            .setBufferSizeInBytes(maxOf(minBuffer, MAX_BUFFER_FRAMES * FRAME_SIZE))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (lowLatency) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)

        val t = builder.build()
        // A normal (non FAST) track underruns constantly below the mixer's minimum
        // A normal (non FAST) track runs dry in bursts right at the mixer minimum, keep some margin
        minFrames = maxOf(MIN_BUFFER_FRAMES, minBuffer / FRAME_SIZE * 3 / 2)
        targetFrames = targetFrames.coerceAtLeast(minFrames)
        val actual = t.setBufferSizeInFrames(targetFrames)
        Log.i(
            TAG, "AudioTrack lowLatency=$lowLatency state=${t.state} minBuffer=$minBuffer " +
                "capacity=${t.bufferCapacityInFrames} effective=$actual " +
                "nativeRate=${AudioTrack.getNativeOutputSampleRate(AudioTrack.MODE_STREAM)}"
        )
        started = false
        framesWritten = 0
        lastUnderruns = 0
        lastHead = 0
        headStuckSince = 0
        return t
    }

    /** Called from the USB reader thread, never blocks. */
    fun submit(data: ByteArray, offset: Int, length: Int) {
        receivedPackets++
        receivedBytes += length
        updatePeak(data, offset, length)

        val now = SystemClock.elapsedRealtime()
        val gap = if (lastSubmitMs == 0L) 0L else now - lastSubmitMs
        if (lastSubmitMs != 0L) arrivalGapMs.add(gap)
        lastSubmitMs = now

        val t = track
        adaptBuffer(t, gap, now)

        val len = length - length % FRAME_SIZE
        val written = t.write(data, offset, len, AudioTrack.WRITE_NON_BLOCKING)
        if (written < 0) {
            writeErrors++
            Log.w(TAG, "AudioTrack.write failed: $written")
            return
        }
        if (written < len) droppedBytes += len - written
        framesWritten += written / FRAME_SIZE

        if (!started) {
            t.play()
            started = true
            startedAtMs = now
        }

        checkPlaying(t)
    }

    private fun adaptBuffer(t: AudioTrack, gapMs: Long, now: Long) {
        // The console paused audio (HOME menu, loading): the track ran dry on purpose. Treat the
        // restart like a new session instead of growing the buffer on the restart hiccups.
        if (gapMs > STREAM_GAP_MS) {
            startedAtMs = now
            lastUnderruns = t.underrunCount
            if (targetFrames > INITIAL_BUFFER_FRAMES) setTarget(t, INITIAL_BUFFER_FRAMES, "after ${gapMs}ms pause")
            return
        }

        val underruns = t.underrunCount
        if (underruns > lastUnderruns) {
            lastUnderruns = underruns
            // Startup is always bumpy (decoder and surface creation), it says nothing about jitter
            if (now - startedAtMs < WARMUP_MS) return
            // One hiccup makes the track underrun on several writes in a row: count it once
            if (now - lastGrowMs < UNDERRUN_SETTLE_MS) return
            if (targetFrames < MAX_BUFFER_FRAMES) {
                setTarget(t, targetFrames + BUFFER_STEP_FRAMES, "underrun after ${gapMs}ms gap")
                lastGrowMs = now
            }
        } else if (now - lastGrowMs > SHRINK_AFTER_MS && targetFrames > minFrames) {
            setTarget(t, targetFrames - BUFFER_STEP_FRAMES, "stable")
            lastGrowMs = now
        }
    }

    private fun setTarget(t: AudioTrack, frames: Int, reason: String) {
        targetFrames = frames.coerceIn(minFrames, MAX_BUFFER_FRAMES)
        val actual = t.setBufferSizeInFrames(targetFrames)
        Log.i(TAG, "Buffer -> $targetFrames frames (actual $actual): $reason")
    }

    private fun checkPlaying(t: AudioTrack) {
        if (!lowLatency) return
        val head = t.playbackHeadPosition
        val now = SystemClock.elapsedRealtime()
        if (head != lastHead) {
            lastHead = head
            headStuckSince = now
        } else if (headStuckSince == 0L) {
            headStuckSince = now
        } else if (now - headStuckSince > HEAD_STUCK_TIMEOUT_MS) {
            Log.w(TAG, "Low latency track is not playing, falling back to the normal path")
            lowLatency = false
            t.release()
            track = createTrack()
        }
    }

    private fun updatePeak(data: ByteArray, offset: Int, length: Int) {
        var p = peak
        var i = offset
        val end = offset + length - 1
        while (i < end) {
            val sample = abs(((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt())
            if (sample > p) p = sample
            i += 2
        }
        peak = p
    }

    /** Drops queued audio, for a new stream session. Called from the USB thread. */
    fun reset() {
        val t = track
        if (started) {
            t.pause()
            t.flush()
            started = false
        }
        // A new session starts from a sane size, not from whatever the last one grew to
        targetFrames = INITIAL_BUFFER_FRAMES.coerceAtLeast(minFrames)
        t.setBufferSizeInFrames(targetFrames)
        framesWritten = 0
        lastSubmitMs = 0
        headStuckSince = 0
    }

    /** One line of diagnostics, resets the peak meter. */
    fun describe(): String {
        val t = track
        val p = peak
        peak = 0
        val bufferMs = t.bufferSizeInFrames * 1000 / SAMPLE_RATE
        val capacityMs = t.bufferCapacityInFrames * 1000 / SAMPLE_RATE
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val queuedMs = (framesWritten - head).coerceAtLeast(0) * 1000 / SAMPLE_RATE
        // getLatency() = HAL latency + full buffer capacity, not the effective size we set
        val total = halLatencyMs(t)
        val hal = if (total < 0) -1 else total - capacityMs
        return "lowLatency=$lowLatency buffer=${bufferMs}ms queued=${queuedMs}ms hal=${hal}ms " +
            "arrival=${arrivalGapMs.take()} underruns=${t.underrunCount} play=${t.playState} peak=$p " +
            "dropped=${droppedBytes / 1024}KB errors=$writeErrors"
    }

    fun release() {
        track.release()
    }

    private companion object {
        const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 48000
        const val FRAME_SIZE = 4 // 16 bit * 2 channels

        // One console audio chunk is 1024 frames (~21 ms)
        const val INITIAL_BUFFER_FRAMES = 3072 // 64 ms
        const val MIN_BUFFER_FRAMES = 1536 // 32 ms
        const val BUFFER_STEP_FRAMES = 512
        const val MAX_BUFFER_FRAMES = 9600 // 200 ms
        const val STREAM_GAP_MS = 100L
        const val SHRINK_AFTER_MS = 10_000L
        const val UNDERRUN_SETTLE_MS = 500L
        const val WARMUP_MS = 2000L
        const val HEAD_STUCK_TIMEOUT_MS = 2000L

        // AudioTrack.getLatency() is hidden but widely used (ExoPlayer does the same): total output
        // latency in ms including the HAL and the track buffer
        val getLatency = runCatching { AudioTrack::class.java.getMethod("getLatency") }.getOrNull()

        fun halLatencyMs(t: AudioTrack): Int =
            runCatching { getLatency?.invoke(t) as? Int }.getOrNull() ?: -1
    }
}
