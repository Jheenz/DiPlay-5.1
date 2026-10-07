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
class PassiveUsbConfigurationMappingTest {
    @Test fun androidAdapterReadsBothConfigurationAndFlattenedViewsThroughDescriptorGettersOnly() {
        val manager = mock(UsbManager::class.java)
        val device = mock(UsbDevice::class.java)
        val mux = mock(UsbInterface::class.java)
        val out = endpoint(0x04)
        val input = endpoint(0x85)
        `when`(mux.id).thenReturn(1)
        `when`(mux.alternateSetting).thenReturn(0)
        `when`(mux.interfaceClass).thenReturn(255)
        `when`(mux.interfaceSubclass).thenReturn(254)
        `when`(mux.interfaceProtocol).thenReturn(2)
        `when`(mux.endpointCount).thenReturn(2)
        `when`(mux.getEndpoint(0)).thenReturn(out)
        `when`(mux.getEndpoint(1)).thenReturn(input)
        val configs = listOf(3, 7).map { id ->
            mock(UsbConfiguration::class.java).also {
                `when`(it.id).thenReturn(id)
                `when`(it.name).thenReturn(if (id == 3) "USB configuration" else null)
                `when`(it.isSelfPowered).thenReturn(true)
                `when`(it.isRemoteWakeup).thenReturn(false)
                `when`(it.maxPower).thenReturn(500)
                `when`(it.interfaceCount).thenReturn(1)
                `when`(it.getInterface(0)).thenReturn(mux)
            }
        }
        `when`(device.deviceName).thenReturn("apple")
        `when`(device.vendorId).thenReturn(0x05ac)
        `when`(device.productId).thenReturn(0x12a8)
        `when`(device.interfaceCount).thenReturn(2)
        `when`(device.getInterface(0)).thenReturn(mux)
        `when`(device.getInterface(1)).thenReturn(mux)
        `when`(device.configurationCount).thenReturn(2)
        configs.forEachIndexed { index, config -> `when`(device.getConfiguration(index)).thenReturn(config) }
        `when`(manager.deviceList).thenReturn(hashMapOf("apple" to device))
        `when`(manager.hasPermission(device)).thenReturn(true)

        val snapshot = AndroidPassiveUsbInventory(manager).snapshot().single()
        val report = PassiveUsbDeviceDiagnostic(PassiveUsbInventory { listOf(snapshot) }, true).scan()
        assertEquals(listOf(3, 7), snapshot.configurations.map { it.id })
        assertTrue(report.contains("configurationCount=2"))
        assertTrue(report.contains("arrayIndex=0 id=3 name=USB configuration"))
        assertTrue(report.contains("arrayIndex=1 id=7 name=unavailable"))
        assertTrue(report.contains("exposedMask=0x60 exposedBits=0x40"))
        assertTrue(report.contains("selfPowered=true remoteWakeup=false maxPower=500mA"))
        assertTrue(report.contains("Active/current configuration=UNKNOWN"))
        verify(manager).deviceList
        verify(manager).hasPermission(device)
        verifyNoMoreInteractions(manager)
        assertOnlyGetters(device, setOf(
            "getDeviceName", "getVendorId", "getProductId", "getDeviceClass", "getDeviceSubclass",
            "getDeviceProtocol", "getInterfaceCount", "getInterface", "getConfigurationCount", "getConfiguration",
        ))
        configs.forEach { assertOnlyGetters(it, setOf(
            "getId", "getName", "isSelfPowered", "isRemoteWakeup", "getMaxPower", "getInterfaceCount", "getInterface",
        )) }
        assertOnlyGetters(mux, setOf(
            "getId", "getName", "getAlternateSetting", "getInterfaceClass", "getInterfaceSubclass",
            "getInterfaceProtocol", "getEndpointCount", "getEndpoint",
        ))
        listOf(out, input).forEach { assertOnlyGetters(it, setOf(
            "getAddress", "getEndpointNumber", "getDirection", "getType", "getMaxPacketSize", "getInterval",
        )) }
    }

    @Test fun observedFlattenedIndicesSixAndEightCorrelateWithMultipleConfigurationsWithoutChoosingOne() {
        // Configuration split is a test hypothesis, not a claimed E01 configuration layout.
        val flattened = (0 until 12).map { index ->
            when (index) {
                6, 8 -> mux()
                9, 10, 11 -> mux().copy(subclass = 253, protocol = 1)
                else -> mux().copy(id = index, interfaceClass = 3, subclass = 0, protocol = 0, endpoints = emptyList())
            }
        }
        val device = phone(flattened, listOf(
            config(3, flattened.take(8)),
            config(7, flattened.drop(8)),
        ))
        val report = describe(device)
        assertTrue(report.contains("flattenedCandidate=6 id=1 alt=0"))
        assertTrue(report.contains("flattenedCandidate=8 id=1 alt=0"))
        assertTrue(report.contains("configIndex=0 configId=3 -> interfaceIndex=6 id=1 -> alt=0"))
        assertTrue(report.contains("configIndex=1 configId=7 -> interfaceIndex=0 id=1 -> alt=0"))
        assertTrue(report.contains("flattenedDescriptorMatches=[6, 8]"))
        assertTrue(report.contains("different configuration array entries"))
        assertTrue(report.contains("cannot be assigned uniquely"))
        assertTrue(report.contains("flattenedCandidates=2 configurationScopedCandidates=2"))
        assertTrue(report.contains("Endpoint address=0x04 number=4 direction=0 type=2 maxPacketSize=512 interval=0"))
        assertTrue(report.contains("Endpoint address=0x85 number=5 direction=128 type=2 maxPacketSize=512 interval=0"))
        assertFalse(report.contains("flattenedCandidate=9"))
        assertTrue(report.contains("No interface selected"))
    }

    @Test fun alternateSettingsWithinOneConfigurationAreDistinguishedByDescriptorValue() {
        val interfaces = listOf(mux(0), mux(1))
        val report = describe(phone(interfaces, listOf(config(4, interfaces))))
        assertTrue(report.contains("Alternate settings represented within configIndex=0 interfaceId=1"))
        assertTrue(report.contains("alt=0 ->"))
        assertTrue(report.contains("alt=1 ->"))
        assertTrue(report.contains("flattenedDescriptorMatches=[0]"))
        assertTrue(report.contains("flattenedDescriptorMatches=[1]"))
        assertFalse(report.contains("different configuration array entries"))
    }

    @Test fun descriptorIdenticalEntriesDoNotProveAndroidDuplicationOrActiveState() {
        val interfaces = listOf(mux(), mux())
        val report = describe(phone(interfaces, listOf(config(4, interfaces))))
        assertTrue(report.contains("Descriptor-identical repeated entries within one configuration"))
        assertTrue(report.contains("cause/Android duplication not established"))
        assertTrue(report.contains("Active/current configuration=UNKNOWN"))
    }

    @Test fun endpointDifferencesAndMissingConfigurationMatchesRemainExplicit() {
        val changed = mux().copy(endpoints = mux().endpoints.map { it.copy(interval = 1) })
        val report = describe(phone(listOf(mux(), mux(2)), listOf(
            config(4, listOf(mux())), config(9, listOf(changed)),
        )))
        assertTrue(report.contains("differ in endpoint descriptor properties"))
        assertTrue(report.contains("WARNING: no configuration-scoped descriptor match"))
    }

    private fun assertOnlyGetters(mock: Any, allowed: Set<String>) {
        val invoked = mockingDetails(mock).invocations.map { it.method.name }
        assertTrue("Unexpected capability: $invoked", invoked.all { it in allowed })
    }

    private fun endpoint(address: Int) = mock(UsbEndpoint::class.java).also {
        `when`(it.address).thenReturn(address)
        `when`(it.endpointNumber).thenReturn(address and 15)
        `when`(it.direction).thenReturn(address and 0x80)
        `when`(it.type).thenReturn(2)
        `when`(it.maxPacketSize).thenReturn(512)
    }
    private fun mux(alt: Int = 0) = PassiveUsbInterface(
        1, 255, 254, 2, listOf(
            PassiveUsbEndpoint(0x04, 4, 0, 2, 512, 0),
            PassiveUsbEndpoint(0x85, 5, 128, 2, 512, 0),
        ), alt,
    )
    private fun config(id: Int, interfaces: List<PassiveUsbInterface>) =
        PassiveUsbConfiguration(id, null, false, true, 500, interfaces)
    private fun phone(interfaces: List<PassiveUsbInterface>, configurations: List<PassiveUsbConfiguration>) =
        PassiveUsbDevice("apple", 0x05ac, 0x12a8, 0, 0, 0, true, interfaces, configurations)
    private fun describe(device: PassiveUsbDevice) =
        PassiveUsbDeviceDiagnostic(PassiveUsbInventory { listOf(device) }, true).scan()
}
