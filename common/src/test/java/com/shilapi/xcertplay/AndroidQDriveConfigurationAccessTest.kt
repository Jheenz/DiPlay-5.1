package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbConfiguration
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
class AndroidQDriveConfigurationAccessTest {
    private class Fixture {
        val manager = mock(UsbManager::class.java)
        val device = mock(UsbDevice::class.java)
        val target = mock(UsbConfiguration::class.java)
        val other = mock(UsbConfiguration::class.java)
        val connection = mock(UsbDeviceConnection::class.java)
        val access = AndroidQDriveConfigurationAccess(mock(Context::class.java), manager)
        init {
            `when`(device.vendorId).thenReturn(0x05ac)
            `when`(device.productId).thenReturn(0x12a8)
            `when`(device.deviceName).thenReturn("apple")
            `when`(device.configurationCount).thenReturn(2)
            `when`(device.getConfiguration(0)).thenReturn(target)
            `when`(device.getConfiguration(1)).thenReturn(other)
            `when`(target.id).thenReturn(5)
            `when`(other.id).thenReturn(1)
            `when`(manager.deviceList).thenReturn(hashMapOf("apple" to device))
            `when`(manager.hasPermission(device)).thenReturn(true)
            `when`(manager.openDevice(device)).thenReturn(connection)
        }
    }

    @Test fun actualId5ObjectNotArrayIndexIsSelectedOnlyOnceThenStandardGetConfiguration() {
        val f = Fixture()
        val session = f.access.open(f.access.devices().single(), true)
        `when`(f.connection.setConfiguration(f.target)).thenReturn(true)
        doAnswer { it.getArgument<ByteArray>(4)[0] = 5; 1 }.`when`(f.connection).controlTransfer(
            eq(0x80), eq(8), eq(0), eq(0), any(ByteArray::class.java), eq(1), eq(1000))
        try {
            assertTrue(session.select5())
            assertThrows(IllegalStateException::class.java) { session.select5() }
            assertEquals(5, session.configuration {})
        } finally { session.close() }
        verify(f.connection).setConfiguration(f.target)
        verify(f.connection).controlTransfer(eq(0x80), eq(8), eq(0), eq(0),
            any(ByteArray::class.java), eq(1), eq(1000))
        verify(f.connection).close()
        verifyNoMoreInteractions(f.connection)
        verify(f.manager, never()).requestPermission(any(UsbDevice::class.java), any(android.app.PendingIntent::class.java))
    }

    @Test fun postInspectionHandleCannotSelectAndExceptionConsumesAttempt() {
        val f = Fixture()
        val readOnly = f.access.open(f.access.devices().single(), false)
        try { assertThrows(IllegalStateException::class.java) { readOnly.select5() } } finally { readOnly.close() }
        val session = f.access.open(f.access.devices().single(), true)
        `when`(f.connection.setConfiguration(f.target)).thenThrow(IllegalStateException("disconnected"))
        try {
            assertThrows(IllegalStateException::class.java) { session.select5() }
            assertThrows(IllegalStateException::class.java) { session.select5() }
        } finally { session.close() }
        verify(f.connection).setConfiguration(f.target)
        verify(f.connection, times(2)).close()
        verifyNoMoreInteractions(f.connection)
    }

    @Test fun compiledNewBoundaryHasNoVendorClaimAlternatePermissionOrTransportEntryPoints() {
        val forbidden = listOf("sendAuditedRequest", "QDriveVendorTransitionDiagnostic", "claimInterface",
            "releaseInterface", "setInterface", "requestPermission", "bulkTransfer", "UsbRequest",
            "com/shilapi/xcertplay/transport/", "com/shilapi/xcertplay/mfi/", "loadLibrary", "sendBroadcast")
        for (clazz in listOf(QDriveConfigurationDiagnostic::class.java, AndroidQDriveConfigurationAccess::class.java,
            Class.forName("com.shilapi.xcertplay.AndroidQDriveConfigurationAccess\$Session"))) {
            val bytes = requireNotNull(clazz.getResourceAsStream("/${clazz.name.replace('.', '/')}.class"))
                .use { it.readBytes().toString(Charsets.ISO_8859_1) }
            forbidden.forEach { assertFalse("${clazz.name}: $it", bytes.contains(it)) }
        }
    }

    @Test fun exactStringMustComeFromConfiguration5AndRawCountMustBe5() {
        for (text in listOf("PTP", "valeria", "prefix Valeria suffix")) {
            val f = Fixture()
            val header = listOf(18, 1, 0, 2, 0, 0, 0, 64, 0xac, 5, 0xa8, 0x12, 0, 1, 0, 0, 0, 5)
            val raw = header + (1..5).flatMap { id ->
                val hasInterface = id == 1 || id == 5
                listOf(9, 2, if (hasInterface) 18 else 9, 0, if (hasInterface) 1 else 0, id, 0, 0x80, 250) +
                    if (hasInterface) listOf(9, 4, 0, 0, 0, 255, 42, 255, if (id == 1) 2 else 3) else emptyList()
            }
            `when`(f.connection.rawDescriptors).thenReturn(raw.map(Int::toByte).toByteArray())
            doAnswer {
                val value = it.getArgument<Int>(2)
                val buffer = it.getArgument<ByteArray>(4)
                val response = if (value == 0x0300) byteArrayOf(4, 3, 9, 4)
                else {
                    val ascii = if (value == 0x0302) "Valeria" else text
                    byteArrayOf((2 + ascii.length * 2).toByte(), 3) + ascii.toByteArray(Charsets.UTF_16LE)
                }
                response.copyInto(buffer)
                response.size
            }.`when`(f.connection).controlTransfer(eq(0x80), eq(6), anyInt(), anyInt(),
                any(ByteArray::class.java), eq(255), eq(1000))
            val session = f.access.open(f.access.devices().single(), false)
            try {
                assertEquals(text.contains("Valeria"), session.valeria5({}, {}))
                verify(f.connection, never()).controlTransfer(eq(0x80), eq(6), eq(0x0302), anyInt(),
                    any(ByteArray::class.java), eq(255), eq(1000))
                val invalid = raw.map(Int::toByte).toByteArray().also { it[17] = 4 }
                `when`(f.connection.rawDescriptors).thenReturn(invalid)
                assertThrows(IllegalStateException::class.java) { session.valeria5({}, {}) }
            } finally { session.close() }
            verify(f.connection, never()).setConfiguration(any(UsbConfiguration::class.java))
        }
    }
}
