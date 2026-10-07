package com.shilapi.xcertplay

import android.hardware.usb.UsbDevice
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
class PassiveUsbInventoryTest {
    @Test fun androidBoundaryUsesOnlyApi22CompatibleDeviceListPermissionStatusAndDescriptorGetters() {
        val manager = mock(UsbManager::class.java)
        val device = mock(UsbDevice::class.java)
        val intf = mock(UsbInterface::class.java)
        val endpoint = mock(UsbEndpoint::class.java)
        `when`(manager.deviceList).thenReturn(hashMapOf("apple" to device))
        `when`(manager.hasPermission(device)).thenReturn(false)
        `when`(device.deviceName).thenReturn("/dev/bus/usb/001/002")
        `when`(device.vendorId).thenReturn(0x05ac)
        `when`(device.productId).thenReturn(0x12a8)
        `when`(device.deviceClass).thenReturn(0)
        `when`(device.deviceSubclass).thenReturn(1)
        `when`(device.deviceProtocol).thenReturn(2)
        `when`(device.interfaceCount).thenReturn(1)
        `when`(device.getInterface(0)).thenReturn(intf)
        `when`(intf.id).thenReturn(7)
        `when`(intf.interfaceClass).thenReturn(255)
        `when`(intf.interfaceSubclass).thenReturn(254)
        `when`(intf.interfaceProtocol).thenReturn(253)
        `when`(intf.endpointCount).thenReturn(1)
        `when`(intf.getEndpoint(0)).thenReturn(endpoint)
        `when`(endpoint.address).thenReturn(0x81)
        `when`(endpoint.endpointNumber).thenReturn(1)
        `when`(endpoint.direction).thenReturn(0x80)
        `when`(endpoint.type).thenReturn(2)
        `when`(endpoint.maxPacketSize).thenReturn(512)
        `when`(endpoint.interval).thenReturn(0)

        val report = PassiveUsbDeviceDiagnostic(AndroidPassiveUsbInventory(manager)).scan()

        assertTrue(report.contains("Device name=/dev/bus/usb/001/002"))
        assertTrue(report.contains("vendorId=0x05AC (1452) productId=0x12A8 (4776)"))
        assertTrue(report.contains("Apple USB device candidate"))
        assertTrue(report.contains("not proof of an iPhone or CarPlay"))
        assertTrue(report.contains("class=0 subclass=1 protocol=2"))
        assertTrue(report.contains("interfaceCount=1 permissionAlreadyGranted=false"))
        assertTrue(report.contains("Interface index=0 id=7 class=255 subclass=254 protocol=253 endpointCount=1"))
        assertTrue(report.contains("Endpoint address=0x81 number=1 direction=IN (128) type=BULK (2) maxPacketSize=512 interval=0"))
        assertTrue(report.contains(PassiveUsbDeviceDiagnostic.SAFETY))

        verify(manager).deviceList
        verify(manager).hasPermission(device)
        verify(device).deviceName
        verify(device).vendorId
        verify(device).productId
        verify(device).deviceClass
        verify(device).deviceSubclass
        verify(device).deviceProtocol
        verify(device).interfaceCount
        verify(device).getInterface(0)
        verify(device).configurationCount
        verify(intf).id
        verify(intf).interfaceClass
        verify(intf).interfaceSubclass
        verify(intf).interfaceProtocol
        verify(intf).alternateSetting
        verify(intf).name
        verify(intf).endpointCount
        verify(intf).getEndpoint(0)
        verify(endpoint).address
        verify(endpoint).endpointNumber
        verify(endpoint).direction
        verify(endpoint).type
        verify(endpoint).maxPacketSize
        verify(endpoint).interval
        verifyNoMoreInteractions(manager, device, intf, endpoint)
    }

    @Test fun injectableInventoryReportsEveryDeviceInterfaceAndEndpointWithoutRequiringPermission() {
        var calls = 0
        val endpoints = (0..3).map {
            PassiveUsbEndpoint(it, it, 0, it, 64, it + 1)
        }
        val report = PassiveUsbDeviceDiagnostic(PassiveUsbInventory {
            calls++
            listOf(
                PassiveUsbDevice("other", 0x1234, 1, 9, 0, 0, true, emptyList()),
                PassiveUsbDevice("apple", 0x05ac, 2, 0, 0, 0, false, listOf(
                    PassiveUsbInterface(3, 255, 1, 2, endpoints),
                    PassiveUsbInterface(9, 3, 0, 0, emptyList()),
                )),
            )
        }).scan()
        assertEquals(1, calls)
        assertTrue(report.contains("Attached USB device count=2"))
        assertTrue(report.contains("Device name=other"))
        assertTrue(report.contains("interfaceCount=0 permissionAlreadyGranted=true"))
        assertTrue(report.contains("Interface index=1 id=9 class=3 subclass=0 protocol=0 endpointCount=0"))
        listOf("CONTROL", "ISOCHRONOUS", "BULK", "INTERRUPT").forEach {
            assertTrue(report.contains("direction=OUT (0) type=$it"))
        }
        assertEquals(1, Regex("Apple USB device candidate").findAll(report).count())
    }

    @Test fun emptyAndDeniedInventoriesRemainExplicitAndPassive() {
        val empty = PassiveUsbDeviceDiagnostic(PassiveUsbInventory { emptyList() }).scan()
        assertTrue(empty.contains("Attached USB device count=0"))
        assertTrue(empty.contains("No attached USB devices visible"))
        for (error in listOf(SecurityException("denied"), IllegalStateException("service unavailable"))) {
            val report = PassiveUsbDeviceDiagnostic(PassiveUsbInventory { throw error }).scan()
            assertTrue(report.contains("ERROR enumerating USB devices: ${error.javaClass.simpleName}: ${error.message}"))
            assertTrue(report.contains(PassiveUsbDeviceDiagnostic.SAFETY))
            assertFalse(report.contains("Attached USB device count=0"))
        }
    }

    @Test fun compiledPassiveBoundaryHasNoActiveUsbOrAuthenticationDependencies() {
        val forbidden = listOf(
            "openDevice", "requestPermission", "claimInterface", "controlTransfer", "bulkTransfer",
            "setConfiguration", "setInterface",
            "UsbRequest", "UsbDeviceConnection", "IphoneUsbHost", "Iap2UsbMuxHost", "NcmUsbBridge",
            "LockdownPairingClient", "LockdownCarKitClient", "Iap2Session", "MfiAuthenticator",
            "MfiDeviceScanner", "I2cTransport", "loadLibrary", "sendBroadcast", "startService",
            "com/shilapi/xcertplay/mfi/", "com/shilapi/xcertplay/transport/",
            "android/bluetooth/", "AppleInterface", "ApplePrivate", "QDrive", "AutoKit",
        )
        val classes = listOf(
            AndroidPassiveUsbInventory::class.java, PassiveUsbInventory::class.java,
            PassiveUsbDeviceDiagnostic::class.java, PassiveUsbDeviceDiagnostic.Companion::class.java,
            PassiveUsbDevice::class.java, PassiveUsbInterface::class.java, PassiveUsbEndpoint::class.java,
            PassiveUsbConfiguration::class.java, PassiveUsbConfigurationMapping::class.java,
        )
        classes.forEach { clazz ->
            val bytecode = requireNotNull(clazz.getResourceAsStream("/${clazz.name.replace('.', '/')}.class"))
                .use { it.readBytes().toString(Charsets.ISO_8859_1) }
            forbidden.forEach { symbol ->
                assertFalse("${clazz.simpleName} references forbidden capability $symbol", bytecode.contains(symbol))
            }
        }
        assertEquals(listOf("snapshot"), PassiveUsbInventory::class.java.declaredMethods.map { it.name })
    }
}
