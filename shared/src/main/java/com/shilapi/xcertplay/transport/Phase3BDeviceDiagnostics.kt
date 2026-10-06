package com.shilapi.xcertplay.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class Phase3BBluetoothDevice(val name: String?, val address: String, val bonded: Boolean, val uuids: List<String>) {
    fun description(): String = "${name ?: "name unavailable"} ($address) bonded=$bonded UUIDs=${uuids.ifEmpty { listOf("unknown (cached SDP only)") }}"
}

/** Inert until an explicit action; no controller or authentication dependency. */
class Phase3BDeviceDiagnostics(context: Context, private val onUpdate: (String) -> Unit) : Closeable {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val socket = AtomicReference<BluetoothSocket?>()
    private val busy = AtomicBoolean()
    private var socketConnected = false
    private val sent = AtomicLong()
    private val received = AtomicLong()
    private val bonded = linkedMapOf<String, Phase3BBluetoothDevice>()
    private val discovered = linkedMapOf<String, Phase3BBluetoothDevice>()
    private var selected: Phase3BBluetoothDevice? = null
    private var adapterReport = "Adapter: not sampled"
    private var serviceReport = "not sampled"
    private var adapterPresent: Boolean? = null
    private var androidEnabled: Boolean? = null
    private var localNameReport = "not sampled"
    private var localAddressReport = "not sampled"
    private var bondedVisible = false
    private var scanReport = "not started"
    private var connectReport = "not started"
    private var framingReport = "not started"
    private var disconnect = "none"
    private var scanOwned = false
    private var scanGeneration = 0
    private var receiverRegistered = false
    private val failures = java.util.ArrayDeque<String>()
    @Volatile private var closed = false
    @Volatile private var report = "Phase 3B Bluetooth diagnostics: not sampled"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            submit("Bluetooth broadcast") {
                safely("Bluetooth broadcast", onFailure = { error ->
                    stopScan()
                    scanReport = "FAIL ${error.javaClass.simpleName}: ${error.message}"
                }) {
                    synchronized(this@Phase3BDeviceDiagnostics) {
                        if (!receiverRegistered || !scanOwned) return@synchronized
                        when (intent.action) {
                            BluetoothDevice.ACTION_FOUND -> if (scanOwned) {
                                @Suppress("DEPRECATION")
                                val device = if (Build.VERSION.SDK_INT >= 33) {
                                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                } else intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                device?.let { discovered[it.address] = describe(it) }
                            }
                            BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                                if (scanOwned) scanReport = "running"
                                log("scan lifecycle started owned=$scanOwned")
                            }
                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                                if (scanOwned) {
                                    scanOwned = false
                                    scanReport = "finished; discovered=${discovered.size}"
                                    unregisterScanReceiver()
                                }
                                log("scan lifecycle finished")
                            }
                        }
                        publish()
                    }
                }
            }
        }
    }

    private fun registerScanReceiver() {
        check(!receiverRegistered) { "Scan receiver already registered" }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Bluetooth broadcasts originate in a privileged process distinct from system UID.
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun unregisterScanReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        safely("Scan receiver cleanup") { app.unregisterReceiver(receiver) }
    }

    fun diagnosticReport(): String = report

    @Synchronized fun candidates(): List<Phase3BBluetoothDevice> = bonded.values.toList()

    @Synchronized fun select(address: String) {
        if (busy.get()) {
            disconnect = "FAIL cannot change candidate while test is running"
            log(disconnect)
        } else {
            selected = bonded[address]
            if (selected == null) {
                disconnect = "FAIL selected device is no longer bonded"
                log(disconnect)
            } else log("candidate selection ${selected?.description()}")
        }
        publish()
    }

    fun refresh(onComplete: () -> Unit = {}) {
        submit("Adapter refresh") {
            refreshSafely()
            if (!closed) onComplete()
        }
    }

    private fun refreshSafely() {
        if (closed) return
        safely("Adapter refresh", onFailure = { error ->
            updateAdapterReport()
            adapterReport += "\nAdapter sample FAIL ${error.javaClass.simpleName}: ${error.message}"
            bonded.clear()
            bondedVisible = false
            selected = null
        }) {
            synchronized(this) { refreshAdapter(); publish() }
        }
    }

    private fun refreshAdapter() {
        val previous = adapterReport
        adapterPresent = null
        androidEnabled = null
        localNameReport = "unknown (not read)"
        localAddressReport = "unknown (not read)"
        bondedVisible = false
        serviceReport = "unknown (lookup not completed)"
        val manager = BluetoothCompatibility.manager(app)
        serviceReport = if (manager == null) "no (no Android BluetoothManager service)" else "yes"
        log("Android Bluetooth service available: $serviceReport")
        val adapter = manager?.adapter
        adapterPresent = adapter != null
        val missing = BluetoothCompatibility.missingPermissions(app, scan = false)
        if (adapter == null) {
            localNameReport = "unavailable (no Android adapter)"
            localAddressReport = "unavailable (no Android adapter)"
            bonded.clear()
            selected = null
            log("Adapter lookup FAIL Android Bluetooth adapter absent; vehicle Bluetooth state unknown")
        } else if (missing.isNotEmpty()) {
            localNameReport = "unavailable; permission required=$missing"
            localAddressReport = "unavailable; permission required=$missing"
            bonded.clear()
            selected = null
            log("Adapter refresh FAIL permission required=$missing")
        } else {
            androidEnabled = adapter.isEnabled
            localNameReport = adapter.name ?: "unavailable (Android returned null)"
            localAddressReport = "${adapter.address ?: "unavailable (Android returned null)"} (may be Android placeholder)"
            bonded.clear()
            val devices = checkNotNull(adapter.bondedDevices) { "Android returned null for bonded devices" }
            devices.sortedBy { it.address }.forEach { bonded[it.address] = describe(it) }
            bondedVisible = true
            selected = selected?.let { bonded[it.address] }
        }
        updateAdapterReport()
        if (adapterReport != previous) log("adapter state $adapterReport")
    }

    private fun updateAdapterReport() {
        adapterReport = buildString {
            appendLine("Android Bluetooth adapter present: ${adapterPresent?.let { if (it) "yes" else "no" } ?: "unknown (lookup failed)"}")
            appendLine("Android adapter enabled: ${androidEnabled?.let { if (it) "yes" else "no" } ?: "unknown/unavailable (not successfully read)"}")
            appendLine("Android adapter local name: $localNameReport")
            append("Android adapter address: $localAddressReport")
        }
    }

    private fun describe(device: BluetoothDevice) = Phase3BBluetoothDevice(
        device.name, device.address, device.bondState == BluetoothDevice.BOND_BONDED,
        device.uuids?.map { it.uuid.toString() }.orEmpty(),
    )

    fun scan() {
        if (closed) return
        if (busy.get()) {
            recordFailure("Scan", IllegalStateException("RFCOMM test is running; scan not started"))
            return
        }
        submit("Scan") {
            safely("Scan", onFailure = { error ->
                stopScan()
                scanReport = "FAIL ${error.javaClass.simpleName}: ${error.message}"
            }) {
                synchronized(this) {
                    check(!busy.get()) { "RFCOMM test is running" }
                    check(BluetoothCompatibility.missingPermissions(app, scan = true).isEmpty()) { "Scan permission missing" }
                    val adapter = checkNotNull(BluetoothCompatibility.adapter(app)) { "Bluetooth adapter absent" }
                    check(adapter.isEnabled) { "Bluetooth adapter disabled; enable in car settings" }
                    check(!adapter.isDiscovering) { "Discovery already running; wait for it to finish" }
                    discovered.clear()
                    registerScanReceiver()
                    scanOwned = true
                    check(adapter.startDiscovery()) { "startDiscovery returned false; firmware may not support discovery" }
                    val generation = ++scanGeneration
                    scanReport = "requested (bounded to 15 seconds)"
                    log("scan lifecycle requested")
                    publish()
                    worker.schedule({
                        synchronized(this) {
                            if (!closed && scanOwned && generation == scanGeneration) {
                                stopScan()
                                scanReport = "finished (15s limit); discovered=${discovered.size}"
                                log("scan lifecycle $scanReport")
                                publish()
                            }
                        }
                    }, 15, TimeUnit.SECONDS)
                }
            }
        }
    }

    fun testRfcomm() {
        if (closed) return
        if (!busy.compareAndSet(false, true)) {
            recordFailure("RFCOMM test", IllegalStateException("Test already running; duplicate request ignored"))
            return
        }
        submit("RFCOMM test") {
            var stream: BluetoothRfcommDuplexStream? = null
            val timedOut = AtomicBoolean()
            try {
                val candidate = synchronized(this) {
                    sent.set(0); received.set(0)
                    disconnect = "none"
                    framingReport = "not started"
                    connectReport = "checking adapter/candidate"
                    publish()
                    check(BluetoothCompatibility.missingPermissions(app, scan = false).isEmpty()) { "Bluetooth connect permission missing" }
                    refreshAdapter()
                    checkNotNull(selected) { "Select a bonded iPhone candidate first" }
                }
                val adapter = checkNotNull(BluetoothCompatibility.adapter(app)) { "Bluetooth adapter absent" }
                check(adapter.isEnabled) { "Bluetooth adapter disabled" }
                synchronized(this) { stopScan() }
                if (BluetoothCompatibility.missingPermissions(app, scan = true).isEmpty()) {
                    if (adapter.isDiscovering) {
                        check(adapter.cancelDiscovery()) { "cancelDiscovery returned false before RFCOMM connect" }
                        log("scan lifecycle cancelled before connect")
                    }
                } else log("Discovery state/cancel skipped without SCAN permission; connection may be slower")
                val device = adapter.bondedDevices.firstOrNull { it.address == candidate.address }
                    ?: throw IOException("Selected candidate is no longer bonded")
                val connection = device.createRfcommSocketToServiceRecord(BluetoothCompatibility.IAP2_SERVICE_UUID)
                socket.set(connection)
                if (closed) throw IOException("Screen paused before connect")
                synchronized(this) {
                    socketConnected = false
                    connectReport = "connecting ${candidate.address}; secure RFCOMM UUID=${BluetoothCompatibility.IAP2_SERVICE_UUID}"
                    log("RFCOMM connect $connectReport")
                    publish()
                }
                val watchdog = timer.schedule({
                    timedOut.set(true)
                    log("RFCOMM connect FAIL timeout (12s)")
                    closeSocket(connection)
                }, 12, TimeUnit.SECONDS)
                try { connection.connect() } finally { watchdog.cancel(false) }
                if (timedOut.get()) throw IOException("RFCOMM connect timeout (12s)")
                check(connection.isConnected) { "connect returned without connected socket" }
                stream = BluetoothRfcommDuplexStream(connection)
                val activeStream = stream
                synchronized(this) {
                    socketConnected = true
                    connectReport = "PASS secure RFCOMM connected"
                    log("RFCOMM connected UUID=${BluetoothCompatibility.IAP2_SERVICE_UUID}")
                    publish()
                }
                val counted = object : BlockingDuplexByteStream {
                    override fun send(data: ByteArray) {
                        activeStream.send(data)
                        sent.addAndGet(data.size.toLong())
                        synchronized(this@Phase3BDeviceDiagnostics) { logCounts(); publish() }
                    }
                    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
                        activeStream.recv(maxBytes, timeoutMillis).also { bytes ->
                            if (bytes != null && bytes.isNotEmpty()) {
                                received.addAndGet(bytes.size.toLong())
                                synchronized(this@Phase3BDeviceDiagnostics) { logCounts(); publish() }
                            }
                        }
                    override fun close() = activeStream.close()
                }
                val framingWatchdog = timer.schedule({
                    log("iAP2 framing FAIL 10s window exceeded; closing socket to unblock I/O")
                    closeSocket(connection)
                }, 10_500, TimeUnit.MILLISECONDS)
                val outcome = try {
                    Iap2PreAuthProbe().run(counted, cancelled = { closed }, onEvent = {
                        synchronized(this) { framingReport = it; log(it); publish() }
                    })
                } finally { framingWatchdog.cancel(false) }
                synchronized(this) {
                    framingReport = "linkReady=${outcome.linkReady}; firstControl=${outcome.firstMessageId?.let { "0x${it.toString(16)}" } ?: "none"}; ${outcome.reason}"
                    disconnect = if (outcome.linkReady) "Stopped at bounded pre-auth test boundary"
                        else "FAIL ${outcome.reason}"
                    log("RFCOMM disconnect $disconnect; $framingReport")
                }
            } catch (error: Exception) {
                synchronized(this) {
                    if (!connectReport.startsWith("PASS")) connectReport = "FAIL ${error.javaClass.simpleName}: ${error.message}"
                    disconnect = if (timedOut.get()) "FAIL RFCOMM connect timeout (12s)" else "FAIL ${error.javaClass.simpleName}: ${error.message}"
                    log("RFCOMM disconnect $disconnect")
                }
            } catch (error: LinkageError) {
                synchronized(this) {
                    connectReport = "FAIL ${error.javaClass.simpleName}: ${error.message}"
                    disconnect = connectReport
                    recordFailure("RFCOMM vendor API", error)
                }
            } finally {
                safely("RFCOMM stream cleanup") { stream?.close() }
                socket.getAndSet(null)?.let(::closeSocket)
                busy.set(false)
                synchronized(this) { socketConnected = false; logCounts(); publish() }
            }
        }
    }

    private fun logCounts() { log("byte counts sent=${sent.get()} received=${received.get()}") }

    private fun stopScan() {
        val wasOwned = scanOwned
        scanOwned = false
        scanGeneration++
        try {
            if (wasOwned) safely("Scan cleanup") {
                val adapter = BluetoothCompatibility.adapter(app)
                if (adapter?.isDiscovering == true && !adapter.cancelDiscovery()) log("scan cleanup FAIL cancelDiscovery returned false")
                scanReport = "stopped; discovered=${discovered.size}"
                log("scan lifecycle stopped")
            }
        } finally { unregisterScanReceiver() }
    }

    private fun closeSocket(connection: BluetoothSocket) {
        safely("RFCOMM socket cleanup") { connection.close() }
    }

    private fun submit(stage: String, action: () -> Unit) {
        if (closed) return
        try {
            worker.execute { if (!closed) action() }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            if (!closed) recordFailure(stage, error)
        }
    }

    private fun safely(stage: String, onFailure: (Throwable) -> Unit = {}, action: () -> Unit) {
        try { action() } catch (error: Exception) {
            synchronized(this) { onFailure(error); recordFailure(stage, error) }
        } catch (error: LinkageError) {
            synchronized(this) { onFailure(error); recordFailure(stage, error) }
        }
    }

    @Synchronized private fun recordFailure(stage: String, error: Throwable) {
        log("$stage FAIL ${error.javaClass.simpleName}: ${error.message}")
        publish()
    }

    @Synchronized private fun publish() {
        report = buildString {
            appendLine(adapterReport)
            appendLine("Android Bluetooth service available: $serviceReport")
            appendLine("Adapter source: Android BluetoothManager only; no getDefaultAdapter fallback; not vehicle Bluetooth state")
            appendLine("Bonded devices visible through Android APIs: ${if (!bondedVisible) "unknown/unavailable (not successfully enumerated)" else bonded.size.toString()}")
            appendLine("Vehicle Bluetooth: operator reports GEELY_BT via Geely vehicle Settings; current vehicle/MCU state is not observable here and may be active independently")
            appendLine("Vehicle/Android mismatch: ${if (androidEnabled != true) "possible if Bluetooth is active in vehicle Settings while Android is unavailable/disabled; verify manually" else "Android adapter reports enabled; vehicle integration still unverified"}")
            appendLine("Bonded devices: ${if (bondedVisible) bonded.size.toString() else "unavailable (not successfully enumerated)"}")
            bonded.values.forEach { appendLine("  ${it.description()}") }
            appendLine("Manual scan: $scanReport")
            appendLine("Scan supported: unknown (not tested; scanning is disabled in this safe-startup build)")
            appendLine("Discovered devices: ${discovered.size}")
            discovered.values.forEach { appendLine("  ${it.description()}") }
            appendLine("Selected iPhone candidate: ${selected?.description() ?: "none (explicit selection required)"}")
            appendLine("Service chosen: secure RFCOMM ${BluetoothCompatibility.IAP2_SERVICE_UUID}; no channel-number/private API fallback")
            appendLine("RFCOMM result: $connectReport")
            appendLine("Socket connected: ${if (socketConnected) "yes" else "no"}; test running=${busy.get()}")
            appendLine("Bytes sent=${sent.get()}; received=${received.get()} (consumed by pre-auth probe)")
            appendLine("iAP2: $framingReport")
            appendLine("Disconnect reason/error: $disconnect")
            appendLine("Recent failures: ${failures.joinToString("; ").ifEmpty { "none" }}")
            appendLine("CarPlay, identification responses, accessory/MFi authentication, hotspot transitions, media, USB and vehicle integration: disabled")
        }
        if (!closed) onUpdate(report)
    }

    @Synchronized private fun log(message: String) {
        Log.i(TAG, message)
        if (message.contains("FAIL")) {
            if (failures.size >= 20) failures.removeFirst()
            failures.addLast(message)
        }
    }

    override fun close() {
        closed = true
        socket.getAndSet(null)?.let(::closeSocket)
        worker.shutdownNow()
        timer.shutdownNow()
        synchronized(this) {
            stopScan()
            socketConnected = false
            if (busy.get()) disconnect = "Screen paused; socket closed to cancel test"
            publish()
        }
    }

    companion object { const val TAG = "DiPlayPhase3BDevice" }
}
