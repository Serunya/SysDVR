package com.tailspin.sysdvr.usb

import java.io.ByteArrayOutputStream

internal object TestPackets {
    fun packet(meta: Int, payload: ByteArray, slot: Int = Protocol.NO_REPLAY_SLOT, timestamp: Long = 0): ByteArray {
        val out = ByteArray(Protocol.HEADER_SIZE + payload.size)
        out.putIntLE(0, Protocol.PACKET_MAGIC)
        out.putIntLE(4, payload.size)
        out.putIntLE(8, timestamp.toInt())
        out.putIntLE(12, (timestamp ushr 32).toInt())
        out[16] = meta.toByte()
        out[17] = slot.toByte()
        payload.copyInto(out, Protocol.HEADER_SIZE)
        return out
    }

    fun video(payload: ByteArray, slot: Int = Protocol.NO_REPLAY_SLOT, timestamp: Long = 0) =
        packet(Protocol.PKT_TYPE_VIDEO or Protocol.PKT_CONTENT_DATA, payload, slot, timestamp)

    fun audio(payload: ByteArray, timestamp: Long = 0) =
        packet(Protocol.PKT_TYPE_AUDIO or Protocol.PKT_CONTENT_DATA, payload, timestamp = timestamp)

    fun jpeg(payload: ByteArray, timestamp: Long = 0) =
        packet(Protocol.PKT_TYPE_VIDEO or Protocol.PKT_CONTENT_DATA or Protocol.PKT_CONTENT_JPEG, payload, timestamp = timestamp)

    fun replay(slot: Int) =
        packet(Protocol.PKT_TYPE_VIDEO or Protocol.PKT_CONTENT_REPLAY, ByteArray(0), slot)

    fun hello(version: String) = "SysDVR|$version".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}

internal class RecordingSink : PacketSink {
    val video = mutableListOf<Pair<List<Byte>, Long>>()
    val audio = mutableListOf<List<Byte>>()
    val errors = mutableListOf<String>()
    val jpegs = mutableListOf<List<Byte>>()

    override fun onJpeg(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        jpegs += data.copyOfRange(offset, offset + length).toList()
    }

    override fun onVideo(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        video += data.copyOfRange(offset, offset + length).toList() to timestampUs
    }

    override fun onAudio(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        audio += data.copyOfRange(offset, offset + length).toList()
    }

    override fun onConsoleError(message: String) {
        errors += message
    }
}

/** Plays back a scripted list of IN transfers, records OUT transfers. */
internal class FakeTransport(vararg reads: ByteArray?) : UsbTransport {
    private val reads = ArrayDeque(reads.toList())
    val writes = mutableListOf<ByteArray>()
    val exhausted get() = reads.isEmpty()

    override fun read(buf: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val next = reads.removeFirstOrNull() ?: return UsbTransport.TIMEOUT
        // null simulates a timeout
        if (next == null) return UsbTransport.TIMEOUT
        check(next.size <= length) { "transfer larger than the read buffer" }
        next.copyInto(buf, offset)
        return next.size
    }

    override fun write(buf: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        writes += buf.copyOfRange(offset, offset + length)
        return length
    }

    override fun close() {}
}
