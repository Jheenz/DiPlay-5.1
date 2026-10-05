package com.shilapi.xcertplay

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import com.shilapi.xcertplay.transport.BluetoothCompatibility
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Phase3BDeviceDiagnostics
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class Phase3BBluetoothTest {
    @Test fun hardwareModeConnectsAndStopsAtFirstControlWithoutIdentificationOrAuthentication() {
        val app = RuntimeEnvironment.getApplication()
        val adapter = BluetoothCompatibility.adapter(app)!!
        shadowOf(adapter).setEnabled(true)
        val device = mock(BluetoothDevice::class.java)
        val socket = mock(BluetoothSocket::class.java)
        val output = ByteArrayOutputStream()
        val marker = byteArrayOf(0xff.toByte(), 0x55, 0x02, 0x00, 0xee.toByte(), 0x10)
        val synchronization = com.shilapi.xcertplay.transport.Iap2LinkEngine.SynchronizationPayload(
            4, 4096, 2000, 500, 4, 3,
            listOf(com.shilapi.xcertplay.transport.Iap2LinkEngine.SessionDescriptor(10, 0, 2)),
        ).encode()
        fun packet(control: Int, sequence: Int, session: Int, payload: ByteArray): ByteArray {
            val length = payload.size + 10
            val header = byteArrayOf(0xff.toByte(), 0x5a, (length ushr 8).toByte(), length.toByte(),
                control.toByte(), sequence.toByte(), 99, session.toByte(), 0)
            fun checksum(bytes: ByteArray) = (-bytes.sumOf { it.toInt() and 0xff }).toByte()
            header[8] = checksum(header.copyOf(8))
            return header + payload + checksum(payload)
        }
        val control = com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer.encodeFrame(0x1d00, byteArrayOf())
        `when`(socket.inputStream).thenReturn(ByteArrayInputStream(
            marker + packet(0xc0, 1, 0, synchronization) + packet(0x40, 2, 10, control)))
        `when`(socket.outputStream).thenReturn(output)
        `when`(socket.isConnected).thenReturn(true)
        `when`(device.address).thenReturn("AA:BB:CC:DD:EE:03")
        `when`(device.name).thenReturn("Test iPhone")
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(device.createRfcommSocketToServiceRecord(BluetoothCompatibility.IAP2_SERVICE_UUID)).thenReturn(socket)
        shadowOf(adapter).setBondedDevices(setOf(device))
        val sampled = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val diagnostics = Phase3BDeviceDiagnostics(app) {
            if (it.contains("Bonded devices: 1")) sampled.countDown()
            if (it.contains("firstControl=0x1d00") && it.contains("test running=false")) completed.countDown()
        }
        try {
            assertTrue(sampled.await(5, TimeUnit.SECONDS))
            diagnostics.select(device.address)
            diagnostics.testRfcomm()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            val report = diagnostics.diagnosticReport()
            assertTrue(report.contains("RFCOMM result: PASS"))
            assertTrue(report.contains("linkReady=true"))
            assertTrue(report.contains("no IdentificationInformation"))
            assertTrue(report.contains("Socket connected: no"))
            assertFalse(report.contains("Bytes sent=0;"))
            assertFalse(report.contains("received=0 "))
            assertArrayEquals(marker, output.toByteArray().copyOf(6))
            val wire = output.toByteArray()
            var offset = 6
            while (offset < wire.size) {
                assertEquals("No outbound control-session frame is permitted", 0, wire[offset + 7].toInt())
                offset += ((wire[offset + 2].toInt() and 0xff) shl 8) or (wire[offset + 3].toInt() and 0xff)
            }
            assertEquals(wire.size, offset)
        } finally { diagnostics.close() }
    }

    @Test
    @Config(sdk = [31])
    fun modernMissingConnectPermissionIsVisibleWithoutRequestingLegacyPermissions() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN)
        val sampled = CountDownLatch(1)
        val diagnostics = Phase3BDeviceDiagnostics(app) {
            if (it.contains("permission required=")) sampled.countDown()
        }
        try {
            assertTrue(sampled.await(5, TimeUnit.SECONDS))
            assertTrue(diagnostics.diagnosticReport().contains("android.permission.BLUETOOTH_CONNECT"))
            assertEquals(listOf(android.Manifest.permission.BLUETOOTH_CONNECT),
                BluetoothCompatibility.missingPermissions(app, scan = false))
            assertEquals(2, BluetoothCompatibility.missingPermissions(app, scan = true).size)
        } finally { diagnostics.close() }
    }

    @Test fun hardwareModeConnectFailureReportsChosenUuidAndNeverSendsFraming() {
        val app = RuntimeEnvironment.getApplication()
        val adapter = BluetoothCompatibility.adapter(app)!!
        shadowOf(adapter).setEnabled(true)
        val device = mock(BluetoothDevice::class.java)
        val socket = mock(BluetoothSocket::class.java)
        `when`(device.address).thenReturn("AA:BB:CC:DD:EE:01")
        `when`(device.name).thenReturn("Test iPhone")
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(device.createRfcommSocketToServiceRecord(BluetoothCompatibility.IAP2_SERVICE_UUID)).thenReturn(socket)
        doThrow(java.io.IOException("SDP service unavailable")).`when`(socket).connect()
        shadowOf(adapter).setBondedDevices(setOf(device))
        val sampled = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val diagnostics = Phase3BDeviceDiagnostics(app) {
            if (it.contains("Bonded devices: 1")) sampled.countDown()
            if (it.contains("SDP service unavailable") && it.contains("test running=false")) completed.countDown()
        }
        try {
            assertTrue(sampled.await(5, TimeUnit.SECONDS))
            diagnostics.select(device.address)
            diagnostics.testRfcomm()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            val report = diagnostics.diagnosticReport()
            assertTrue(report.contains("RFCOMM result: FAIL"))
            assertTrue(report.contains("SDP service unavailable"))
            assertTrue(report.contains("Bytes sent=0; received=0"))
            assertTrue(report.contains("iAP2: not started"))
            verify(device).createRfcommSocketToServiceRecord(BluetoothCompatibility.IAP2_SERVICE_UUID)
            verify(socket).connect()
            verify(socket).close()
            verify(socket, never()).outputStream
        } finally { diagnostics.close() }
    }

    @Test fun scanEventsPopulateDevicesAndCleanupDoesNotStartRfcomm() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val adapter = BluetoothCompatibility.adapter(app)!!
        shadowOf(adapter).setEnabled(true)
        val diagnostics = Phase3BDeviceDiagnostics(app) {}
        try {
            diagnostics.scan()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!diagnostics.diagnosticReport().contains("Manual scan: requested") && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(diagnostics.diagnosticReport(), diagnostics.diagnosticReport().contains("Manual scan: requested"))
            val device = adapter.getRemoteDevice("AA:BB:CC:DD:EE:02")
            app.sendBroadcast(android.content.Intent(BluetoothDevice.ACTION_FOUND).putExtra(BluetoothDevice.EXTRA_DEVICE, device))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            while (!diagnostics.diagnosticReport().contains("Discovered devices: 1") && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(diagnostics.diagnosticReport().contains("AA:BB:CC:DD:EE:02"))
            assertTrue(diagnostics.diagnosticReport().contains("RFCOMM result: not started"))
        } finally { diagnostics.close() }
        assertTrue(diagnostics.diagnosticReport().contains("Manual scan: stopped"))
    }

    @Test fun adapterBondedSnapshotAndExplicitSelectionDoNotConnect() {
        val app = RuntimeEnvironment.getApplication()
        val adapter = BluetoothCompatibility.adapter(app)!!
        shadowOf(adapter).setEnabled(true)
        val device = mock(BluetoothDevice::class.java)
        `when`(device.address).thenReturn("AA:BB:CC:DD:EE:FF")
        `when`(device.name).thenReturn("Test iPhone")
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(device.uuids).thenReturn(arrayOf(ParcelUuid(BluetoothCompatibility.IAP2_SERVICE_UUID)))
        shadowOf(adapter).setBondedDevices(setOf(device))
        val sampled = CountDownLatch(1)
        val diagnostics = Phase3BDeviceDiagnostics(app) {
            if (it.contains("Bonded devices: 1")) sampled.countDown()
        }
        try {
            assertTrue(sampled.await(5, TimeUnit.SECONDS))
            diagnostics.select(device.address)
            val report = diagnostics.diagnosticReport()
            assertTrue(report.contains("Adapter enabled: yes"))
            assertTrue(report.contains("Test iPhone"))
            assertTrue(report.contains("AA:BB:CC:DD:EE:FF"))
            assertTrue(report.contains(BluetoothCompatibility.IAP2_SERVICE_UUID.toString()))
            assertTrue(report.contains("RFCOMM result: not started"))
            assertTrue(report.contains("Socket connected: no"))
            assertTrue(report.contains("Bytes sent=0; received=0"))
            verify(device, never()).createRfcommSocketToServiceRecord(any())
        } finally { diagnostics.close() }
    }

    @Test fun rfcommStreamReadsWritesAndClosesOwnedSocketOnce() {
        val socket = mock(BluetoothSocket::class.java)
        val output = ByteArrayOutputStream()
        `when`(socket.inputStream).thenReturn(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
        `when`(socket.outputStream).thenReturn(output)
        val stream = BluetoothRfcommDuplexStream(socket)
        try {
            stream.send(byteArrayOf(5, 6))
            assertArrayEquals(byteArrayOf(5, 6), output.toByteArray())
            assertArrayEquals(byteArrayOf(1, 2), stream.recv(2, 2000))
            assertArrayEquals(byteArrayOf(3, 4), stream.recv(2, 2000))
            assertArrayEquals(byteArrayOf(), stream.recv(2, 2000))
        } finally { stream.close() }
        stream.close()
        verify(socket, times(1)).close()
        assertThrows(java.io.IOException::class.java) { stream.send(byteArrayOf(7)) }
    }
}
