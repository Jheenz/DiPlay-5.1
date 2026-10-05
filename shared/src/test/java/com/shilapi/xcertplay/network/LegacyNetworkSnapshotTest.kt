package com.shilapi.xcertplay.network

import android.net.ConnectivityManager
import android.net.NetworkInfo
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class LegacyNetworkSnapshotTest {
    private val apAddress = InetAddress.getByName("192.168.43.1")
    private val stationAddress = InetAddress.getByName("192.168.1.20")
    private val ap = HotspotInterfaceSnapshot("wlan0", 7, true, listOf(apAddress), true)
    private val station = HotspotInterfaceSnapshot("wlan1", 8, true, listOf(stationAddress), true)
    private val idle = LegacyNetworkState(null, null, false, null, emptyList())
    private fun snapshot(before: LegacyNetworkState = idle, after: LegacyNetworkState = before,
        interfaces: List<HotspotInterfaceSnapshot> = listOf(ap), owned: Set<String>? = null) =
        legacyNetworkSnapshot(before, after, interfaces, owned, true)

    @Test fun noDefaultNetworkIsAConsistentLocalApNotASamplingFailure() {
        val value = snapshot()
        assertTrue(value.consistent)
        assertEquals(emptySet<String>(), value.wifiUpstreams)
        assertNull(value.defaultInterface)
        var time = 0L
        val selected = ManualHotspotReadiness({ value }, { false }, { time += it }, { time }).await(1500)
        assertEquals(apAddress, selected.address)
        assertEquals("wlan0", selected.name)
        assertEquals(500L, time)
    }

    @Test fun legacyStationIpIdentifiesUpstreamEvenWhenLinkPropertiesAreMissing() {
        val state = LegacyNetworkState(ConnectivityManager.TYPE_WIFI, NetworkInfo.State.CONNECTED,
            true, stationAddress, emptyList())
        val value = snapshot(state, interfaces = listOf(ap, station))
        assertEquals(setOf("wlan1"), value.wifiUpstreams)
        assertEquals("wlan1", value.defaultInterface)
        assertEquals("wlan0", selectHotspotInterface(value) {}?.name)
        assertNull(selectHotspotInterface(snapshot(state, interfaces = listOf(station))) {})
        assertEquals(listOf(stationAddress), existingWifiHostAddresses(station.addresses, station.index))
    }

    @Test fun changedStationAddressOrConnectivityRejectsTheSample() {
        assertFalse(snapshot(after = idle.copy(wifiConnected = true, stationAddress = stationAddress)).consistent)
        val state = idle.copy(defaultType = ConnectivityManager.TYPE_MOBILE, defaultState = NetworkInfo.State.CONNECTED)
        assertFalse(snapshot(state, state.copy(defaultState = NetworkInfo.State.DISCONNECTED)).consistent)
        assertNull(selectHotspotInterface(snapshot(state, idle)) {})
    }

    @Test fun unknownConnectedStationMustNotBeMistakenForAnAp() {
        val value = snapshot(idle.copy(wifiConnected = true))
        assertNull(value.wifiUpstreams)
        assertNull(selectHotspotInterface(value) {})
        assertEquals("wlan0", selectHotspotInterface(value.copy(apInterfaces = setOf("wlan0"))) {}?.name)
    }

    @Test fun platformWifiLinkIdentifiesStationWithoutReadableWifiInfo() {
        val state = idle.copy(links = listOf(LegacyNetworkLink(ConnectivityManager.TYPE_WIFI, true,
            "wlan1", listOf(stationAddress))))
        assertEquals(setOf("wlan1"), snapshot(state, interfaces = listOf(ap, station)).wifiUpstreams)
        assertEquals("wlan0", selectHotspotInterface(snapshot(state, interfaces = listOf(ap, station))) {}?.name)
        assertNull(selectHotspotInterface(snapshot(interfaces = listOf(ap.copy(up = false)))) {})
    }

    @Test fun wifiIpIsDecodedInAndroidLittleEndianOrderAndInvalidAddressesAreRejected() {
        assertEquals(apAddress, legacyWifiIpv4(0x012ba8c0))
        for (ip in listOf(0, 0x0100007f, 0xfb0000e0.toInt(), 0x0100fea9)) assertNull(legacyWifiIpv4(ip))
    }

    @Test fun changedLinkAddressesRejectTheSnapshotAndDisconnectedDefaultsAreNotUsed() {
        val state = idle.copy(defaultType = ConnectivityManager.TYPE_WIFI,
            defaultState = NetworkInfo.State.CONNECTED, wifiConnected = true,
            links = listOf(LegacyNetworkLink(ConnectivityManager.TYPE_WIFI, true, "wlan1", listOf(stationAddress))))
        val changed = state.copy(links = state.links.map { it.copy(addresses = listOf(apAddress)) })
        assertFalse(snapshot(state, changed, listOf(ap, station)).consistent)
        assertNull(snapshot(state.copy(defaultState = NetworkInfo.State.DISCONNECTED),
            interfaces = listOf(ap, station)).defaultInterface)
    }

    @Test fun unnamedWifiLinksUseExactLocalAddressesButUnknownSecondaryLinksStayUnknown() {
        val state = idle.copy(links = listOf(LegacyNetworkLink(ConnectivityManager.TYPE_WIFI, true,
            null, listOf(stationAddress))))
        assertEquals(setOf("wlan1"), snapshot(state, interfaces = listOf(ap, station)).wifiUpstreams)
        val unknownSecondary = state.copy(links = state.links +
            LegacyNetworkLink(ConnectivityManager.TYPE_WIFI, true, null, emptyList()))
        assertNull(snapshot(unknownSecondary, interfaces = listOf(ap, station)).wifiUpstreams)
        assertNull(selectHotspotInterface(snapshot(unknownSecondary, interfaces = listOf(ap, station))) {})
    }

    @Test fun ethernetAndCellularDefaultsDoNotAuthorizeOrdinaryInterfacesAsHotspots() {
        val ethernet = ap.copy(name = "eth0", wireless = false)
        val state = idle.copy(defaultType = ConnectivityManager.TYPE_ETHERNET,
            defaultState = NetworkInfo.State.CONNECTED,
            links = listOf(LegacyNetworkLink(ConnectivityManager.TYPE_ETHERNET, true, "eth0", listOf(apAddress))))
        assertEquals("eth0", snapshot(state, interfaces = listOf(ethernet)).defaultInterface)
        assertNull(selectHotspotInterface(snapshot(state, interfaces = listOf(ethernet))) {})
        assertEquals("eth0", selectHotspotInterface(snapshot(state, interfaces = listOf(ethernet),
            owned = setOf("eth0"))) {}?.name)
    }
}
