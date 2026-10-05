package com.shilapi.xcertplay.network

import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import java.net.InetAddress

internal data class LegacyNetworkLink(
    val type: Int,
    val connected: Boolean,
    val interfaceName: String?,
    val addresses: List<InetAddress>,
)

internal data class LegacyNetworkState(
    val defaultType: Int?,
    val defaultState: NetworkInfo.State?,
    val wifiConnected: Boolean,
    val stationAddress: InetAddress?,
    val links: List<LegacyNetworkLink>,
)

/** Android 21/22 have Network/LinkProperties, but no activeNetwork accessor. */
@Suppress("DEPRECATION")
internal fun readLegacyNetworkState(
    connectivity: ConnectivityManager,
    wifi: WifiManager,
    onDiagnostic: (String) -> Unit = {},
): LegacyNetworkState {
    val active = connectivity.activeNetworkInfo
    val connected = connectivity.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true
    val station = if (connected) {
        try {
            legacyWifiIpv4(wifi.connectionInfo?.ipAddress ?: 0)
        } catch (error: SecurityException) {
            onDiagnostic("legacy station address unavailable: ${error.javaClass.simpleName}; using link properties")
            null
        }
    } else null
    val links = connectivity.allNetworks.mapNotNull { network ->
        val info = connectivity.getNetworkInfo(network) ?: return@mapNotNull null
        val properties = connectivity.getLinkProperties(network)
        LegacyNetworkLink(info.type, info.isConnected, properties?.interfaceName,
            properties?.linkAddresses?.map { it.address }.orEmpty())
    }.sortedWith(compareBy<LegacyNetworkLink> { it.type }.thenBy { it.interfaceName })
    return LegacyNetworkState(active?.type, active?.state, connected, station, links)
}

internal fun legacyWifiIpv4(littleEndianAddress: Int): InetAddress? =
    if (littleEndianAddress == 0) null else InetAddress.getByAddress(
        ByteArray(4) { (littleEndianAddress ushr (it * 8)).toByte() },
    ).takeUnless { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress || it.isLinkLocalAddress }

internal fun legacyNetworkSnapshot(
    before: LegacyNetworkState,
    after: LegacyNetworkState,
    interfaces: List<HotspotInterfaceSnapshot>,
    apInterfaces: Set<String>?,
    apEnabled: Boolean?,
): HotspotNetworkSnapshot {
    val stationNames = interfaces.filter {
        it.up && before.stationAddress != null && before.stationAddress in it.addresses
    }.map { it.name }.toSet()
    val wifiLinks = before.links.filter { it.connected && it.type == ConnectivityManager.TYPE_WIFI }
    val linkNames = wifiLinks.map { link ->
        link.interfaceName?.let { setOf(it) } ?: interfaces.filter { iface ->
            iface.up && iface.addresses.any { it in link.addresses }
        }.map { it.name }.toSet()
    }
    val wifiNames = linkNames.flatten().toSet() + stationNames
    // A connected but unidentified station must not masquerade as an AP.
    val unidentifiedLink = linkNames.any { it.isEmpty() } && (wifiLinks.size != 1 || stationNames.isEmpty())
    val upstreams = if (unidentifiedLink ||
        (before.wifiConnected || wifiLinks.isNotEmpty()) && wifiNames.isEmpty()) null else wifiNames
    val defaultName = before.links.filter {
        before.defaultState == NetworkInfo.State.CONNECTED && it.connected && it.type == before.defaultType
    }
        .mapNotNull { it.interfaceName }.distinct().singleOrNull()
        ?: stationNames.singleOrNull()?.takeIf {
            before.defaultType == ConnectivityManager.TYPE_WIFI && before.defaultState == NetworkInfo.State.CONNECTED
        }
    return HotspotNetworkSnapshot(interfaces, apInterfaces, upstreams, defaultName,
        consistent = before == after, apEnabled = apEnabled)
}
