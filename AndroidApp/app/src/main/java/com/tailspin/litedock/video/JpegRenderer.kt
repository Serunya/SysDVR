package com.tailspin.litedock.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import android.util.Log
import com.tailspin.litedock.diag.StatWindow

/**
 * Decodes the caps:sc JPEG screenshots (HOME menu, games without video capture) on its own thread.
 * Only the latest frame matters: if a new one arrives while decoding, the pending one is replaced.
 * Bitmaps are recycled in a small ring so steady state decoding doesn't allocate.
 */
class JpegRenderer(private val onFrame: (Bitmap) -> Unit) {
    private val lock = Object()
    private var pending = ByteArray(INITIAL_BUFFER)
    private var pendingLength = 0
    private var hasPending = false
    private var decodeBuffer = ByteArray(INITIAL_BUFFER)
    @Volatile private var running = true

    private val bitmaps = arrayOfNulls<Bitmap>(RING_SIZE)
    private var nextBitmap = 0

    val decodeMs = StatWindow()
    @Volatile var decodedFrames = 0L; private set
    @Volatile var replacedFrames = 0L; private set
    @Volatile var failedFrames = 0L; private set

    private val thread = Thread(::decodeLoop, "JpegRenderer").apply { start() }

    /** Called from the USB thread, only copies. */
    fun submit(data: ByteArray, offset: Int, length: Int) = synchronized(lock) {
        if (pending.size < length) pending = ByteArray(length)
        System.arraycopy(data, offset, pending, 0, length)
        pendingLength = length
        if (hasPending) replacedFrames++
        hasPending = true
        lock.notify()
    }

    private fun decodeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        while (running) {
            val length: Int
            synchronized(lock) {
                while (!hasPending && running) lock.wait()
                if (!running) return
                // Swap buffers so the USB thread can fill the next frame while we decode
                val tmp = decodeBuffer
                decodeBuffer = pending
                pending = tmp
                length = pendingLength
                hasPending = false
            }
            decode(decodeBuffer, length)?.let(onFrame)
        }
    }

    private fun decode(data: ByteArray, length: Int): Bitmap? {
        val start = System.nanoTime()
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inBitmap = bitmaps[nextBitmap]
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(data, 0, length, options)
        } catch (e: IllegalArgumentException) {
            // The recycled bitmap doesn't fit this frame (size changed), decode into a new one
            options.inBitmap = null
            BitmapFactory.decodeByteArray(data, 0, length, options)
        }
        if (bitmap == null) {
            failedFrames++
            Log.w(TAG, "Failed to decode a $length bytes JPEG")
            return null
        }
        bitmaps[nextBitmap] = bitmap
        nextBitmap = (nextBitmap + 1) % RING_SIZE
        decodedFrames++
        decodeMs.add((System.nanoTime() - start) / 1_000_000)
        return bitmap
    }

    fun release() {
        running = false
        synchronized(lock) { lock.notify() }
        thread.join(500)
    }

    private companion object {
        const val TAG = "JpegRenderer"
        const val INITIAL_BUFFER = 0x40000
        // One on screen, one being decoded, one spare so the UI never draws a bitmap we're writing
        const val RING_SIZE = 3
    }
}
