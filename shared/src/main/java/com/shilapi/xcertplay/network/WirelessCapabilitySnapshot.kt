package com.shilapi.xcertplay.network

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Build

internal data class WirelessCapabilityFacts(
    val apiLevel: Int,
    val wifiDirectFeature: Boolean?,
    val gpsLocationFeature: Boolean?,
    val fiveGhzRadioSupported: Boolean?,
    val wifiEnabled: Boolean?,
    val connectedFrequencyMhz: Int?,
)

internal fun formatWirelessCapabilitySnapshot(facts: WirelessCapabilityFacts): String {
    val frequency = facts.connectedFrequencyMhz
    val currentBand = when (frequency) {
        in 2_400..2_500 -> "2.4 GHz"
        in 4_900..5_900 -> "5 GHz"
        null, 0 -> "unknown (not connected or unavailable)"
        else -> "unknown"
    }
    return buildString {
        appendLine("Phase 3W.1 wireless capability snapshot (read-only)")
        appendLine("Android API: ${facts.apiLevel}")
        appendLine("PackageManager Wi-Fi Direct feature: ${facts.wifiDirectFeature ?: "unknown"}")
        appendLine("PackageManager GPS location feature: ${facts.gpsLocationFeature ?: "unknown"} (declared hardware only)")
        appendLine("WifiManager 5 GHz radio support: ${facts.fiveGhzRadioSupported ?: "unknown"} (not SoftAP-specific)")
        appendLine("Wi-Fi enabled: ${facts.wifiEnabled ?: "unknown"}")
        val currentConnection = frequency?.let { "$it MHz ($currentBand)" }
            ?: "unknown (not connected or unavailable)"
        appendLine("Current Wi-Fi connection frequency: $currentConnection")
        appendLine("2.4 GHz chipset query: unavailable on API levels below 31")
        appendLine("SoftAP band support and STA+AP concurrency: not exposed by this API22 snapshot")
        appendLine("Wi-Fi chipset/driver identity: not exposed; no system files or identifiers read")
        appendLine("Bluetooth: use the existing Phase 3B cache-only diagnostics; no adapter or vendor query run")
        appendLine("No scan, hotspot change, Bluetooth discovery, connection, or advertisement was started.")
    }
}

/** Captures only public, passive framework capability/state metadata. */
class WirelessCapabilitySnapshot(context: Context) {
    private val app = context.applicationContext

    fun capture(): String {
        val packageManager = app.packageManager
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiEnabled = safelyRead { wifi?.isWifiEnabled }
        val frequency = if (wifiEnabled == true) {
            safelyRead {
                wifi?.connectionInfo?.takeIf { it.supplicantState == SupplicantState.COMPLETED }
                    ?.frequency?.takeIf { it > 0 }
            }
        } else {
            null
        }
        return formatWirelessCapabilitySnapshot(
            WirelessCapabilityFacts(
                apiLevel = Build.VERSION.SDK_INT,
                wifiDirectFeature = safelyRead {
                    packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
                },
                gpsLocationFeature = safelyRead {
                    packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
                },
                fiveGhzRadioSupported = safelyRead { wifi?.is5GHzBandSupported },
                wifiEnabled = wifiEnabled,
                connectedFrequencyMhz = frequency,
            ),
        )
    }

    private inline fun <T> safelyRead(read: () -> T): T? = try {
        read()
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        null
    }
}