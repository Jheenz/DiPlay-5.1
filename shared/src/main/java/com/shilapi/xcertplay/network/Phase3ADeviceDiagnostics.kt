package com.shilapi.xcertplay.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun phase3AAddressSignature(snapshot: HotspotNetworkSnapshot): List<String> =
    snapshot.interfaces.sortedBy { it.name }.map {
        "${it.name}:${it.index}:${it.up}:${it.addresses.map { address -> address.hostAddress }.sortedBy { address -> address }}"
    }

/** No controller, identity, control probe, socket accept loop, or network configuration is used. */
class Phase3ADeviceDiagnostics(context: Context, private val onUpdate: (String) -> Unit) : Closeable {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val reader = ManualHotspotInterfaces(app, ::log)
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifi = app.wifiService()
    private var previousAddresses: List<String>? = null
    private var addressChanges = 0
    private var networkReport = "Network: not sampled"
    private var readiness = "not checked"
    private val failures = java.util.ArrayDeque<String>()
    private var result = "Test: not started"
    private var registration = "not started"
    private var discovery = "not started"
    private var peer = "none"
    private var selfObserved = false
    private var resolvePending = false
    private var testSelection: HotspotSelection? = null
    private var socket: ServerSocket? = null
    private var multicast: android.net.wifi.WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var generation = 0
    @Volatile private var closed = false
    @Volatile private var report = "Phase 3A network diagnostics: not sampled"

    init {
        worker.scheduleWithFixedDelay({ sampleSafely() }, 0, 2, TimeUnit.SECONDS)
    }

    fun diagnosticReport(): String = report

    fun refresh() {
        if (!closed) worker.execute { sampleSafely() }
    }

    fun startNetworkTest() {
        if (!closed) worker.execute {
            synchronized(this) {
                stopNsd()
                registration = "not started"
                discovery = "not started"
                peer = "none"
                selfObserved = false
                result = "Test: checking stable manual-hotspot readiness"
                publish()
            }
            try {
                sampleNetwork()
                val selected = ManualHotspotReadiness(
                    sample = { reader.sample() },
                    cancelled = { closed },
                    pause = { Thread.sleep(it) },
                    log = ::log,
                ).await(2500)
                synchronized(this) {
                    if (!closed) {
                        testSelection = selected
                        result = "Test: readiness PASS; NSD test running (20 seconds)"
                        log("readiness PASS iface=${selected.name} address=${selected.address.hostAddress}")
                        startNsd(selected)
                    }
                }
            } catch (error: Exception) {
                synchronized(this) {
                    if (closed) return@execute
                    result = "Test: FAIL ${error.javaClass.simpleName}: ${error.message}"
                    log(result)
                    stopNsd()
                    publish()
                }
            }
        }
    }

    private fun sampleSafely() {
        if (closed) return
        try {
            sampleNetwork()
        } catch (error: Exception) {
            synchronized(this) {
                networkReport = "Network sample FAIL ${error.javaClass.simpleName}: ${error.message}"
                log(networkReport)
                if (socket != null) {
                    result = "Test: FAIL network sampling unavailable"
                    stopNsd()
                }
                publish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sampleNetwork() {
        val snapshot = reader.sample()
        val evidence = mutableListOf<String>()
        val selected = selectHotspotInterface(snapshot, evidence::add)
        val stableReadiness = try {
            ManualHotspotReadiness(
                sample = { reader.sample() },
                cancelled = { closed },
                pause = { Thread.sleep(it) },
                log = { if (it !in evidence) evidence.add(it) },
            ).await(1000).let { stable ->
                if (selected?.sameAddress(stable) == true) "PASS (three stable samples)"
                else "FAIL interface/address changed during readiness check"
            }
        } catch (error: WirelessStartupException) {
            "FAIL ${error.message}; candidate rejection reasons listed below"
        }
        val connectivity = app.connectivityService()
        val active = connectivity?.activeNetworkInfo
        val internetCapability = connectivity?.allNetworks?.any { network ->
            connectivity.getNetworkInfo(network)?.isConnected == true &&
                connectivity.getNetworkCapabilities(network)?.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET,
                ) == true
        }
        val links = connectivity?.allNetworks?.mapNotNull { connectivity.getLinkProperties(it) }.orEmpty()
        val details = mutableListOf<String>()
        val prefixes = snapshot.interfaces.flatMap { iface ->
            NetworkInterface.getByName(iface.name)?.interfaceAddresses.orEmpty().map {
                "${iface.name} ${it.address.hostAddress}/${it.networkPrefixLength}"
            }
        }
        val routes = links.flatMap { link -> link.routes.map { "${link.interfaceName}: $it" } }.toMutableList()
        if (Build.VERSION.SDK_INT <= 22) {
            try {
                File("/proc/net/route").useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val columns = line.trim().split(Regex("\\s+"))
                        if (columns.size >= 4 && columns[1] == "00000000") {
                            val gateway = columns[2].toLong(16).toInt()
                            routes.add("${columns[0]} default gateway=${legacyWifiIpv4(gateway)?.hostAddress ?: "on-link"}")
                        }
                    }
                }
            } catch (error: IOException) {
                details.add("Kernel routes unavailable: ${error.javaClass.simpleName}: ${error.message}")
            } catch (error: SecurityException) {
                details.add("Kernel routes unavailable: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        val ssid = try {
            if (snapshot.apEnabled == true) {
                val configuration = wifi?.let {
                    it.javaClass.getMethod("getWifiApConfiguration").invoke(it) as? WifiConfiguration
                }
                configuration?.SSID ?: "unavailable (firmware hides AP SSID)"
            } else wifi?.connectionInfo?.ssid ?: "unavailable"
        } catch (error: ReflectiveOperationException) {
            details.add("AP SSID unavailable: ${error.javaClass.simpleName}")
            "unavailable"
        } catch (error: SecurityException) {
            details.add("SSID unavailable: ${error.javaClass.simpleName}")
            "unavailable"
        }
        val role = when {
            snapshot.apEnabled != false && snapshot.apInterfaces?.isNotEmpty() == true -> "AP/hotspot owner (platform evidence)"
            selected != null -> "unknown (wireless non-upstream AP candidate)"
            snapshot.wifiUpstreams?.isNotEmpty() == true -> "station"
            else -> "unknown"
        }
        val diagnosticInterface = selected?.name ?: snapshot.wifiUpstreams?.singleOrNull()
            ?: snapshot.defaultInterface
        val signature = phase3AAddressSignature(snapshot)
        synchronized(this) {
            val changed = previousAddresses != null && previousAddresses != signature
            if (changed) addressChanges++
            if (previousAddresses != signature) {
                evidence.forEach(::log)
                log("interface selection=${selected?.name ?: "none"} address selection=${selected?.address?.hostAddress ?: "none"}")
            }
            previousAddresses = signature
            if (readiness != stableReadiness) {
                readiness = stableReadiness
                evidence.forEach(::log)
                log("interface selection=${selected?.name ?: "none"} address selection=${selected?.address?.hostAddress ?: "none"}")
                log("readiness $readiness")
            }
            if (socket != null && (!readiness.startsWith("PASS") || selected == null || testSelection?.sameAddress(selected) != true)) {
                result = "Test: FAIL interface/address changed or readiness lost"
                log(result)
                stopNsd()
            }
            networkReport = buildString {
                appendLine("Sample: ${java.util.Date()}")
                appendLine("Interfaces:")
                snapshot.interfaces.forEach { appendLine("  ${it.name} index=${it.index} up=${it.up} wireless=${it.wireless} addresses=${it.addresses.map { address -> address.hostAddress }}") }
                appendLine("Selected hotspot/LAN interface: ${diagnosticInterface ?: "none"} (${if (selected != null) "manual AP candidate" else "LAN observation only; not authorized for test"})")
                appendLine("Selected address: ${selected?.address?.hostAddress ?: "none (manual readiness rejected)"}")
                appendLine("Local IPv4: ${snapshot.interfaces.firstOrNull { it.name == diagnosticInterface }?.addresses?.filterIsInstance<Inet4Address>()?.joinToString { it.hostAddress ?: "unknown" }?.ifEmpty { "unavailable" } ?: "unavailable"}")
                appendLine("Subnet/prefix: ${prefixes.joinToString("; ").ifEmpty { "unavailable" }}")
                appendLine("Gateway/routes: ${routes.joinToString("; ").ifEmpty { "none observable" }}")
                appendLine("Default interface: ${snapshot.defaultInterface ?: "none observable"}")
                appendLine("Default connectivity: ${active?.isConnected == true} (${active?.typeName ?: "none"})")
                appendLine("Connected network declares Internet capability: ${internetCapability ?: "unknown"} (not proof of Internet access)")
                appendLine("Internet: unknown (API 22 has no validated Internet capability; no external probe)")
                appendLine("AP enabled: ${snapshot.apEnabled}; role: $role")
                appendLine("SSID: $ssid")
                appendLine("Address/interface changes: $addressChanges; changed this sample=$changed")
                appendLine("Manual readiness: $readiness")
                appendLine("Readiness evidence/failure reasons:")
                if (snapshot.interfaces.isEmpty()) appendLine("  no observable local interfaces")
                evidence.forEach { appendLine("  $it") }
                details.forEach { appendLine(it); log(it) }
            }
            publish()
        }
    }

    private fun startNsd(selected: HotspotSelection) {
        val manager = checkNotNull(nsd) { "NsdManager unavailable" }
        val token = ++generation
        val name = "DiPlay-Phase3A-${System.nanoTime()}"
        socket = ServerSocket().apply { bind(InetSocketAddress(selected.address, 0)) }
        multicast = checkNotNull(wifi) { "WifiManager unavailable" }
            .createMulticastLock(TAG).apply { setReferenceCounted(false); acquire() }
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            port = checkNotNull(socket).localPort
        }
        fun update(action: () -> Unit) {
            synchronized(this) {
                if (closed || token != generation) return
                action()
                publish()
            }
        }
        val registrationCallbacks = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(service: NsdServiceInfo) = update {
                registration = "PASS name=${service.serviceName} port=${service.port}"
                log("NSD registration $registration")
            }
            override fun onRegistrationFailed(service: NsdServiceInfo, code: Int) = update {
                registration = "FAIL code=$code"; log("NSD registration $registration")
            }
            override fun onServiceUnregistered(service: NsdServiceInfo) { log("NSD unregistered") }
            override fun onUnregistrationFailed(service: NsdServiceInfo, code: Int) { log("NSD unregister FAIL code=$code") }
        }
        val discoveryCallbacks = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = update {
                discovery = "started; waiting for service"; log("NSD discovery started")
            }
            override fun onDiscoveryStopped(type: String) { log("NSD discovery stopped") }
            override fun onStartDiscoveryFailed(type: String, code: Int) = update {
                discovery = "FAIL code=$code"; log("NSD discovery $discovery")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) { log("NSD stop discovery FAIL code=$code") }
            override fun onServiceLost(service: NsdServiceInfo) = update {
                log("NSD service lost name=${service.serviceName}")
            }
            override fun onServiceFound(service: NsdServiceInfo) = update {
                discovery = "PASS service observed"
                log("NSD discovery $discovery")
                if (service.serviceName.startsWith(name)) {
                    selfObserved = true
                } else if (!resolvePending) {
                    resolvePending = true
                    peer = "pending resolution"
                    log("peer resolution started")
                    try {
                        manager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(service: NsdServiceInfo, code: Int) = update {
                                resolvePending = false
                                peer = "FAIL resolution code=$code"; log("peer resolution $peer")
                            }
                            override fun onServiceResolved(service: NsdServiceInfo) = update {
                                resolvePending = false
                                peer = if (service.host != null && service.port in 1..65535) {
                                    "${service.host.hostAddress}:${service.port} (platform-scoped; LAN ownership unverified)"
                                } else "FAIL missing address or invalid port"
                                log("peer resolution $peer")
                            }
                        })
                    } catch (error: RuntimeException) {
                        resolvePending = false
                        peer = "FAIL ${error.javaClass.simpleName}: ${error.message}"
                        log("peer resolution $peer")
                    }
                }
            }
        }
        registration = "pending"
        discovery = "pending"
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationCallbacks)
        registrationListener = registrationCallbacks
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryCallbacks)
        discoveryListener = discoveryCallbacks
        publish()
        worker.schedule({
            synchronized(this) {
                if (!closed && token == generation) {
                    if (registration == "pending") registration = "FAIL registration timeout (20s)"
                    if (!discovery.startsWith("PASS")) discovery = "FAIL no service observed within 20s; previous=$discovery"
                    if (resolvePending) {
                        peer = "FAIL peer resolution timeout (20s test window)"
                        log("peer resolution $peer")
                    }
                    result = if (registration.startsWith("PASS") && discovery.startsWith("PASS") && !peer.startsWith("FAIL")) {
                        "Test: PASS registration/discovery; selfObserved=$selfObserved; peer=$peer"
                    } else "Test: FAIL registration=$registration; discovery=$discovery; peer=$peer"
                    log(result)
                    stopNsd()
                    publish()
                }
            }
        }, 20, TimeUnit.SECONDS)
    }

    private fun stopNsd() {
        generation++
        registrationListener?.let {
            try { nsd?.unregisterService(it) } catch (error: RuntimeException) { log("NSD unregister FAIL ${error.javaClass.simpleName}: ${error.message}") }
        }
        registrationListener = null
        discoveryListener?.let {
            try { nsd?.stopServiceDiscovery(it) } catch (error: RuntimeException) { log("NSD stop discovery FAIL ${error.javaClass.simpleName}: ${error.message}") }
        }
        discoveryListener = null
        try { socket?.close() } catch (error: IOException) { log("Test socket close FAIL: ${error.message}") }
        socket = null
        multicast?.let {
            try { if (it.isHeld) it.release() } catch (error: RuntimeException) { log("Multicast release FAIL: ${error.message}") }
        }
        multicast = null
        testSelection = null
        resolvePending = false
    }

    private fun publish() {
        report = "$result\n$networkReport\nBonjour/mDNS backend: system_nsd (platform-scoped, API ${Build.VERSION.SDK_INT})\n" +
            "Test service: $SERVICE_TYPE (no CarPlay advertisement or handshake)\n" +
            "NSD registration: $registration\nNSD discovery: $discovery\nSelf advertisement observed: $selfObserved\n" +
            "Resolved peer: $peer\nMulticast lock: ${if (multicast?.isHeld == true) "held" else "not held"}\n" +
            "Recent failures:\n${failures.joinToString("\n").ifEmpty { "none" }}"
        if (!closed) onUpdate(report)
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
        synchronized(this) {
            result = if (socket != null) "Test: stopped (screen paused)" else result
            stopNsd()
            reader.close()
            publish()
        }
    }

    companion object {
        const val TAG = "DiPlayPhase3ADevice"
        const val SERVICE_TYPE = "_diplay-phase3a._tcp."
    }

    @Synchronized private fun log(message: String) {
        Log.i(TAG, message)
        if (message.contains("FAIL", ignoreCase = true) || message.contains("unavailable", ignoreCase = true)) {
            if (failures.size >= 20) failures.removeFirst()
            failures.addLast(message)
        }
    }
}
