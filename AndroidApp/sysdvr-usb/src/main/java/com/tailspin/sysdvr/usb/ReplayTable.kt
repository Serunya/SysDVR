package com.tailspin.sysdvr.usb

/**
 * Cache for NAL replay (UseNalHash): the console sends a payload once with a slot id and later
 * only a header asking to replay that slot. Slots are reused by the console, never evicted here.
 */
internal class ReplayTable {
    private val data = arrayOfNulls<ByteArray>(256)
    private val lengths = IntArray(256)

    fun store(slot: Int, src: ByteArray, offset: Int, length: Int) {
        var dst = data[slot]
        if (dst == null || dst.size < length) {
            dst = ByteArray(length)
            data[slot] = dst
        }
        System.arraycopy(src, offset, dst, 0, length)
        lengths[slot] = length
    }

    /** Returns the cached payload (valid length in [lengthOf]) or null if the slot is empty. */
    fun get(slot: Int): ByteArray? = data[slot]

    fun lengthOf(slot: Int) = lengths[slot]

    fun clear() {
        data.fill(null)
        lengths.fill(0)
    }
}
