package com.tailspin.sysdvr.usb

/**
 * SysDVR wire protocol, see protocol.md and sysmodule/source/modes/proto.h, capture.h.
 * Everything is little endian and packed.
 */
internal object Protocol {
    const val USB_VID = 0x18D1
    const val USB_PID = 0x4EE0

    const val HELLO_PREFIX = "SysDVR|"
    const val HELLO_SIZE = 10

    const val REQUEST_MAGIC = 0xAAAAAAAA.toInt()
    const val REQUEST_SIZE = 16

    const val HANDSHAKE_OK = 6
    const val RESPONSE_SIZE_V02 = 4
    const val RESPONSE_SIZE_V03 = 72

    val SUPPORTED_VERSIONS = setOf("02", "03")

    const val PACKET_MAGIC = 0xCCCCCCCC.toInt()
    const val HEADER_SIZE = 18

    // JPEG packets of the LiteDock sysmodule (CAPSSC_JPEG_BUFFER_SIZE), upstream video is at most
    // VbufSz = 0x54000 and audio AbufSz * (1 + MaxABatching)
    const val MAX_DATA_SIZE = 0x80000
    const val MAX_PACKET_SIZE = MAX_DATA_SIZE + HEADER_SIZE

    // One read must be a multiple of the bulk max packet size (512 on USB2, 1024 on USB3),
    // otherwise the host can overflow when the device sends a full-sized transfer.
    val READ_CHUNK = (MAX_PACKET_SIZE + 1023) and 1023.inv()

    // ProtoMetaFlags
    const val META_VIDEO = 1 shl 0
    const val META_AUDIO = 1 shl 1

    // ProtoMetaVideoFlags
    const val VIDEO_USE_NAL_HASH = 1 shl 0
    const val VIDEO_INJECT_PPS_SPS = 1 shl 1
    const val VIDEO_NAL_HASH_ONLY_IDR = 1 shl 2

    // ExtraFeatureFlags, protocol 03+
    const val FEATURE_TURN_OFF_SCREEN = 1 shl 0

    // LiteDock sysmodule extension, ignored by upstream sysmodules
    const val FEATURE_JPEG_FALLBACK = 1 shl 2
    const val FEATURE_JPEG_ONLY = 1 shl 3
    const val FEATURE_SYSTEM_AUDIO = 1 shl 4
    const val FEATURE_MUTE_SPEAKER = 1 shl 5
    const val FEATURE_KEEP_AWAKE = 1 shl 6

    // PacketMeta
    const val PKT_TYPE_VIDEO = 1 shl 0
    const val PKT_TYPE_AUDIO = 1 shl 1
    const val PKT_TYPE_MASK = PKT_TYPE_VIDEO or PKT_TYPE_AUDIO
    const val PKT_CONTENT_DATA = 1 shl 2
    const val PKT_CONTENT_REPLAY = 1 shl 3
    const val PKT_CONTENT_MULTI_NAL = 1 shl 4
    const val PKT_CONTENT_ERROR = 1 shl 5
    // LiteDock extension: caps:sc JPEG screenshot instead of H264
    const val PKT_CONTENT_JPEG = 1 shl 6
    const val PKT_RESERVED_MASK = 1 shl 7

    const val NO_REPLAY_SLOT = 0xFF

    /** Returns the two-char protocol version from the hello packet or null if it's not a hello. */
    fun parseHello(buf: ByteArray, length: Int): String? {
        if (length != HELLO_SIZE) return null
        for (i in HELLO_PREFIX.indices)
            if (buf[i] != HELLO_PREFIX[i].code.toByte()) return null
        if (buf[HELLO_SIZE - 1] != 0.toByte()) return null

        val hi = buf[HELLO_PREFIX.length].toInt()
        val lo = buf[HELLO_PREFIX.length + 1].toInt()
        if (hi !in 0x20..0x7E || lo !in 0x20..0x7E) return null
        return "${hi.toChar()}${lo.toChar()}"
    }

    fun encodeRequest(version: String, config: StreamConfig): ByteArray {
        require(version.length == 2)
        val out = ByteArray(REQUEST_SIZE)
        out.putIntLE(0, REQUEST_MAGIC)
        // The sysmodule memcmp()s the version, so the ascii chars go in memory order
        out[4] = version[0].code.toByte()
        out[5] = version[1].code.toByte()

        var meta = 0
        if (config.video) meta = meta or META_VIDEO
        if (config.audio) meta = meta or META_AUDIO
        out[6] = meta.toByte()

        var video = VIDEO_INJECT_PPS_SPS
        if (config.useNalReplay) video = video or VIDEO_USE_NAL_HASH
        if (config.nalReplayOnlyKeyframes) video = video or VIDEO_NAL_HASH_ONLY_IDR
        out[7] = video.toByte()

        out[8] = config.audioBatching.toByte()

        var features = 0
        if (version >= "03") {
            if (config.turnOffConsoleScreen) features = features or FEATURE_TURN_OFF_SCREEN
            features = features or when (config.jpegMode) {
                JpegMode.OFF -> 0
                JpegMode.FALLBACK -> FEATURE_JPEG_FALLBACK
                JpegMode.ONLY -> FEATURE_JPEG_ONLY
            }
            if (config.audio && config.systemAudio) features = features or FEATURE_SYSTEM_AUDIO
            if (config.muteConsoleSpeaker) features = features or FEATURE_MUTE_SPEAKER
            if (config.keepConsoleAwake) features = features or FEATURE_KEEP_AWAKE
        }
        out[9] = features.toByte()
        // Reserved[0]: max JPEG fps for the LiteDock sysmodule
        out[10] = if (config.jpegMode == JpegMode.OFF) 0 else config.jpegMaxFps.toByte()
        return out
    }

    fun responseSize(version: String) =
        if (version >= "03") RESPONSE_SIZE_V03 else RESPONSE_SIZE_V02

    fun describeHandshakeCode(code: Int) = when (code) {
        0 -> "unknown failure"
        1 -> "wrong protocol version, update the sysmodule or the app"
        2 -> "invalid argument"
        3 -> "invalid size"
        4 -> "invalid meta"
        5 -> "wrong magic"
        7 -> "invalid channel"
        else -> "code $code"
    }

    private fun resultText(code: Int) =
        if (code == 0) "ok" else "failed 0x${code.toUInt().toString(16)} (${2000 + (code and 0x1FF)}-${"%04d".format((code ushr 9) and 0x1FFF)})"

    /** Decodes the body of a PKT_CONTENT_ERROR packet (capture errors reported by the console). */
    fun describeConsoleError(buf: ByteArray, offset: Int, length: Int): String {
        if (length < 32) return "Malformed console error packet ($length bytes)"
        val type = buf.intLE(offset)
        val code = buf.intLE(offset + 4)
        val ctx1 = buf.longLE(offset + 8)
        val ctx2 = buf.longLE(offset + 16)
        val ctx3 = buf.longLE(offset + 24)
        return when (type) {
            1 -> "Video capture failed with code 0x${code.toUInt().toString(16)} requested size was 0x${ctx1.toString(16)}"
            2, 3 -> "Audio capture failed with code 0x${code.toUInt().toString(16)} requested size was 0x${ctx1.toString(16)}, iteration $ctx2"
            4 -> "grc:d begin from video thread failed with code 0x${code.toUInt().toString(16)}"
            5 -> "grc:d begin from audio thread failed with code 0x${code.toUInt().toString(16)}"
            // LiteDock sysmodule diagnostics
            0x10 -> "JPEG capture failed with code 0x${code.toUInt().toString(16)} " +
                "(${2000 + (code and 0x1FF)}-${"%04d".format((code ushr 9) and 0x1FFF)}) size $ctx1"
            0x12 -> "System audio (audrec) failed with code 0x${code.toUInt().toString(16)} " +
                "(${2000 + (code and 0x1FF)}-${"%04d".format((code ushr 9) and 0x1FFF)})" +
                if (ctx1 == 0L) ", using grc audio" else " while recording"
            0x13 -> "Audio status: application peak $ctx2, HOME menu peak $ctx3, HOME recorder aruid 0x${ctx1.toString(16)} " +
                if (code == 0) "open" else "failed 0x${code.toUInt().toString(16)} (${2000 + (code and 0x1FF)}-${"%04d".format((code ushr 9) and 0x1FFF)})"
            0x14 -> "Dock mode: speaker mute ${resultText(code)}, keep awake ${resultText(ctx1.toInt())}, feature flags 0x${ctx2.toString(16)}"
            0x11 -> "JPEG status: grc idle ${ctx1} ms, flags ${ctx2 and 0xFF}, " +
                "video thread in grc ${(ctx2 shr 8) and 1}, jpeg active ${(ctx2 shr 9) and 1}, frames sent $ctx3"
            else -> "Unknown console error type $type code 0x${code.toUInt().toString(16)} $ctx1 $ctx2 $ctx3"
        }
    }
}

/** Reusable, allocation free view of a PacketHeader. */
internal class PacketHeader {
    var dataSize = 0
        private set
    var timestampUs = 0L
        private set
    var meta = 0
        private set
    var replaySlot = 0
        private set

    val isVideo get() = meta and Protocol.PKT_TYPE_VIDEO != 0
    val isAudio get() = meta and Protocol.PKT_TYPE_AUDIO != 0
    val isReplay get() = meta and Protocol.PKT_CONTENT_REPLAY != 0
    val isError get() = meta and Protocol.PKT_CONTENT_ERROR != 0
    val isJpeg get() = meta and Protocol.PKT_CONTENT_JPEG != 0

    /** Parses and validates a header, false means the bytes are not a valid header. */
    fun parse(buf: ByteArray, offset: Int): Boolean {
        if (buf.intLE(offset) != Protocol.PACKET_MAGIC) return false

        val size = buf.intLE(offset + 4)
        if (size < 0 || size > Protocol.MAX_DATA_SIZE) return false

        val m = buf[offset + 16].toInt() and 0xFF
        if (m and Protocol.PKT_RESERVED_MASK != 0) return false

        val type = m and Protocol.PKT_TYPE_MASK
        if (type != Protocol.PKT_TYPE_VIDEO && type != Protocol.PKT_TYPE_AUDIO) return false

        val content = m and (Protocol.PKT_CONTENT_DATA or Protocol.PKT_CONTENT_REPLAY or Protocol.PKT_CONTENT_ERROR)
        when (content) {
            Protocol.PKT_CONTENT_DATA, Protocol.PKT_CONTENT_ERROR -> Unit
            Protocol.PKT_CONTENT_REPLAY -> if (type != Protocol.PKT_TYPE_VIDEO || size != 0) return false
            else -> return false
        }
        if (m and Protocol.PKT_CONTENT_JPEG != 0 &&
            (type != Protocol.PKT_TYPE_VIDEO || content != Protocol.PKT_CONTENT_DATA)
        ) return false

        dataSize = size
        timestampUs = buf.longLE(offset + 8)
        meta = m
        replaySlot = buf[offset + 17].toInt() and 0xFF
        return true
    }
}

/** Parsed USB serial string, e.g. `SysDVR|6.3|03|XAW1000000000`. */
data class SysDvrDeviceInfo(
    val sysmoduleVersion: String,
    val protocolVersion: String,
    val serial: String,
) {
    val isProtocolSupported get() = protocolVersion in Protocol.SUPPORTED_VERSIONS

    companion object {
        fun parse(descriptor: String?): SysDvrDeviceInfo? {
            val parts = descriptor?.trimEnd('\u0000')?.split('|', limit = 4) ?: return null
            if (parts.size != 4 || parts[0] != "SysDVR" || parts[2].length != 2) return null
            return SysDvrDeviceInfo(parts[1], parts[2], parts[3].trim())
        }
    }
}

internal fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF

internal fun ByteArray.intLE(i: Int) =
    u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)

internal fun ByteArray.longLE(i: Int) =
    (intLE(i).toLong() and 0xFFFFFFFFL) or (intLE(i + 4).toLong() shl 32)

internal fun ByteArray.putIntLE(i: Int, v: Int) {
    this[i] = v.toByte()
    this[i + 1] = (v ushr 8).toByte()
    this[i + 2] = (v ushr 16).toByte()
    this[i + 3] = (v ushr 24).toByte()
}
