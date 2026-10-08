package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessCapabilitySnapshotTest {
    @Test
    fun reportsRadioSupportWithoutClaimingSoftApOrConcurrency() {
        val report = formatWirelessCapabilitySnapshot(
            WirelessCapabilityFacts(
                apiLevel = 22,
                wifiDirectFeature = true,
                gpsLocationFeature = true,
                fiveGhzRadioSupported = true,
                wifiEnabled = true,
                connectedFrequencyMhz = 5180,
            ),
        )

        assertTrue(report.contains("PackageManager Wi-Fi Direct feature: true"))
        assertTrue(report.contains("PackageManager GPS location feature: true (declared hardware only)"))
        assertTrue(report.contains("5 GHz radio support: true (not SoftAP-specific)"))
        assertTrue(report.contains("5180 MHz (5 GHz)"))
        assertTrue(report.contains("2.4 GHz chipset query: unavailable on API levels below 31"))
        assertTrue(report.contains("SoftAP band support and STA+AP concurrency: not exposed"))
    }

    @Test
    fun unavailableAndDisconnectedValuesRemainUnknown() {
        val report = formatWirelessCapabilitySnapshot(
            WirelessCapabilityFacts(
                apiLevel = 22,
                wifiDirectFeature = null,
                gpsLocationFeature = null,
                fiveGhzRadioSupported = null,
                wifiEnabled = null,
                connectedFrequencyMhz = null,
            ),
        )

        assertTrue(report.contains("Wi-Fi Direct feature: unknown"))
        assertTrue(report.contains("GPS location feature: unknown"))
        assertTrue(report.contains("5 GHz radio support: unknown"))
        assertTrue(report.contains("Wi-Fi enabled: unknown"))
        assertTrue(report.contains("Current Wi-Fi connection frequency: unknown (not connected or unavailable)"))
        assertFalse(report.contains("SoftAP band support: false"))
    }

    @Test
    fun reportDoesNotIncludeNetworkOrHardwareIdentifiers() {
        val report = formatWirelessCapabilitySnapshot(
            WirelessCapabilityFacts(22, true, true, false, true, 2412),
        ).lowercase()

        listOf("ssid", "bssid", "mac address", "device name", "hardware name", "driver name").forEach {
            assertFalse("unexpected identifier field: $it", report.contains(it))
        }
        assertTrue(report.contains("2412 mhz (2.4 ghz)"))
    }
}