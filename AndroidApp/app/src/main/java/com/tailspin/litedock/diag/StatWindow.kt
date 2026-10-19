package com.tailspin.litedock.diag

/** Average / max / count of values over a window, reset on every [take]. Thread safe. */
class StatWindow {
    private var sum = 0L
    private var count = 0
    private var max = Long.MIN_VALUE

    @Synchronized
    fun add(value: Long) {
        sum += value
        count++
        if (value > max) max = value
    }

    /** "avg/max" in the unit the values were added with, then resets. */
    @Synchronized
    fun take(): String {
        val s = if (count == 0) "-" else "${sum / count}/${max}"
        sum = 0
        count = 0
        max = Long.MIN_VALUE
        return s
    }
}

/**
 * Tracks how late packets arrive compared to the console clock. The absolute offset between the
 * two clocks is unknown, but its minimum is the "fastest" packet: anything above it is queueing
 * somewhere between the console encoder and our USB reader.
 */
class ArrivalProbe {
    private var minOffsetUs = Long.MAX_VALUE
    private var lastArrivalUs = 0L
    val extraDelayMs = StatWindow()
    val gapMs = StatWindow()

    /** Called from the USB thread for each packet. */
    fun onPacket(consoleTimestampUs: Long) {
        val now = System.nanoTime() / 1000
        val offset = now - consoleTimestampUs
        if (offset < minOffsetUs) minOffsetUs = offset
        extraDelayMs.add((offset - minOffsetUs) / 1000)
        if (lastArrivalUs != 0L) gapMs.add((now - lastArrivalUs) / 1000)
        lastArrivalUs = now
    }

    fun reset() {
        minOffsetUs = Long.MAX_VALUE
        lastArrivalUs = 0
    }
}
