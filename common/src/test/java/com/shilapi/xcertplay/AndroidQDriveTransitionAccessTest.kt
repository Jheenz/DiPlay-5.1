package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidQDriveTransitionAccessTest {
    @Test fun exactAllowedRequestHasNullPayloadAndCannotBeRetriedOrFollowedByOldConnectionReads() {
        val f = Fixture()
        val session = f.access.open(f.snapshot)
        `when`(f.connection.controlTransfer(0x40, 0x52, 0, 2, null, 0, 1000)).thenReturn(-1)
        assertEquals(-1, session.sendAuditedRequest())
        assertThrows(IllegalStateException::class.java) { session.sendAuditedRequest() }
        assertThrows(IllegalStateException::class.java) { session.configuration {} }
        assertThrows(IllegalStateException::class.java) { session.valeria({}, {}) }
        session.close()
        verify(f.connection).controlTransfer(0x40, 0x52, 0, 2, null, 0, 1000)
        verify(f.connection).close()
        verifyNoMoreInteractions(f.connection)
        verify(f.manager).deviceList
        verify(f.manager).hasPermission(f.device)
        verify(f.manager).openDevice(f.device)
        verifyNoMoreInteractions(f.manager)
    }

    @Test fun exceptionConsumesSingleAttemptAndFinallyCloses() {
        val f = Fixture()
        `when`(f.connection.controlTransfer(0x40, 0x52, 0, 2, null, 0, 1000))
            .thenThrow(IllegalStateException("disconnected"))
        val session = f.access.open(f.snapshot)
        try {
            assertThrows(IllegalStateException::class.java) { session.sendAuditedRequest() }
            assertThrows(IllegalStateException::class.java) { session.sendAuditedRequest() }
        } finally { session.close() }
        verify(f.connection).controlTransfer(0x40, 0x52, 0, 2, null, 0, 1000)
        verify(f.connection).close()
        verifyNoMoreInteractions(f.connection)
    }

    @Test fun permissionLossIdentityChangeAndNullOpenNeverTransfer() {
        for (stage in listOf("permission", "identity", "open")) {
            val f = Fixture()
            when (stage) {
                "permission" -> `when`(f.manager.hasPermission(f.device)).thenReturn(false)
                "identity" -> `when`(f.device.productId).thenReturn(0x12ab)
                "open" -> `when`(f.manager.openDevice(f.device)).thenReturn(null)
            }
            assertThrows(IllegalStateException::class.java) { f.access.open(f.snapshot) }
            verifyNoInteractions(f.connection)
        }
    }

    @Test fun readOnlySessionUsesExistingStandardConfigurationAndDiscriminatorWithoutClaims() {
        val f = Fixture()
        val raw = listOf(18, 1, 0, 2, 0, 0, 0, 64, 0xac, 5, 0xa8, 0x12, 0, 1, 0, 0, 0, 1,
            9, 2, 18, 0, 1, 1, 0, 0x80, 250, 9, 4, 0, 0, 0, 6, 1, 1, 0)
            .map { it.toByte() }.toByteArray()
        `when`(f.connection.rawDescriptors).thenReturn(raw)
        doAnswer {
            it.getArgument<ByteArray>(4)[0] = 1
            1
        }.`when`(f.connection).controlTransfer(eq(0x80), eq(8), eq(0), eq(0),
            any(ByteArray::class.java), eq(1), eq(1000))
        val session = f.access.open(f.snapshot)
        try {
            assertEquals(1, session.configuration {})
            assertEquals(false, session.valeria({}, {}))
        } finally { session.close() }
        verify(f.connection).controlTransfer(eq(0x80), eq(8), eq(0), eq(0),
            any(ByteArray::class.java), eq(1), eq(1000))
        verify(f.connection).rawDescriptors
        verify(f.connection).close()
        verifyNoMoreInteractions(f.connection)
    }

    @Test fun compiledBoundaryHasNoSetterClaimPermissionOrTransportReferences() {
        val forbidden = listOf("setConfiguration", "setInterface", "claimInterface", "requestPermission",
            "bulkTransfer", "UsbRequest", "com/shilapi/xcertplay/transport/", "com/shilapi/xcertplay/mfi/",
            "android/bluetooth/", "loadLibrary", "sendBroadcast", "StartSession", "StartService")
        val classes = listOf(QDriveTransitionAccess::class.java, QDriveTransitionConnection::class.java,
            QDriveVendorTransitionDiagnostic::class.java, AndroidQDriveTransitionAccess::class.java,
            Class.forName("com.shilapi.xcertplay.AndroidQDriveTransitionAccess\$Session"))
        for (clazz in classes) {
            val bytes = requireNotNull(clazz.getResourceAsStream("/${clazz.name.replace('.', '/')}.class"))
                .use { it.readBytes().toString(Charsets.ISO_8859_1) }
            forbidden.forEach { assertFalse("${clazz.simpleName}: $it", bytes.contains(it)) }
        }
    }

    private class Fixture {
        val manager = mock(UsbManager::class.java)
        val device = mock(UsbDevice::class.java)
        val connection = mock(UsbDeviceConnection::class.java)
        val snapshot = PassiveUsbDevice("apple", 0x05ac, 0x12a8, 0, 0, 0, true, emptyList())
        val access = AndroidQDriveTransitionAccess(mock(Context::class.java), manager)
        init {
            `when`(device.vendorId).thenReturn(0x05ac)
            `when`(device.productId).thenReturn(0x12a8)
            `when`(device.deviceName).thenReturn("apple")
            `when`(manager.deviceList).thenReturn(hashMapOf("apple" to device))
            `when`(manager.hasPermission(device)).thenReturn(true)
            `when`(manager.openDevice(device)).thenReturn(connection)
        }
    }
}
