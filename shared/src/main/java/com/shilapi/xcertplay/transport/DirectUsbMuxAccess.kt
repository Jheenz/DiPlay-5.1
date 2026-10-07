package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.util.concurrent.atomic.AtomicBoolean

interface DirectUsbMuxAccess {
    fun devices(): List<UsbDevice>
    fun hasPermission(device: UsbDevice): Boolean
    fun open(device: UsbDevice, selection: DirectUsbMuxSelection, report: (String) -> Unit): UsbMuxBulkPipe
}

data class DirectUsbMuxSelection(
    val index: Int,
    val usbInterface: UsbInterface,
    val out: UsbEndpoint,
    val input: UsbEndpoint,
) {
    companion object {
        fun find(device: UsbDevice): DirectUsbMuxSelection? {
            val candidates = (0 until device.interfaceCount).mapNotNull { index ->
                val intf = device.getInterface(index)
                if (!IphoneCarPlayConfiguration.isUsbMuxInterface(intf)) return@mapNotNull null
                val endpoints = IphoneCarPlayConfiguration.usbMuxEndpoints(intf) ?: return@mapNotNull null
                if (endpoints.first.maxPacketSize <= 0 || endpoints.second.maxPacketSize <= 0) {
                    return@mapNotNull null
                }
                DirectUsbMuxSelection(index, intf, endpoints.first, endpoints.second)
            }
            // Do not guess between multiple multiplexors or switch configurations/alternate settings.
            return candidates.singleOrNull()
        }
    }
}

class AndroidDirectUsbMuxAccess(private val manager: UsbManager) : DirectUsbMuxAccess {
    override fun devices(): List<UsbDevice> = manager.deviceList.values.toList()
    override fun hasPermission(device: UsbDevice): Boolean = manager.hasPermission(device)

    override fun open(
        device: UsbDevice,
        selection: DirectUsbMuxSelection,
        report: (String) -> Unit,
    ): UsbMuxBulkPipe {
        report("open result=attempting")
        val connection = try {
            manager.openDevice(device)
                ?: throw IphoneUsbException.DeviceUnavailable("openDevice returned null")
        } catch (error: Exception) {
            report("open result=FAIL; claim result=not attempted; cleanup=no device connection acquired")
            throw error
        }
        report("open result=PASS")
        var claimed = false
        try {
            // force=false: never detach an existing kernel driver to make this diagnostic succeed.
            report("claim result=attempting")
            if (!connection.claimInterface(selection.usbInterface, false)) {
                report("claim result=FAIL")
                throw IphoneUsbException.DeviceUnavailable("claimInterface returned false")
            }
            claimed = true
            report("claim result=PASS (only selected USBMUX interface; force=false)")
            val session = Iap2UsbSession(connection, selection.out, selection.input)
            val closed = AtomicBoolean()
            return object : UsbMuxBulkPipe {
                override fun write(data: ByteArray, timeoutMillis: Int) = session.write(data, timeoutMillis)
                override fun read(timeoutMillis: Long): ByteArray? = session.read(timeoutMillis)
                override fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    report("cleanup device close=scheduled")
                    try {
                        session.closeWithRelease {
                            report("cleanup releaseInterface=${if (connection.releaseInterface(selection.usbInterface)) "PASS" else "FAIL"}")
                        }
                    } catch (error: Exception) {
                        report("cleanup USB release/close=FAIL ${error.javaClass.simpleName}: ${error.message}")
                        throw error
                    }
                    report("cleanup device close=PASS")
                }
            }
        } catch (error: Exception) {
            if (!claimed) report("claim result=FAIL")
            try {
                if (claimed) report("cleanup releaseInterface=${if (connection.releaseInterface(selection.usbInterface)) "PASS" else "FAIL"}")
                else report("cleanup releaseInterface=not needed (claim unsuccessful)")
            } finally {
                connection.close()
                report("cleanup device close=PASS")
            }
            throw error
        }
    }
}
