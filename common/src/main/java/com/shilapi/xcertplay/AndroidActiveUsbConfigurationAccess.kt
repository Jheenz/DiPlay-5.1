package com.shilapi.xcertplay

import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDeviceConnection

internal fun readOnlyUsbConfiguration(connection: UsbDeviceConnection, report: (String) -> Unit): Int {
    report("GET_CONFIGURATION requestType=0x80 request=0x08 value=0 index=0 length=1 timeoutMs=1000")
    val buffer = ByteArray(1)
    val count = connection.controlTransfer(0x80, 0x08, 0, 0, buffer, 1, 1_000)
    report("GET_CONFIGURATION transferred=$count expected=1")
    check(count == 1) { "GET_CONFIGURATION failed or malformed response: transferred=$count expected=1" }
    return buffer[0].toInt() and 0xff
}

/** Only standard device GET_CONFIGURATION is exposed; the connection never escapes this scope. */
class AndroidActiveUsbConfigurationAccess(private val manager: UsbManager) : ActiveUsbConfigurationAccess {
    override fun devices(): List<PassiveUsbDevice> = AndroidPassiveUsbInventory(manager).snapshot()

    override fun readConfiguration(device: PassiveUsbDevice, report: (String) -> Unit): Int {
        val current = manager.deviceList[device.name]
            ?: throw IllegalStateException("Selected Apple device detached before open")
        check(current.vendorId == 0x05ac && current.productId == device.productId) {
            "Selected USB identity changed before open — STOP"
        }
        check(manager.hasPermission(current)) { "USB permission required — STOP (rechecked before open)" }
        val connection = try {
            manager.openDevice(current)
        } catch (error: Exception) {
            report("open result=FAIL ${error.javaClass.simpleName}: ${error.message}; cleanup=no connection acquired")
            throw error
        }
        if (connection == null) {
            report("open result=FAIL; cleanup=not needed (no connection acquired)")
            throw IllegalStateException("openDevice returned null")
        }
        try {
            report("open result=PASS; interface claim=not attempted")
            return readOnlyUsbConfiguration(connection, report)
        } finally {
            try {
                connection.close()
                report("cleanup connection close=PASS")
            } catch (error: Exception) {
                report("cleanup connection close=FAIL ${error.javaClass.simpleName}: ${error.message}")
                throw error
            }
        }
    }
}
