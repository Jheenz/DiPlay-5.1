package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.LegacyLaunchBuild
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class Phase3ADeviceDiagnosticsTest {
    private fun snapshot(address: String = "192.168.43.1") = HotspotNetworkSnapshot(
        listOf(HotspotInterfaceSnapshot("wlan0", 7, true, listOf(InetAddress.getByName(address)), true)),
        setOf("wlan0"), emptySet(), null,
    )

    @Test fun addressSignatureDetectsChangesWithoutDependingOnInterfaceOrder() {
        val value = snapshot()
        val original = value.copy(interfaces = value.interfaces + value.interfaces.first().copy(name = "wlan1", index = 8))
        assertEquals(phase3AAddressSignature(original), phase3AAddressSignature(original.copy(interfaces = original.interfaces.reversed())))
        assertNotEquals(phase3AAddressSignature(original), phase3AAddressSignature(snapshot("192.168.44.1")))
        assertNotEquals(phase3AAddressSignature(original), phase3AAddressSignature(original.copy(
            interfaces = original.interfaces.map { it.copy(up = false) })))
        assertNotEquals(phase3AAddressSignature(original), phase3AAddressSignature(original.copy(
            interfaces = original.interfaces.map { it.copy(index = 8) })))
    }

    @Test fun diagnosticServiceCannotAdvertiseCarPlayAndProjectionGatesRemainOff() {
        assertEquals("_diplay-phase3a._tcp.", Phase3ADeviceDiagnostics.SERVICE_TYPE)
        assertFalse(LegacyLaunchBuild.CONNECTIONS_ENABLED)
        assertFalse(LegacyLaunchBuild.VENDOR_INTEGRATION_ENABLED)
        assertTrue(LegacyLaunchBuild.PHASE3A_DIAGNOSTICS_ENABLED)
    }
}
