package com.shilapi.xcertplay

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.transport.UsbMuxVersionPacket

class AndroidUsbMuxVersionAccess(manager: UsbManager) : UsbMuxVersionAccess {
    private val scoped = AndroidActiveConfig5UsbMuxClaimAccess(manager)
    override fun devices() = scoped.devices()
    override fun open(device: PassiveUsbDevice): UsbMuxVersionConnection {
        versionInterface(device)
        return scoped.openScoped(device) { connection, target, unchanged -> Session(connection, target, unchanged) }
    }

    private class Session(
        private val connection: UsbDeviceConnection,
        private val target: UsbInterface,
        private val unchanged: () -> Unit,
    ) : UsbMuxVersionConnection {
        private var closed = false
        private var readAttempted = false
        private var active5 = false
        private var claimAttempted = false
        private var claimed = false
        private var outAttempted = false
        private var outComplete = false
        private var inAttempted = false
        private var releaseAttempted = false
        private val output = (0 until target.endpointCount).map(target::getEndpoint).single { it.address == 0x04 }
        private val input = (0 until target.endpointCount).map(target::getEndpoint).single { it.address == 0x85 }

        override fun configuration(report: (String) -> Unit): Int {
            check(!closed && !readAttempted) { "One pre-claim GET_CONFIGURATION only" }
            readAttempted = true
            return readOnlyUsbConfiguration(connection, report).also { active5 = it == 5 }
        }
        override fun claimUsbMux(): Boolean {
            check(!closed && active5 && !claimAttempted) { "Verified active5; one claim only" }
            claimAttempted = true
            unchanged()
            return connection.claimInterface(target, false).also { claimed = it }
        }
        override fun writeVersion(packet: ByteArray): Int {
            check(!closed && claimed && !releaseAttempted && !outAttempted) { "One OUT after claim only" }
            check(packet.contentEquals(UsbMuxVersionPacket.request())) { "Only audited20-byte request allowed" }
            outAttempted = true
            unchanged()
            return connection.bulkTransfer(output, packet, packet.size, UsbMuxVersionDiagnostic.TIMEOUT_MILLIS)
                .also { outComplete = it == UsbMuxVersionPacket.LENGTH }
        }
        override fun readVersion(buffer: ByteArray): Int {
            check(!closed && claimed && !releaseAttempted && outComplete && !inAttempted) { "One IN after complete OUT only" }
            check(buffer.size == UsbMuxVersionDiagnostic.READ_CAPACITY) { "Exact bounded response capacity required" }
            inAttempted = true
            unchanged()
            return connection.bulkTransfer(input, buffer, buffer.size, UsbMuxVersionDiagnostic.TIMEOUT_MILLIS)
        }
        override fun releaseUsbMux(): Boolean {
            check(!closed && claimed && !releaseAttempted) { "One release after claimtrue only" }
            releaseAttempted = true
            return connection.releaseInterface(target)
        }
        override fun close() {
            check(!closed) { "Already closed" }
            closed = true
            connection.close()
        }
    }
}
