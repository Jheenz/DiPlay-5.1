package com.shilapi.xcertplay.network

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 22, maxSdkVersion = 22)
class LegacyNetworkApi22DeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val config = AirPlayConfig(
        deviceName = "DiPlay-Phase3A-Test", deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02", sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
    )
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "phase3a-test-only")

    @Test fun deviceDiagnosticsReportUsesNsdAndClosesWithoutProjection() {
        val sampled = CountDownLatch(1)
        val diagnostics = Phase3ADeviceDiagnostics(context) { report ->
            if (report.contains("Manual readiness:")) sampled.countDown()
        }
        try {
            assertTrue("Device diagnostics sample timed out", sampled.await(8, TimeUnit.SECONDS))
            val report = diagnostics.diagnosticReport()
            assertTrue(report.contains("Bonjour/mDNS backend: system_nsd"))
            assertTrue(report.contains("NSD registration: not started"))
            assertTrue(report.contains("Multicast lock: not held"))
            assertTrue(report.contains("no CarPlay advertisement or handshake"))
            assertFalse(com.shilapi.xcertplay.orchestration.LegacyLaunchBuild.CONNECTIONS_ENABLED)
            assertFalse(com.shilapi.xcertplay.orchestration.LegacyLaunchBuild.VENDOR_INTEGRATION_ENABLED)
        } finally {
            diagnostics.close()
        }
        assertTrue(diagnostics.diagnosticReport().contains("Multicast lock: not held"))
    }

    private fun snapshot(): HotspotNetworkSnapshot =
        ManualHotspotInterfaces(context) { Log.i(TAG, it) }.use { reader ->
            var result = reader.sample()
            repeat(3) {
                if (!result.consistent) {
                    Thread.sleep(250)
                    result = reader.sample()
                }
            }
            assertTrue("Legacy sample must not fail because activeNetwork is unavailable", result.consistent)
            result.interfaces.forEach { iface ->
                Log.i(TAG, "iface=${iface.name} up=${iface.up} index=${iface.index} addresses=${iface.addresses}")
            }
            result
        }

    private fun localInterface(): HotspotInterfaceSnapshot = snapshot().interfaces.first {
        it.up && it.index > 0 && existingWifiHostAddresses(it.addresses, it.index).any { address -> address is Inet4Address }
    }

    @Test fun enumeratesRealInterfacesAndBindsSamePortListenersWithoutCarPlay() {
        assertEquals(22, Build.VERSION.SDK_INT)
        assertNotNull(context.connectivityService())
        assertNotNull(context.wifiService())
        CarHotspotStatus.isEnabled(context)
        ManualHotspotManager(context, "Phase3A", "", ManualHotspotBand.AUTO, 0,
            ManualHotspotSecurity.OPEN).close()
        ExistingWifiManager(context, "Phase3A", "").close()
        val iface = localInterface()
        val addresses = existingWifiHostAddresses(iface.addresses, iface.index)
        val listeners = AirPlayPortSelector.bindAll(addresses, 0)
        try {
            assertEquals(1, listeners.map { it.localPort }.distinct().size)
            listeners.zip(addresses).forEach { (server, address) ->
                server.soTimeout = 3000
                Socket().use { client ->
                    client.connect(InetSocketAddress(address, server.localPort), 3000)
                    client.getOutputStream().write(42)
                    server.accept().use { peer -> assertEquals(42, peer.getInputStream().read()) }
                }
                Log.i(TAG, "socket roundtrip iface=${iface.name} address=$address port=${server.localPort}")
            }
        } finally {
            listeners.forEach { it.close() }
        }
    }

    @Test fun legacyWifiCallbackRegistersWithoutRequestingOrChangingNetwork() {
        val connectivity = context.connectivityService()!!
        val callback = object : ConnectivityManager.NetworkCallback() {}
        connectivity.registerNetworkCallback(existingWifiNetworkRequest(), callback)
        connectivity.unregisterNetworkCallback(callback)
    }

    @Test fun readinessAcceptsStableInjectedApButRejectsStationAndChangedSamples() {
        val iface = localInterface().copy(name = "wlan0", wireless = true)
        val idle = LegacyNetworkState(null, null, false, null, emptyList())
        val value = legacyNetworkSnapshot(idle, idle, listOf(iface), setOf(iface.name), true)
        var time = 0L
        val selected = ManualHotspotReadiness({ value }, { false }, { time += it }, { time }).await(1500)
        assertEquals(iface.name, selected.name)
        assertEquals(500L, time)
        assertNull(selectHotspotInterface(value.copy(consistent = false)) {})
        assertNull(selectHotspotInterface(value.copy(apEnabled = false)) {})
        assertNull(selectHotspotInterface(value.copy(apInterfaces = null,
            wifiUpstreams = setOf(iface.name), defaultInterface = iface.name)) {})
    }

    @Test fun interfaceMdnsUsesSystemNsdFallbackOnApi22WithoutControlProbe() {
        val address = localInterface().addresses.filterIsInstance<Inet4Address>().first()
        val nsd = context.getSystemService(android.content.Context.NSD_SERVICE) as NsdManager
        val name = "DiPlay-Phase3A-${System.nanoTime()}"
        val advertised = CountDownLatch(1)
        val discoveryError = AtomicReference<String>()
        val observer = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName.startsWith(name)) advertised.countDown()
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                discoveryError.set("NSD advertisement observer failed code=$code")
                advertised.countDown()
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {
                Log.e(TAG, "NSD advertisement observer cleanup failed code=$code")
            }
        }
        nsd.discoverServices("_airplay._tcp.", NsdManager.PROTOCOL_DNS_SD, observer)
        try {
            AirPlayPortSelector.bind(address, 0).use { server ->
                CarPlayBonjour(context, config.copy(deviceName = name, port = server.localPort), identity, address.hostAddress,
                    useInterfaceMdns = true, probeControlService = false).use { bonjour ->
                    bonjour.start()
                    assertTrue(bonjour.diagnosticSnapshot().contains("mdnsFamilies=system_nsd"))
                    assertTrue("Bonjour advertisement was not discovered", advertised.await(15, TimeUnit.SECONDS))
                    assertNull(discoveryError.get())
                    assertTrue(bonjour.diagnosticSnapshot().contains("connectProbes=0"))
                    Log.i(TAG, "NSD fallback advertisement observed")
                }
            }
        } finally {
            nsd.stopServiceDiscovery(observer)
        }
    }

    @Test fun nsdAdvertisesDiscoversAndResolvesLocalPeerWithoutHandshake() {
        val address = localInterface().addresses.filterIsInstance<Inet4Address>().first()
        val nsd = context.getSystemService(android.content.Context.NSD_SERVICE) as NsdManager
        val registered = CountDownLatch(1)
        val registrationError = AtomicReference<String>()
        val resolved = CountDownLatch(1)
        val endpoint = AtomicReference<CarPlayBonjourEndpoint>()
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { registered.countDown() }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                registrationError.set("NSD registration failed code=$code")
                registered.countDown()
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "NSD peer cleanup failed code=$code")
            }
        }
        AirPlayPortSelector.bind(address, 0).use { server ->
            val peer = NsdServiceInfo().apply {
                serviceName = "DiPlay-Phase3A-Peer"
                serviceType = "_carplay-ctrl._tcp."
                port = server.localPort
                setAttribute("id", "phase3a-peer")
            }
            nsd.registerService(peer, NsdManager.PROTOCOL_DNS_SD, listener)
            try {
                assertTrue("NSD registration timed out", registered.await(15, TimeUnit.SECONDS))
                assertNull(registrationError.get())
                CarPlayBonjour(context, config.copy(port = server.localPort), identity, address.hostAddress,
                    onEvent = { event ->
                        if (event is CarPlayBonjourEvent.Resolved && event.endpoint.serviceName.startsWith(peer.serviceName)) {
                            endpoint.set(event.endpoint)
                            resolved.countDown()
                        }
                    }, probeControlService = false).use { bonjour ->
                    bonjour.start()
                    assertTrue("Local NSD peer resolution timed out", resolved.await(25, TimeUnit.SECONDS))
                    assertEquals(server.localPort, endpoint.get().port)
                    assertFalse(endpoint.get().host.isBlank())
                    // Android 5.1's resolver can omit TXT data; endpoint resolution must still work.
                    assertTrue(endpoint.get().bluetoothId == null || endpoint.get().bluetoothId == "phase3a-peer")
                    assertTrue(bonjour.diagnosticSnapshot().contains("connectProbes=0"))
                    Log.i(TAG, "NSD local peer resolved port=${endpoint.get().port}")
                }
            } finally {
                nsd.unregisterService(listener)
            }
        }
    }

    private companion object { const val TAG = "DiPlayPhase3A" }
}
