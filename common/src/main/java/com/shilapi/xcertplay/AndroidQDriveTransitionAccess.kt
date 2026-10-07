package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class AndroidQDriveTransitionAccess(
    private val context: Context,
    private val manager: UsbManager,
) : QDriveTransitionAccess {
    override fun devices() = AndroidPassiveUsbInventory(manager, includeAllConfigurations = true).snapshot()

    override fun open(device: PassiveUsbDevice): QDriveTransitionConnection {
        val apples = manager.deviceList.values.filter { it.vendorId == 0x05ac }
        check(apples.size == 1) { "Apple device inventory changed before open" }
        val current = apples.single()
        check(current.deviceName == device.name && current.productId == device.productId) {
            "USB identity changed before open"
        }
        check(manager.hasPermission(current)) { "USB permission absent; not requested" }
        val connection = manager.openDevice(current) ?: error("openDevice returned null")
        return Session(connection, AndroidQDriveDescriptorAccess(manager))
    }

    override fun observe(onEvent: (QDriveUsbEvent) -> Unit): Closeable {
        return observeAndroidUsbEvents(context, onEvent)
    }

    private class Session(
        private val connection: UsbDeviceConnection,
        private val descriptors: AndroidQDriveDescriptorAccess,
    ) : QDriveTransitionConnection {
        private val sent = AtomicBoolean(false)
        private var closed = false
        private fun readable() { check(!closed && !sent.get()) { "Old connection cannot be used after vendor attempt" } }
        override fun configuration(report: (String) -> Unit): Int {
            readable()
            return readOnlyUsbConfiguration(connection, report)
        }
        override fun valeria(report: (String) -> Unit, checkActive: () -> Unit): Boolean? {
            readable()
            return descriptors.inspectConnection(connection, report, checkActive)
        }
        override fun sendAuditedRequest(): Int {
            check(!closed && sent.compareAndSet(false, true)) { "One request only; no retry" }
            return connection.controlTransfer(0x40, 0x52, 0, 2, null, 0,
                QDriveVendorTransitionDiagnostic.REQUEST_TIMEOUT_MS)
        }
        override fun close() {
            check(!closed) { "Connection already closed" }
            closed = true
            connection.close()
        }
    }
}

internal fun observeAndroidUsbEvents(context: Context, onEvent: (QDriveUsbEvent) -> Unit): Closeable {
    val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            val action = when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> "ATTACH"
                UsbManager.ACTION_USB_DEVICE_DETACHED -> "DETACH"
                else -> return
            }
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device == null) {
                onEvent(QDriveUsbEvent("ERROR", "USB $action event missing device metadata", 0, 0))
                return
            }
            onEvent(QDriveUsbEvent(action, device.deviceName, device.vendorId, device.productId))
        }
    }
    ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }, ContextCompat.RECEIVER_EXPORTED)
    return Closeable { context.unregisterReceiver(receiver) }
}
