package com.shilapi.xcertplay

import android.app.PendingIntent
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidActiveConfig5UsbMuxClaimAccessTest {
    private class Fixture {
        val manager = mock(UsbManager::class.java)
        val device = mock(UsbDevice::class.java)
        val connection = mock(UsbDeviceConnection::class.java)
        val access = AndroidActiveConfig5UsbMuxClaimAccess(manager)
        val interfaces = mutableMapOf<Int, List<UsbInterface>>()
        init {
            val snapshot = claimTestDevice()
            `when`(device.deviceName).thenReturn(snapshot.name)
            `when`(device.vendorId).thenReturn(snapshot.vendorId)
            `when`(device.productId).thenReturn(snapshot.productId)
            `when`(device.configurationCount).thenReturn(5)
            snapshot.configurations.reversed().forEachIndexed { index, config ->
                val actual = mock(UsbConfiguration::class.java)
                val scoped = config.interfaces.map { intf ->
                    mock(UsbInterface::class.java).also { obj ->
                        `when`(obj.id).thenReturn(intf.id)
                        `when`(obj.alternateSetting).thenReturn(intf.alternateSetting!!)
                        `when`(obj.name).thenReturn(intf.name)
                        `when`(obj.interfaceClass).thenReturn(intf.interfaceClass)
                        `when`(obj.interfaceSubclass).thenReturn(intf.subclass)
                        `when`(obj.interfaceProtocol).thenReturn(intf.protocol)
                        `when`(obj.endpointCount).thenReturn(intf.endpoints.size)
                        intf.endpoints.forEachIndexed { ei, endpoint ->
                            val ep = mock(UsbEndpoint::class.java)
                            `when`(ep.address).thenReturn(endpoint.address)
                            `when`(ep.endpointNumber).thenReturn(endpoint.number)
                            `when`(ep.direction).thenReturn(endpoint.direction)
                            `when`(ep.type).thenReturn(endpoint.type)
                            `when`(ep.maxPacketSize).thenReturn(endpoint.maxPacketSize)
                            `when`(ep.interval).thenReturn(endpoint.interval)
                            `when`(obj.getEndpoint(ei)).thenReturn(ep)
                        }
                    }
                }
                interfaces[config.id] = scoped
                `when`(actual.id).thenReturn(config.id)
                `when`(actual.name).thenReturn(config.name)
                `when`(actual.maxPower).thenReturn(500)
                `when`(actual.interfaceCount).thenReturn(scoped.size)
                scoped.forEachIndexed { ii, intf -> `when`(actual.getInterface(ii)).thenReturn(intf) }
                `when`(device.getConfiguration(index)).thenReturn(actual)
            }
            `when`(manager.deviceList).thenReturn(hashMapOf("apple" to device))
            `when`(manager.hasPermission(device)).thenReturn(true)
            `when`(manager.openDevice(device)).thenReturn(connection)
            `when`(connection.claimInterface(interfaces.getValue(5).first(), false)).thenReturn(true)
            `when`(connection.releaseInterface(interfaces.getValue(5).first())).thenReturn(true)
            read(5)
        }
        fun read(value: Int, count: Int = 1) {
            doAnswer { it.getArgument<ByteArray>(4)[0] = value.toByte(); count }.`when`(connection).controlTransfer(
                eq(0x80), eq(0x08), eq(0), eq(0), any(ByteArray::class.java), eq(1), eq(1000))
        }
        fun session() = access.open(access.devices().single())
        fun verifyRead() = verify(connection).controlTransfer(eq(0x80), eq(0x08), eq(0), eq(0),
            any(ByteArray::class.java), eq(1), eq(1000))
    }

    @Test fun exactConfigurationObjectForceFalseAndOnlyReadClaimReleaseClose() {
        val f = Fixture()
        val report = ActiveConfig5UsbMuxClaimDiagnostic(f.access).run()
        assertTrue(report, report.contains(ActiveConfig5UsbMuxClaimDiagnostic.PASS))
        f.verifyRead()
        val ordered = inOrder(f.connection)
        ordered.verify(f.connection).controlTransfer(eq(0x80), eq(8), eq(0), eq(0),
            any(ByteArray::class.java), eq(1), eq(1000))
        ordered.verify(f.connection).claimInterface(f.interfaces.getValue(5).first(), false)
        ordered.verify(f.connection).releaseInterface(f.interfaces.getValue(5).first())
        ordered.verify(f.connection).close()
        verifyNoMoreInteractions(f.connection)
        verify(f.manager).openDevice(f.device)
        verify(f.manager, never()).requestPermission(any(UsbDevice::class.java), any(PendingIntent::class.java))
        verify(f.device, never()).getInterface(anyInt())
        verify(f.device, never()).interfaceCount
    }
    @Test fun failedShortOrMalformedGetNeverClaims() {
        for (count in listOf(-1, 0, 2)) {
            val f = Fixture()
            f.read(5, count)
            val report = ActiveConfig5UsbMuxClaimDiagnostic(f.access).run()
            assertTrue(report, report.contains("GET_CONFIGURATION_FAILURE"))
            f.verifyRead()
            verify(f.connection).close()
            verifyNoMoreInteractions(f.connection)
        }
    }
    @Test fun activeOtherValueCannotClaimEvenThroughAdapterDirectly() {
        val f = Fixture()
        f.read(1)
        val session = f.session()
        try {
            assertEquals(1, session.configuration {})
            assertThrows(IllegalStateException::class.java) { session.claimUsbMux() }
            assertThrows(IllegalStateException::class.java) { session.releaseUsbMux() }
        } finally { session.close() }
        f.verifyRead()
        verify(f.connection).close()
        verifyNoMoreInteractions(f.connection)
    }
    @Test fun sessionCannotClaimBeforeGetOrAfterCloseAndReadIsOneShot() {
        val f = Fixture()
        val session = f.session()
        try {
            assertThrows(IllegalStateException::class.java) { session.claimUsbMux() }
            assertEquals(5, session.configuration {})
            assertThrows(IllegalStateException::class.java) { session.configuration {} }
        } finally { session.close() }
        assertThrows(IllegalStateException::class.java) { session.claimUsbMux() }
        f.verifyRead()
        verify(f.connection).close()
        verifyNoMoreInteractions(f.connection)
    }
    @Test fun falseAndThrowingClaimConsumeAttemptAndNeverRelease() {
        for (throws in listOf(false, true)) {
            val f = Fixture()
            val target = f.interfaces.getValue(5).first()
            if (throws) `when`(f.connection.claimInterface(target, false)).thenThrow(IllegalStateException("detached"))
            else `when`(f.connection.claimInterface(target, false)).thenReturn(false)
            val session = f.session()
            try {
                session.configuration {}
                if (throws) assertThrows(IllegalStateException::class.java) { session.claimUsbMux() }
                else assertFalse(session.claimUsbMux())
                assertThrows(IllegalStateException::class.java) { session.claimUsbMux() }
                assertThrows(IllegalStateException::class.java) { session.releaseUsbMux() }
            } finally { session.close() }
            f.verifyRead()
            verify(f.connection).claimInterface(target, false)
            verify(f.connection).close()
            verifyNoMoreInteractions(f.connection)
        }
    }
    @Test fun releaseFalseAndThrowingReleaseConsumeAttemptAndClose() {
        for (throws in listOf(false, true)) {
            val f = Fixture()
            val target = f.interfaces.getValue(5).first()
            if (throws) `when`(f.connection.releaseInterface(target)).thenThrow(IllegalStateException("detached"))
            else `when`(f.connection.releaseInterface(target)).thenReturn(false)
            val session = f.session()
            try {
                session.configuration {}
                assertTrue(session.claimUsbMux())
                if (throws) assertThrows(IllegalStateException::class.java) { session.releaseUsbMux() }
                else assertFalse(session.releaseUsbMux())
                assertThrows(IllegalStateException::class.java) { session.releaseUsbMux() }
            } finally { session.close() }
            f.verifyRead()
            verify(f.connection).claimInterface(target, false)
            verify(f.connection).releaseInterface(target)
            verify(f.connection).close()
            verifyNoMoreInteractions(f.connection)
        }
    }
    @Test fun disappearancePermissionLossAndDescriptorMutationAfterReadNeverClaim() {
        for (change in 0..2) {
            val f = Fixture()
            val session = f.session()
            try {
                session.configuration {}
                when (change) {
                    0 -> `when`(f.manager.deviceList).thenReturn(hashMapOf())
                    1 -> `when`(f.manager.hasPermission(f.device)).thenReturn(false)
                    2 -> `when`(f.interfaces.getValue(5).first().id).thenReturn(9)
                }
                assertThrows(IllegalStateException::class.java) { session.claimUsbMux() }
            } finally { session.close() }
            f.verifyRead()
            verify(f.connection).close()
            verifyNoMoreInteractions(f.connection)
        }
    }
    @Test fun openNullAndPermissionLossBeforeOpenFailClosed() {
        val f = Fixture()
        `when`(f.manager.openDevice(f.device)).thenReturn(null)
        assertTrue(ActiveConfig5UsbMuxClaimDiagnostic(f.access).run().contains("DEVICE_OPEN_FAILURE"))
        verifyNoInteractions(f.connection)
        val denied = Fixture()
        `when`(denied.manager.hasPermission(denied.device)).thenReturn(false)
        assertTrue(ActiveConfig5UsbMuxClaimDiagnostic(denied.access).run().contains("PERMISSION_UNAVAILABLE"))
        verify(denied.manager, never()).openDevice(any(UsbDevice::class.java))
    }
    @Test fun compiledBoundaryHasNoConfigurationVendorBulkInterruptOrTransportEntryPoints() {
        val forbidden = listOf("setConfiguration", "setInterface", "requestPermission", "bulkTransfer",
            "UsbRequest", "sendAuditedRequest", "QDriveVendorTransitionDiagnostic", "QDriveConfigurationDiagnostic",
            "com/shilapi/xcertplay/transport/", "com/shilapi/xcertplay/mfi/", "loadLibrary", "sendBroadcast")
        val classes = listOf(ActiveConfig5UsbMuxClaimDiagnostic::class.java, ActiveConfig5UsbMuxClaimSelection::class.java,
            AndroidActiveConfig5UsbMuxClaimAccess::class.java,
            Class.forName("com.shilapi.xcertplay.AndroidActiveConfig5UsbMuxClaimAccess\$Session"))
        classes.forEach { clazz ->
            val bytes = requireNotNull(clazz.getResourceAsStream("/${clazz.name.replace('.', '/')}.class"))
                .use { it.readBytes().toString(Charsets.ISO_8859_1) }
            forbidden.forEach { assertFalse("${clazz.name}: $it", bytes.contains(it)) }
        }
    }
}
