package com.tailspin.sysdvr.usb

/**
 * Splits the USB byte stream into SysDVR packets.
 *
 * The sysmodule sends one packet per bulk transfer, but we don't rely on transfer boundaries:
 * a missing zero-length packet or a host stack that merges transfers would otherwise break framing.
 * Bytes are appended with [prepareWrite]/[commit] and complete packets are emitted by [drain].
 */
internal class PacketParser(capacity: Int = Protocol.READ_CHUNK + Protocol.MAX_PACKET_SIZE) {

    fun interface Handler {
        /** [buf] at [dataOffset] holds [PacketHeader.dataSize] bytes of payload, valid only during the call. */
        fun onPacket(header: PacketHeader, buf: ByteArray, dataOffset: Int)
    }

    val buffer = ByteArray(capacity)
    private val header = PacketHeader()
    private var start = 0
    private var end = 0

    var resyncs = 0L
        private set

    val buffered get() = end - start

    /** Moves pending bytes to the front and returns the offset where new data must be written. */
    fun prepareWrite(): Int {
        if (start > 0) {
            System.arraycopy(buffer, start, buffer, 0, end - start)
            end -= start
            start = 0
        }
        return end
    }

    val writableLength get() = buffer.size - end

    fun commit(count: Int) {
        require(count >= 0 && end + count <= buffer.size)
        end += count
    }

    fun reset() {
        start = 0
        end = 0
    }

    fun drain(handler: Handler) {
        while (end - start >= Protocol.HEADER_SIZE) {
            if (!header.parse(buffer, start)) {
                resync()
                continue
            }

            val total = Protocol.HEADER_SIZE + header.dataSize
            if (end - start < total) return

            handler.onPacket(header, buffer, start + Protocol.HEADER_SIZE)
            start += total
        }
    }

    // Skip to the next CC CC CC CC sequence, keep up to 3 trailing bytes that may be a partial magic
    private fun resync() {
        resyncs++
        var i = start + 1
        val last = end - 4
        while (i <= last) {
            if (buffer[i] == MAGIC_BYTE && buffer[i + 1] == MAGIC_BYTE &&
                buffer[i + 2] == MAGIC_BYTE && buffer[i + 3] == MAGIC_BYTE
            ) {
                start = i
                return
            }
            i++
        }
        start = maxOf(start + 1, end - 3)
    }

    private companion object {
        const val MAGIC_BYTE = 0xCC.toByte()
    }
}
