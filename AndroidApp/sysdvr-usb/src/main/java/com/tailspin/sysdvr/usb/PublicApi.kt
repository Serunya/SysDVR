package com.tailspin.sysdvr.usb

/** caps:sc JPEG screenshots, only supported by the LiteDock sysmodule (upstream ignores the request). */
enum class JpegMode {
    OFF,
    /** H264 from grc, JPEG while grc has no video: HOME menu, games without video capture. */
    FALLBACK,
    /** Only JPEG, never grc video. For comparing latency and quality. */
    ONLY,
}

/** Stream parameters sent to the console during the handshake. */
data class StreamConfig(
    val video: Boolean = true,
    val audio: Boolean = true,
    /** How many audio chunks (~21 ms each) the console groups per transfer, 0..5. Lower is less latency. */
    val audioBatching: Int = 2,
    /** Lets the console skip re-sending identical NALs, fixes glitching on some games. */
    val useNalReplay: Boolean = true,
    val nalReplayOnlyKeyframes: Boolean = true,
    /** Turns off the console screen while streaming. Ignored by sysmodules older than protocol 03. */
    val turnOffConsoleScreen: Boolean = false,
    val jpegMode: JpegMode = JpegMode.OFF,
    /** Upper bound for the JPEG frame rate, 1..60. caps:sc manages ~55 fps on a Lite. */
    val jpegMaxFps: Int = 30,
    /**
     * LiteDock sysmodule: record the console's final audio output instead of grc. Includes the HOME
     * menu and system sounds, is captured before the console volume (the speaker can be muted) and
     * has ~20 ms of latency. Falls back to grc if unavailable.
     */
    val systemAudio: Boolean = false,
    /** LiteDock sysmodule: mute the console speaker while connected, restored on disconnect. */
    val muteConsoleSpeaker: Boolean = false,
    /** LiteDock sysmodule: disable the handheld auto sleep while connected, restored on disconnect. */
    val keepConsoleAwake: Boolean = false,
) {
    init {
        require(video || audio) { "At least one of video or audio must be enabled" }
        require(audioBatching in 0..5) { "audioBatching must be in 0..5" }
        require(jpegMaxFps in 1..60) { "jpegMaxFps must be in 1..60" }
    }
}

/**
 * Receives stream data. All callbacks run on the USB reader thread: keep them short, never block
 * for long or the console will drop the connection (it times out after ~1.5 s).
 *
 * The [data] arrays are owned by the client and are only valid for the duration of the call.
 */
interface PacketSink {
    /** A new stream session started (after a successful handshake). Reset decoders here. */
    fun onStreamStarted(device: SysDvrDeviceInfo) {}

    /** One or more H.264 NALs in Annex-B format. [timestampUs] is the console capture time. */
    fun onVideo(data: ByteArray, offset: Int, length: Int, timestampUs: Long)

    /**
     * A JPEG screenshot of the whole screen (LiteDock sysmodule, see [JpegMode]). Arrives instead of
     * [onVideo] while it is active. [timestampUs] is the console clock at capture.
     */
    fun onJpeg(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {}

    /** PCM s16le, 48000 Hz, stereo. */
    fun onAudio(data: ByteArray, offset: Int, length: Int, timestampUs: Long)

    /** The current session ended (unplugged, stall, error). A new one may follow. */
    fun onStreamStopped() {}

    /** Capture error reported by the console, the stream usually continues. */
    fun onConsoleError(message: String) {}
}

sealed interface UsbConnectionState {
    data object Stopped : UsbConnectionState
    data object WaitingForDevice : UsbConnectionState
    data object WaitingForPermission : UsbConnectionState
    data class Connecting(val device: SysDvrDeviceInfo) : UsbConnectionState
    data class Streaming(val device: SysDvrDeviceInfo, val protocolVersion: String) : UsbConnectionState

    /** Something went wrong, the client keeps retrying on its own. */
    data class Error(val message: String) : UsbConnectionState
}

/** Live counters, written by the reader thread and safe to poll from any thread. */
class ConnectionStats internal constructor() {
    @Volatile var bytesReceived = 0L; internal set
    @Volatile var videoPackets = 0L; internal set
    @Volatile var jpegPackets = 0L; internal set
    @Volatile var audioPackets = 0L; internal set
    @Volatile var replayedPackets = 0L; internal set
    @Volatile var replayMisses = 0L; internal set
    @Volatile var resyncs = 0L; internal set
    @Volatile var consoleErrors = 0L; internal set
    @Volatile var sessions = 0L; internal set
}
