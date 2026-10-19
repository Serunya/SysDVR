package com.tailspin.litedock.diag

import android.app.Activity
import android.util.Log
import android.view.Choreographer
import android.view.Display
import kotlin.math.abs

/**
 * The console streams 60 fps. If the box outputs HDMI at 50 Hz (or 30), frames pile up in
 * SurfaceFlinger and some are dropped, adding latency. TV boxes usually support switching
 * the output mode from the app, like Kodi does for frame rate matching.
 */
object DisplayRate {
    private const val TAG = "DisplayRate"

    @Suppress("DEPRECATION")
    private fun display(activity: Activity): Display = activity.windowManager.defaultDisplay

    fun currentMode(activity: Activity): String = display(activity).mode.describe()

    /** Requests a ~60 Hz output mode, as close as possible to the current resolution. Returns true if it asked for a switch. */
    fun prefer60Hz(activity: Activity): Boolean {
        val display = display(activity)
        val current = display.mode
        display.supportedModes.forEach { Log.i(TAG, "Supported mode: ${it.describe()}") }
        Log.i(TAG, "Current mode: ${current.describe()}")

        if (abs(current.refreshRate - 60f) < 1f) return false

        val best = display.supportedModes
            .filter { abs(it.refreshRate - 60f) < 1f }
            .minByOrNull { abs(it.physicalWidth - current.physicalWidth) + abs(it.physicalHeight - current.physicalHeight) }
        if (best == null) {
            Log.w(TAG, "No 60 Hz mode available")
            return false
        }

        Log.i(TAG, "Requesting ${best.describe()}")
        val window = activity.window
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
        return true
    }

    private fun Display.Mode.describe() = "#$modeId ${physicalWidth}x$physicalHeight@${"%.2f".format(refreshRate)}"
}

/** Counts real vsync callbacks, i.e. the refresh rate the display is actually running at. */
class VsyncMeter : Choreographer.FrameCallback {
    @Volatile var frames = 0L; private set
    private var running = false

    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frames++
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }
}
