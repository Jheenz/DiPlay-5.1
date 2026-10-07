package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import java.util.concurrent.atomic.AtomicBoolean

class AndroidQDriveConfigurationAccess(
    private val context: Context,
    private val manager: UsbManager,
) : QDriveConfigurationAccess {
    override fun devices() = AndroidPassiveUsbInventory(manager, includeAllConfigurations = true).snapshot()

    override fun observe(onEvent: (QDriveUsbEvent) -> Unit) = observeAndroidUsbEvents(context, onEvent)

    override fun open(device: PassiveUsbDevice, selectionAllowed: Boolean): QDriveConfigurationConnection {
        check(devices().filter { it.vendorId == 0x05ac } == listOf(device)) { "USB state changed before open" }
        val current = manager.deviceList[device.name] ?: error("Apple device disappeared")
        check(manager.hasPermission(current)) { "Permission unavailable; not requested" }
        val target = (0 until current.configurationCount).map(current::getConfiguration).singleOrNull { it.id == 5 }
            ?: error("Unique configuration ID 5 required")
        val connection = manager.openDevice(current) ?: error("openDevice returned null")
        return Session(connection, target, selectionAllowed, AndroidQDriveDescriptorAccess(manager))
    }

    private class Session(
        private val connection: UsbDeviceConnection,
        private val target: UsbConfiguration,
        private val selectionAllowed: Boolean,
        private val descriptors: AndroidQDriveDescriptorAccess,
    ) : QDriveConfigurationConnection {
        private val attempted = AtomicBoolean(false)
        private var closed = false

        override fun configuration(report: (String) -> Unit): Int {
            check(!closed) { "Connection closed" }
            return readOnlyUsbConfiguration(connection, report)
        }

        override fun valeria5(report: (String) -> Unit, checkActive: () -> Unit): Boolean? {
            check(!closed) { "Connection closed" }
            return descriptors.inspectConnection(connection, report, checkActive, configurationId = 5,
                expectedConfigurationCount = 5)
        }

        override fun select5(): Boolean {
            check(!closed && selectionAllowed && attempted.compareAndSet(false, true)) {
                "Configuration selection prohibited or already attempted"
            }
            check(target.id == 5) { "Target must be configuration value 5" }
            return connection.setConfiguration(target)
        }

        override fun close() {
            check(!closed) { "Connection already closed" }
            closed = true
            connection.close()
        }
    }
}
