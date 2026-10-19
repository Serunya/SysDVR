package com.tailspin.litedock

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tailspin.litedock.audio.AudioPlayer
import com.tailspin.litedock.diag.ArrivalProbe
import com.tailspin.litedock.diag.DisplayRate
import com.tailspin.litedock.diag.FrameProbe
import com.tailspin.litedock.diag.VsyncMeter
import com.tailspin.litedock.diag.StatWindow
import com.tailspin.litedock.ui.theme.LiteDockTheme
import com.tailspin.litedock.video.H264
import com.tailspin.litedock.video.JpegRenderer
import com.tailspin.litedock.video.VideoDecoder
import com.tailspin.sysdvr.usb.ConnectionStats
import com.tailspin.sysdvr.usb.JpegMode
import com.tailspin.sysdvr.usb.PacketSink
import com.tailspin.sysdvr.usb.StreamConfig
import com.tailspin.sysdvr.usb.SysDvrDeviceInfo
import com.tailspin.sysdvr.usb.SysDvrUsbClient
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    // Recreated when the JPEG mode changes, the mode is negotiated in the handshake
    private var client by mutableStateOf<SysDvrUsbClient?>(null)
    private var jpegMode by mutableStateOf(JpegMode.FALLBACK)

    // Owned by the SurfaceView lifecycle, read by the USB thread
    @Volatile private var decoder: VideoDecoder? = null

    private val audio = AudioPlayer()
    private val arrival = ArrivalProbe()
    private val sinkMs = StatWindow()
    private val vsync = VsyncMeter()
    private val frames = FrameProbe()

    // JPEG screenshots (HOME menu, games without video capture) are shown in an ImageView over the video
    private var jpegView: ImageView? = null
    @Volatile private var jpegActive = false
    @Volatile private var hideJpegOnNextVideoFrame = false
    private val jpeg = JpegRenderer { bitmap ->
        runOnUiThread {
            // A frame decoded after H264 resumed must not cover the video again
            if (!jpegActive) return@runOnUiThread
            jpegView?.setImageBitmap(bitmap)
            jpegView?.visibility = View.VISIBLE
        }
    }

    // Remote: OK toggles the overlay (it may force GPU composition over the video), UP toggles the release mode
    private var overlayVisible by mutableStateOf(true)
    private var renderWithTimestamp = true

    private val sink = object : PacketSink {
        override fun onStreamStarted(device: SysDvrDeviceInfo) {
            decoder?.reset()
            audio.reset()
            jpegActive = false
            runOnUiThread { jpegView?.visibility = View.GONE }
            arrival.reset()
            frames.reset()
        }

        override fun onVideo(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
            if (jpegActive) {
                // Back from the HOME menu: keep the last JPEG on screen until the decoder has a clean frame
                jpegActive = false
                decoder?.requestKeyframe()
                hideJpegOnNextVideoFrame = true
            }
            arrival.onPacket(timestampUs)
            frames.onVideo(data, offset, length, timestampUs)
            val start = System.nanoTime()
            decoder?.submit(data, offset, length, timestampUs)
            sinkMs.add((System.nanoTime() - start) / 1_000_000)
        }

        override fun onJpeg(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
            jpegActive = true
            hideJpegOnNextVideoFrame = false
            jpeg.submit(data, offset, length)
        }

        override fun onAudio(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
            audio.submit(data, offset, length)
        }

        override fun onConsoleError(message: String) {
            Log.w("LiteDock", message)
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            decoder = VideoDecoder(holder.surface).also {
                it.renderWithTimestamp = renderWithTimestamp
                it.frameListener = {
                    if (hideJpegOnNextVideoFrame) {
                        hideJpegOnNextVideoFrame = false
                        runOnUiThread { if (!jpegActive) jpegView?.visibility = View.GONE }
                    }
                }
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            decoder?.release()
            decoder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        client = createClient()
        DisplayRate.prefer60Hz(this)
        requestGameMode()

        setContent {
            LiteDockTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            SurfaceView(context).apply {
                                // Decode at native size and let the display hardware scale to the TV
                                holder.setFixedSize(H264.WIDTH, H264.HEIGHT)
                                holder.addCallback(surfaceCallback)
                            }
                        },
                    )
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            ImageView(context).apply {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                setBackgroundColor(android.graphics.Color.BLACK)
                                visibility = View.GONE
                                jpegView = this
                            }
                        },
                    )
                    client?.let {
                        DebugOverlay(it, audio, arrival, sinkMs, vsync, frames, jpeg, jpegMode, { DisplayRate.currentMode(this@MainActivity) }, overlayVisible) { decoder }
                    }
                }
            }
        }
    }

    private fun createClient() = SysDvrUsbClient(
        this,
        StreamConfig(
            // Dock mode: the console screen and speaker turn off and it doesn't sleep while connected
            turnOffConsoleScreen = true,
            muteConsoleSpeaker = true,
            keepConsoleAwake = true,
            audioBatching = 0,
            jpegMode = jpegMode,
            jpegMaxFps = 30,
            systemAudio = true,
        ),
        sink,
    )

    /**
     * Asks the TV to turn off its picture processing (HDMI ALLM / game content type). TVs add
     * 100-200 ms otherwise. Only available on Android 11+, a no-op on older boxes.
     */
    private fun requestGameMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i("LiteDock", "Minimal post processing needs Android 11, set Game mode on the TV input manually")
            return
        }
        window.setPreferMinimalPostProcessing(true)
        Log.i("LiteDock", "Minimal post processing supported by display: ${display?.isMinimalPostProcessingSupported}")
    }

    override fun onStart() {
        super.onStart()
        vsync.start()
        client?.start()
    }

    override fun onStop() {
        client?.stop()
        vsync.stop()
        super.onStop()
    }

    override fun onDestroy() {
        audio.release()
        jpeg.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    overlayVisible = !overlayVisible
                    Log.i("Latency", "Overlay visible: $overlayVisible")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Cycle OFF -> FALLBACK -> ONLY, the mode is negotiated in the handshake
                    jpegMode = JpegMode.entries[(jpegMode.ordinal + 1) % JpegMode.entries.size]
                    Log.i("Latency", "JPEG mode: $jpegMode")
                    client?.stop()
                    client = createClient().also { it.start() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    renderWithTimestamp = !renderWithTimestamp
                    decoder?.renderWithTimestamp = renderWithTimestamp
                    Log.i("Latency", "Render with timestamp: $renderWithTimestamp")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        client?.rescan()
    }
}

@Composable
private fun DebugOverlay(
    client: SysDvrUsbClient,
    audio: AudioPlayer,
    arrival: ArrivalProbe,
    sinkMs: StatWindow,
    vsync: VsyncMeter,
    frames: FrameProbe,
    jpeg: JpegRenderer,
    jpegMode: JpegMode,
    displayMode: () -> String,
    visible: Boolean,
    decoder: () -> VideoDecoder?,
) {
    val state by client.state.collectAsState()
    var line by remember { mutableStateOf("") }
    var audioLine by remember { mutableStateOf("") }
    var latencyLine by remember { mutableStateOf("") }
    var jpegLine by remember { mutableStateOf("") }

    LaunchedEffect(client) {
        var prev = Sample.take(client.stats, decoder())
        var prevAudioPackets = audio.receivedPackets
        var prevAudioBytes = audio.receivedBytes
        var prevVsync = vsync.frames
        var prevSmall = frames.smallFrames
        var prevNonRef = frames.nonRefFrames
        var prevJpeg = jpeg.decodedFrames
        var prevJpegPackets = client.stats.jpegPackets
        while (true) {
            delay(1000)
            val now = Sample.take(client.stats, decoder())
            line = "${(now.bytes - prev.bytes) * 8 / 1000} kbit/s · " +
                "packets ${now.video - prev.video}/s (small ${frames.smallFrames - prevSmall}, " +
                "non-ref ${frames.nonRefFrames - prevNonRef}) · " +
                "rendered ${now.rendered - prev.rendered} fps · shown ${now.shown - prev.shown} fps · " +
                "vsync ${vsync.frames - prevVsync} Hz (${displayMode()}) · " +
                "dropped ${now.dropped} · resyncs ${now.resyncs} · " +
                "replay misses ${now.misses} · ${decoder()?.decoderName.orEmpty()}"
            prev = now
            prevVsync = vsync.frames
            jpegLine = "jpeg mode=$jpegMode · received ${client.stats.jpegPackets - prevJpegPackets}/s · " +
                "decoded ${jpeg.decodedFrames - prevJpeg} fps · decode=${jpeg.decodeMs.take()} · " +
                "replaced ${jpeg.replacedFrames} · failed ${jpeg.failedFrames}"
            Log.i("Latency", jpegLine)
            prevJpeg = jpeg.decodedFrames
            prevJpegPackets = client.stats.jpegPackets
            prevSmall = frames.smallFrames
            prevNonRef = frames.nonRefFrames
            audioLine = "audio ${audio.receivedPackets - prevAudioPackets} pkt/s " +
                "${audio.receivedBytes - prevAudioBytes} B/s · ${audio.describe()}"
            latencyLine = "usb extraDelay=${arrival.extraDelayMs.take()} gap=${arrival.gapMs.take()} " +
                "submit=${sinkMs.take()} · ${decoder()?.describe().orEmpty()}"
            Log.i("Latency", line)
            Log.i("Latency", latencyLine)
            Log.i("Latency", audioLine)
            prevAudioPackets = audio.receivedPackets
            prevAudioBytes = audio.receivedBytes
        }
    }

    if (!visible) return

    Column(
        Modifier
            .padding(16.dp)
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(8.dp)
    ) {
        Text("$state", color = Color.White, fontSize = 12.sp)
        Text(line, color = Color.White, fontSize = 12.sp)
        Text(latencyLine, color = Color.White, fontSize = 12.sp)
        Text(audioLine, color = Color.White, fontSize = 12.sp)
        Text(jpegLine, color = Color.White, fontSize = 12.sp)
    }
}

private data class Sample(
    val bytes: Long, val video: Long, val resyncs: Long, val misses: Long,
    val rendered: Long, val dropped: Long, val shown: Long,
) {
    companion object {
        fun take(s: ConnectionStats, d: VideoDecoder?) = Sample(
            s.bytesReceived, s.videoPackets, s.resyncs, s.replayMisses,
            d?.renderedFrames ?: 0, d?.droppedFrames ?: 0, d?.shownFrames ?: 0,
        )
    }
}
