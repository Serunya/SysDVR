package com.tailspin.sysdvr.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException

internal interface UsbTransport : Closeable {
    /** Returns the number of bytes read or [TIMEOUT]. Throws [IOException] when the device is gone. */
    fun read(buf: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** Returns the number of bytes written or [TIMEOUT]. Throws [IOException] when the device is gone. */
    fun write(buf: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    companion object {
        const val TIMEOUT = -1
    }
}

/**
 * Bulk endpoints of the SysDVR interface. bulkTransfer() returns -1 both on timeout and on errors,
 * we tell them apart by timing: an unplugged device fails immediately and repeatedly.
 */
internal class AndroidUsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : UsbTransport {

    private var fastFailures = 0

    override fun read(buf: ByteArray, offset: Int, length: Int, timeoutMs: Int) =
        transfer(epIn, buf, offset, length, timeoutMs)

    override fun write(buf: ByteArray, offset: Int, length: Int, timeoutMs: Int) =
        transfer(epOut, buf, offset, length, timeoutMs)

    private fun transfer(ep: UsbEndpoint, buf: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val begin = SystemClock.elapsedRealtime()
        val n = connection.bulkTransfer(ep, buf, offset, length, timeoutMs)
        if (n >= 0) {
            fastFailures = 0
            return n
        }

        if (SystemClock.elapsedRealtime() - begin < timeoutMs / 2) {
            if (++fastFailures >= MAX_FAST_FAILURES)
                throw IOException("USB transfer keeps failing, device disconnected?")
            SystemClock.sleep(10)
        } else {
            fastFailures = 0
        }
        return UsbTransport.TIMEOUT
    }

    override fun close() {
        connection.releaseInterface(iface)
        connection.close()
    }

    companion object {
        private const val MAX_FAST_FAILURES = 10

        fun open(manager: UsbManager, device: UsbDevice): AndroidUsbTransport {
            if (device.interfaceCount < 1) throw IOException("Device has no interfaces")

            // Since 6.0 the sysmodule exposes a single interface with one bulk IN and one bulk OUT endpoint
            val iface = device.getInterface(0)
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn == null || epOut == null) throw IOException("Bulk endpoints not found")

            val connection = manager.openDevice(device) ?: throw IOException("openDevice() failed")
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                throw IOException("claimInterface() failed")
            }
            return AndroidUsbTransport(connection, iface, epIn, epOut)
        }
    }
}
