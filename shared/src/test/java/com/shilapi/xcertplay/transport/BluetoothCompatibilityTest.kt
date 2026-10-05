package com.shilapi.xcertplay.transport

import android.Manifest
import org.junit.Assert.*
import org.junit.Test

class BluetoothCompatibilityTest {
    @Test fun api22HasNoRuntimeBluetoothOrLocationPermissions() {
        assertTrue(BluetoothCompatibility.runtimePermissions(22, scan = false).isEmpty())
        assertTrue(BluetoothCompatibility.runtimePermissions(22, scan = true).isEmpty())
    }

    @Test fun modernPermissionsRemainOperationSpecific() {
        for (sdk in listOf(23, 28, 30)) {
            assertTrue(BluetoothCompatibility.runtimePermissions(sdk, false).isEmpty())
            assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BluetoothCompatibility.runtimePermissions(sdk, true))
        }
        for (sdk in listOf(31, 33, 37)) {
            assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT), BluetoothCompatibility.runtimePermissions(sdk, false))
            assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                BluetoothCompatibility.runtimePermissions(sdk, true))
        }
        assertEquals("00000000-deca-fade-deca-deafdecacafe", BluetoothCompatibility.IAP2_SERVICE_UUID.toString())
    }
}
