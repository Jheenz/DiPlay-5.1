package com.shilapi.xcertplay

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

class AndroidActiveConfig5UsbMuxClaimAccess(private val manager: UsbManager) : ActiveConfig5UsbMuxClaimAccess {
    override fun devices() = AndroidPassiveUsbInventory(manager, includeAllConfigurations = true,
        includeFlattenedInterfaces = false).snapshot()

    override fun open(device: PassiveUsbDevice): ActiveConfig5UsbMuxClaimConnection {
        return openScoped(device) { connection, target, unchanged -> Session(connection, target, unchanged) }
    }

    internal fun <T> openScoped(
        device: PassiveUsbDevice,
        create: (UsbDeviceConnection, UsbInterface, () -> Unit) -> T,
    ): T {
        ActiveConfig5UsbMuxClaimSelection.unchanged(device, devices())
        val current = manager.deviceList[device.name]
            ?: throw UsbMuxClaimFailure(UsbMuxClaimFailureReason.DEVICE_DISAPPEARED, "Device disappeared before open")
        ActiveConfig5UsbMuxClaimSelection.unchanged(device, listOf(
            AndroidPassiveUsbInventory(manager, includeAllConfigurations = true,
                includeFlattenedInterfaces = false).describeDevice(current)))
        requireUsbMuxClaim(manager.hasPermission(current), UsbMuxClaimFailureReason.PERMISSION_UNAVAILABLE,
            "Existing permission unavailable before open")
        val expected = ActiveConfig5UsbMuxClaimSelection.interface5(device)
        val config = (0 until current.configurationCount).map(current::getConfiguration).single { it.id == 5 }
        val target = (0 until config.interfaceCount).map(config::getInterface).single {
            it.id == expected.id && it.alternateSetting == expected.alternateSetting &&
                it.interfaceClass == expected.interfaceClass && it.interfaceSubclass == expected.subclass &&
                it.interfaceProtocol == expected.protocol
        }
        val connection = try {
            manager.openDevice(current)
        } catch (error: SecurityException) {
            throw UsbMuxClaimFailure(UsbMuxClaimFailureReason.PERMISSION_UNAVAILABLE,
                "Open permission denied: ${error.message}")
        } ?: throw UsbMuxClaimFailure(UsbMuxClaimFailureReason.DEVICE_OPEN_FAILURE, "openDevice returned null")
        try {
            return create(connection, target) { ActiveConfig5UsbMuxClaimSelection.unchanged(device, devices()) }
        } catch (error: Throwable) {
            try { connection.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private class Session(
        private val connection: UsbDeviceConnection,
        private val target: UsbInterface,
        private val unchanged: () -> Unit,
    ) : ActiveConfig5UsbMuxClaimConnection {
        private var closed = false
        private var readAttempted = false
        private var active5 = false
        private var claimAttempted = false
        private var claimed = false
        private var releaseAttempted = false

        override fun configuration(report: (String) -> Unit): Int {
            check(!closed && !readAttempted) { "Only one pre-claim GET_CONFIGURATION permitted" }
            readAttempted = true
            return readOnlyUsbConfiguration(connection, report).also { active5 = it == 5 }
        }

        override fun claimUsbMux(): Boolean {
            check(!closed && active5 && !claimAttempted) { "Verified active5 and one claim only required" }
            claimAttempted = true
            unchanged()
            return connection.claimInterface(target, false).also { claimed = it }
        }

        override fun releaseUsbMux(): Boolean {
            check(!closed && claimed && !releaseAttempted) { "Only one release after claimtrue permitted" }
            releaseAttempted = true
            return connection.releaseInterface(target)
        }

        override fun close() {
            check(!closed) { "Connection already closed" }
            closed = true
            connection.close()
        }
    }
}
