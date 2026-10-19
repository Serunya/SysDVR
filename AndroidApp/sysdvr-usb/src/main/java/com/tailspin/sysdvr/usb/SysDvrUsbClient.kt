package com.tailspin.sysdvr.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Finds a SysDVR console on USB, asks for permission, connects and keeps reconnecting
 * until [stop]. Plug and unplug are handled automatically, the caller only observes [state]
 * and consumes data in [sink].
 *
 * For permission-less auto start add a `USB_DEVICE_ATTACHED` intent filter with
 * `@xml/sysdvr_usb_device_filter` to the activity: once the user ticks "always open" the
 * system grants the permission by itself on every plug.
 */
class SysDvrUsbClient(
    context: Context,
    private val config: StreamConfig,
    private val sink: PacketSink,
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)

    private val _state = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Stopped)
    val state: StateFlow<UsbConnectionState> = _state.asStateFlow()

    val stats = ConnectionStats()

    @Volatile private var running = false
    @Volatile private var activeDeviceName: String? = null
    @Volatile private var activeDeviceDetached = false

    private val wakeup = Semaphore(0)
    private var worker: Thread? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (device != null && device.deviceName == activeDeviceName)
                    activeDeviceDetached = true
            }
            wake()
        }
    }

    /** Must be called from the main thread. */
    fun start() {
        if (running) return
        running = true

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        worker = Thread(::workerLoop, "SysDvrUsb").apply { start() }
    }

    /** Must be called from the main thread. Blocks until the reader thread exits (at most ~1.5 s). */
    fun stop() {
        if (!running) return
        running = false
        appContext.unregisterReceiver(receiver)
        wake()
        worker?.join(STOP_JOIN_TIMEOUT_MS)
        worker = null
        _state.value = UsbConnectionState.Stopped
    }

    /** Forces an immediate device rescan, e.g. from Activity.onNewIntent() on USB_DEVICE_ATTACHED. */
    fun rescan() = wake()

    private fun wake() = wakeup.release()

    private fun sleep(ms: Long) {
        wakeup.tryAcquire(ms, TimeUnit.MILLISECONDS)
        wakeup.drainPermits()
    }

    private fun setState(state: UsbConnectionState) {
        if (_state.value == state) return
        Log.i(TAG, "State: $state")
        _state.value = state
    }

    private fun workerLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)

        var permissionRequestedFor: String? = null
        var rejectedDevice: String? = null

        while (running) {
            val device = findDevice()
            if (device == null) {
                permissionRequestedFor = null
                rejectedDevice = null
                setState(UsbConnectionState.WaitingForDevice)
                sleep(POLL_INTERVAL_MS)
                continue
            }

            // Incompatible sysmodule, don't retry until it is replugged
            if (device.deviceName == rejectedDevice) {
                sleep(POLL_INTERVAL_MS)
                continue
            }

            if (!usbManager.hasPermission(device)) {
                setState(UsbConnectionState.WaitingForPermission)
                // Ask once per plug, if the user declines we wait for a replug
                if (permissionRequestedFor != device.deviceName) {
                    permissionRequestedFor = device.deviceName
                    requestPermission(device)
                }
                sleep(POLL_INTERVAL_MS)
                continue
            }

            // The serial is only readable with permission on Android 10+
            val serial = runCatching { device.serialNumber }.getOrNull()
            val info = SysDvrDeviceInfo.parse(serial)
            if (info == null || !info.isProtocolSupported) {
                rejectedDevice = device.deviceName
                setState(
                    UsbConnectionState.Error(
                        if (info == null) "Not a SysDVR device: $serial"
                        else "Unsupported sysmodule protocol ${info.protocolVersion} (SysDVR ${info.sysmoduleVersion})"
                    )
                )
                continue
            }

            runDevice(device, info)

            if (running) sleep(RETRY_DELAY_MS)
        }
    }

    private fun runDevice(device: UsbDevice, info: SysDvrDeviceInfo) {
        activeDeviceName = device.deviceName
        activeDeviceDetached = false

        val transport = try {
            AndroidUsbTransport.open(usbManager, device)
        } catch (e: IOException) {
            Log.w(TAG, "Open failed", e)
            setState(UsbConnectionState.Error("Can't open the device: ${e.message}"))
            activeDeviceName = null
            return
        }

        val isActive = { running && !activeDeviceDetached }
        val session = SysDvrSession(transport, config, sink, stats)

        try {
            while (isActive()) {
                setState(UsbConnectionState.Connecting(info))

                val version = try {
                    session.handshake(isActive) ?: break
                } catch (e: HandshakeException) {
                    Log.w(TAG, "Handshake failed", e)
                    setState(UsbConnectionState.Error(e.message ?: "Handshake failed"))
                    sleep(RETRY_DELAY_MS)
                    continue
                }

                stats.sessions++
                setState(UsbConnectionState.Streaming(info, version))
                sink.onStreamStarted(info)
                try {
                    session.stream(isActive)
                } catch (e: StallException) {
                    // Same transport, the console will send a new hello once it notices
                    Log.w(TAG, "Stream stalled, reconnecting", e)
                } finally {
                    sink.onStreamStopped()
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "USB I/O error", e)
        } catch (e: Exception) {
            // A crash in the sink must not kill the reader thread
            Log.e(TAG, "Unexpected error", e)
            setState(UsbConnectionState.Error("Unexpected error: ${e.message}"))
        } finally {
            transport.close()
            activeDeviceName = null
        }
    }

    private fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == Protocol.USB_VID && it.productId == Protocol.USB_PID
        }

    private fun requestPermission(device: UsbDevice) {
        Log.i(TAG, "Requesting permission for ${device.deviceName}")
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        // The system fills in EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED so it must be mutable
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        usbManager.requestPermission(device, pending)
    }

    private companion object {
        const val TAG = "SysDvrUsb"
        const val ACTION_USB_PERMISSION = "com.tailspin.sysdvr.usb.USB_PERMISSION"
        const val POLL_INTERVAL_MS = 2000L
        const val RETRY_DELAY_MS = 500L
        const val STOP_JOIN_TIMEOUT_MS = 3000L
    }
}
