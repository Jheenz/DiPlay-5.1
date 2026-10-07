package com.shilapi.xcertplay

import android.hardware.usb.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ActiveUsbConfigurationDiagnosticTest {
    @Test fun permissionAbsentStopsWithoutOpeningOrRequestingPermission() {
        var reads = 0
        val access = object : ActiveUsbConfigurationAccess {
            override fun devices() = listOf(phone(false))
            override fun readConfiguration(device: PassiveUsbDevice, report: (String) -> Unit): Int {
                reads++
                error("Must not open")
            }
        }
        val report = ActiveUsbConfigurationDiagnostic(access).scan()
        assertEquals(0, reads)
        assertTrue(report.contains("USB permission required — STOP"))
        assertTrue(report.contains("active configuration=UNKNOWN"))
        val fixture = Fixture(permission = false)
        assertThrows(IllegalStateException::class.java) { fixture.read() }
        verify(fixture.manager).deviceList
        verify(fixture.manager).hasPermission(fixture.device)
        verifyNoMoreInteractions(fixture.manager)
        verifyNoInteractions(fixture.connection)
    }

    @Test fun openFailureHasNoClaimTransferOrConnectionCleanup() {
        val fixture = Fixture()
        `when`(fixture.manager.openDevice(fixture.device)).thenReturn(null)
        assertThrows(IllegalStateException::class.java) { fixture.read() }
        assertTrue(fixture.lines.any { it.contains("open result=FAIL") })
        verifyNoInteractions(fixture.connection)
        fixture.verifyManagerOnly()
    }

    @Test fun exactStandardRequestReturnsValuesOneThroughFourAndClosesBeforeCorrelation() {
        for (value in 1..4) {
            val fixture = Fixture(value = value)
            val access = object : ActiveUsbConfigurationAccess {
                override fun devices() = listOf(phone())
                override fun readConfiguration(device: PassiveUsbDevice, report: (String) -> Unit) =
                    fixture.adapter.readConfiguration(device, report)
            }
            val report = ActiveUsbConfigurationDiagnostic(access).scan()
            assertTrue(report, report.contains("GET_CONFIGURATION result=PASS"))
            assertTrue(report.contains("Active configuration value=$value"))
            assertTrue(report.contains("Descriptor name=${names[value - 1]}"))
            assertTrue(report.contains("cleanup connection close=PASS"))
            assertTrue(report.contains("Scoped USBMUX candidate count=${if (value >= 3) 1 else 0}"))
            assertTrue(report.contains("Apple USB Ethernet present in active configuration=${value == 4}"))
            if (value >= 3) {
                assertTrue(report.contains("USBMUX candidate configurationId=$value interfaceId=1 alt=0 class=255 subclass=254 protocol=2"))
                assertTrue(report.contains("Endpoint address=0x0004"))
                assertTrue(report.contains("Endpoint address=0x0085"))
            }
            if (value == 4) {
                assertTrue(report.contains("Apple USB Ethernet configurationId=4 interfaceId=2 alt=0"))
                assertTrue(report.contains("Apple USB Ethernet configurationId=4 interfaceId=2 alt=1"))
            }
            fixture.verifySingleRequestAndClose()
        }
    }

    @Test fun zeroShortOversizedAndNegativeTransferResultsFailAndAlwaysClose() {
        for (count in listOf(0, -1, 2)) {
            val fixture = Fixture(count = count)
            assertThrows(IllegalStateException::class.java) { fixture.read() }
            assertTrue(fixture.lines.any { it.contains("transferred=$count expected=1") })
            assertTrue(fixture.lines.contains("cleanup connection close=PASS"))
            fixture.verifySingleRequestAndClose()
        }
    }

    @Test fun transferExceptionAndOpenExceptionAreExplicitAndCleanupIsCorrect() {
        val fixture = Fixture()
        doThrow(SecurityException("transfer failed")).`when`(fixture.connection)
            .controlTransfer(eq(0x80), eq(8), eq(0), eq(0), any(ByteArray::class.java), eq(1), eq(1_000))
        assertThrows(SecurityException::class.java) { fixture.read() }
        assertTrue(fixture.lines.contains("cleanup connection close=PASS"))
        fixture.verifySingleRequestAndClose()
        val openFailure = Fixture()
        `when`(openFailure.manager.openDevice(openFailure.device)).thenThrow(SecurityException("denied"))
        assertThrows(SecurityException::class.java) { openFailure.read() }
        assertTrue(openFailure.lines.any { it.contains("open result=FAIL") && it.contains("denied") })
        verifyNoInteractions(openFailure.connection)
    }

    @Test fun zeroConfigurationAndUnknownOrDuplicateIdsDoNotGuessDescriptors() {
        for (value in listOf(0, 255)) {
            val report = ActiveUsbConfigurationDiagnostic(fake(phone(), value)).scan()
            assertTrue(report.contains("Active configuration value=$value"))
            assertFalse(report.contains("Descriptor name="))
            assertTrue(report.contains(if (value == 0) "Device is unconfigured" else "ERROR descriptor correlation"))
        }
        val duplicated = phone().copy(configurations = listOf(phone().configurations[3], phone().configurations[3]))
        val report = ActiveUsbConfigurationDiagnostic(fake(duplicated, 4)).scan()
        assertTrue(report.contains("matching configuration count=2"))
        assertFalse(report.contains("Descriptor name="))
    }

    @Test fun cleanupFailureIsReportedWithoutClaimingConfirmedSuccess() {
        val fixture = Fixture()
        doThrow(IllegalStateException("close failed")).`when`(fixture.connection).close()
        val report = ActiveUsbConfigurationDiagnostic(object : ActiveUsbConfigurationAccess {
            override fun devices() = listOf(phone())
            override fun readConfiguration(device: PassiveUsbDevice, report: (String) -> Unit) =
                fixture.adapter.readConfiguration(device, report)
        }).scan()
        assertTrue(report.contains("cleanup connection close=FAIL"))
        assertTrue(report.contains("close failed"))
        assertFalse(report.contains("GET_CONFIGURATION result=PASS"))
    }

    @Test fun noAppleOrMultipleApplesStopBeforeRead() {
        for (devices in listOf(emptyList(), listOf(phone(), phone()))) {
            val report = ActiveUsbConfigurationDiagnostic(object : ActiveUsbConfigurationAccess {
                override fun devices() = devices
                override fun readConfiguration(device: PassiveUsbDevice, report: (String) -> Unit): Int =
                    error("Must not read")
            }).scan()
            assertTrue(report.contains("Exactly one Apple device required"))
        }
    }

    @Test fun detachedOrReplacedIdentityStopsBeforePermissionAndOpen() {
        val fixture = Fixture()
        `when`(fixture.manager.deviceList).thenReturn(hashMapOf())
        assertThrows(IllegalStateException::class.java) { fixture.read() }
        verify(fixture.manager).deviceList
        verifyNoMoreInteractions(fixture.manager)
        val replaced = Fixture()
        `when`(replaced.device.productId).thenReturn(0x9999)
        assertThrows(IllegalStateException::class.java) { replaced.read() }
        verify(replaced.manager).deviceList
        verifyNoMoreInteractions(replaced.manager)
    }

    @Test fun compiledBoundaryHasNoMutationOrTransportCapabilities() {
        val forbidden = listOf(
            "requestPermission", "claimInterface", "setConfiguration", "setInterface", "bulkTransfer",
            "UsbRequest", "com/shilapi/xcertplay/transport/", "com/shilapi/xcertplay/mfi/",
            "android/bluetooth/", "StartSession", "StartService", "loadLibrary", "sendBroadcast",
        )
        for (clazz in listOf(
            ActiveUsbConfigurationAccess::class.java, ActiveUsbConfigurationDiagnostic::class.java,
            ActiveUsbConfigurationDiagnostic.Companion::class.java, AndroidActiveUsbConfigurationAccess::class.java,
        )) {
            val bytes = requireNotNull(clazz.getResourceAsStream("/${clazz.name.replace('.', '/')}.class"))
                .use { it.readBytes().toString(Charsets.ISO_8859_1) }
            forbidden.forEach { assertFalse("${clazz.simpleName} references $it", bytes.contains(it)) }
        }
    }

    private class Fixture(permission: Boolean = true, value: Int = 4, count: Int = 1) {
        val manager = mock(UsbManager::class.java)
        val device = mock(UsbDevice::class.java)
        val connection = mock(UsbDeviceConnection::class.java)
        val lines = mutableListOf<String>()
        val adapter = AndroidActiveUsbConfigurationAccess(manager)
        init {
            `when`(device.vendorId).thenReturn(0x05ac)
            `when`(device.productId).thenReturn(0x12a8)
            `when`(manager.deviceList).thenReturn(hashMapOf("apple" to device))
            `when`(manager.hasPermission(device)).thenReturn(permission)
            `when`(manager.openDevice(device)).thenReturn(connection)
            doAnswer { invocation ->
                val buffer = invocation.getArgument<ByteArray>(4)
                assertEquals(1, buffer.size)
                buffer[0] = value.toByte()
                count
            }.`when`(connection).controlTransfer(eq(0x80), eq(8), eq(0), eq(0), any(ByteArray::class.java), eq(1), eq(1_000))
        }
        fun read() = adapter.readConfiguration(phone(), lines::add)
        fun verifyManagerOnly() {
            verify(manager).deviceList
            verify(manager).hasPermission(device)
            verify(manager).openDevice(device)
            verifyNoMoreInteractions(manager)
        }
        fun verifySingleRequestAndClose() {
            verifyManagerOnly()
            val order = inOrder(connection)
            order.verify(connection).controlTransfer(eq(0x80), eq(8), eq(0), eq(0), any(ByteArray::class.java), eq(1), eq(1_000))
            order.verify(connection).close()
            verifyNoMoreInteractions(connection)
        }
    }

    companion object {
        private val names = listOf("PTP", "iPod USB Interface", "PTP + Apple Mobile Device",
            "PTP + Apple Mobile Device + Apple USB Ethernet")
        private fun phone(permission: Boolean = true): PassiveUsbDevice {
            val mux = PassiveUsbInterface(1, 255, 254, 2, listOf(
                PassiveUsbEndpoint(4, 4, 0, 2, 512, 0),
                PassiveUsbEndpoint(0x85, 5, 128, 2, 512, 0),
            ), 0)
            val ethernet = mux.copy(id = 2, subclass = 253, protocol = 1)
            return PassiveUsbDevice("apple", 0x05ac, 0x12a8, 0, 0, 0, permission, emptyList(),
                (1..4).map { id ->
                    PassiveUsbConfiguration(id, names[id - 1], false, false, 500,
                        (if (id >= 3) listOf(mux) else emptyList()) +
                            if (id == 4) listOf(ethernet, ethernet.copy(alternateSetting = 1)) else emptyList())
                })
        }
        private fun fake(device: PassiveUsbDevice, value: Int) = object : ActiveUsbConfigurationAccess {
            override fun devices() = listOf(device)
            override fun readConfiguration(device: PassiveUsbDevice, report: (String) -> Unit) = value
        }
    }
}
