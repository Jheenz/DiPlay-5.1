package com.shilapi.xcertplay

import java.io.Closeable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

interface QDriveTransitionConnection : Closeable {
    fun configuration(report: (String) -> Unit): Int
    fun valeria(report: (String) -> Unit, checkActive: () -> Unit): Boolean?
    fun sendAuditedRequest(): Int
}

data class QDriveUsbEvent(val action: String, val name: String, val vendorId: Int, val productId: Int)

interface QDriveTransitionAccess {
    fun devices(): List<PassiveUsbDevice>
    fun open(device: PassiveUsbDevice): QDriveTransitionConnection
    fun observe(onEvent: (QDriveUsbEvent) -> Unit): Closeable
}

interface QDriveTransitionClock {
    fun elapsedMillis(): Long
    fun timestamp(): String
    fun waitMillis(milliseconds: Long)
}

internal object SystemQDriveTransitionClock : QDriveTransitionClock {
    override fun elapsedMillis() = System.nanoTime() / 1_000_000
    override fun timestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
    override fun waitMillis(milliseconds: Long) = Thread.sleep(milliseconds)
}

data class QDriveTransitionPreflight(val device: PassiveUsbDevice?, val report: String)

private data class QDriveTransitionInspection(
    val valeria: Boolean?,
    val rawFingerprint: String?,
    val strings: Map<String, String>,
)

class QDriveVendorTransitionDiagnostic(
    private val access: QDriveTransitionAccess,
    private val clock: QDriveTransitionClock = SystemQDriveTransitionClock,
) {
    private val cancelled = AtomicBoolean(false)
    private val attempted = AtomicBoolean(false)

    fun cancel() { cancelled.set(true) }

    fun preflight(): QDriveTransitionPreflight {
        val report = StringBuilder("$TITLE\n$SAFETY\nRead-only preflight; request not sent.\n")
        var ready: PassiveUsbDevice? = null
        try {
            val device = initialDevice()
            describe(listOf(device), report)
            withConnection(device, report) { connection ->
                verifyInitial(connection, report)
            }
            checkActive()
            check(initialDevice() == device) { "Device changed during read-only preflight" }
            ready = device
            report.appendLine("PREFLIGHT PASS — manual confirmation required; connection closed. State will be reverified before sending.")
        } catch (error: Exception) {
            failure(report, "preflight", error)
            report.appendLine("PREFLIGHT FAILED — REQUEST NOT SENT")
        }
        return QDriveTransitionPreflight(ready, report.toString())
    }

    fun run(expected: PassiveUsbDevice): String {
        val report = StringBuilder("$TITLE\n$SAFETY\n")
        val events = ConcurrentLinkedQueue<Triple<String, QDriveUsbEvent, Boolean>>()
        var sent = false
        var changed = false
        var inspectionComplete = false
        var observationFailed = false
        var originalClosed = false
        var original: PassiveUsbDevice? = null
        var initialInspection: QDriveTransitionInspection? = null
        var observation: Closeable? = null
        try {
            check(!attempted.get()) { "One-shot diagnostic already attempted; no automatic retry" }
            val device = initialDevice()
            check(device == expected) { "Device state changed since preflight; run read-only preflight again" }
            original = device
            describe(listOf(device), report)
            observation = access.observe { events.add(Triple(clock.timestamp(), it, attempted.get())) }
            withConnection(device, report) { connection ->
                initialInspection = verifyInitial(connection, report)
                check(initialDevice() == device) { "Device inventory changed during preflight" }
                checkActive()
                check(attempted.compareAndSet(false, true)) { "Request already attempted" }
                sent = true
                report.appendLine("Request timestamp=${clock.timestamp()}")
                report.appendLine("controlTransfer(0x40, 0x52, 0, 2, null, 0, $REQUEST_TIMEOUT_MS)")
                report.appendLine("Only intentional request difference from QDrive: bounded timeout ${REQUEST_TIMEOUT_MS}ms instead of native 0 (unbounded). No retry.")
                val start = clock.elapsedMillis()
                try {
                    report.appendLine("Vendor request returned=${connection.sendAuditedRequest()}")
                } catch (error: Exception) {
                    failure(report, "single vendor request (may invalidate original connection)", error)
                } finally {
                    report.appendLine("Request elapsedMs=${clock.elapsedMillis() - start}")
                    report.appendLine("Request completion timestamp=${clock.timestamp()}")
                }
            }
            originalClosed = true
        } catch (error: Exception) {
            failure(report, if (sent) "original connection cleanup" else "preflight / observer setup", error)
            if (sent) observationFailed = true
        }

        if (sent && original != null) {
            try {
                val baseline = listOf(original)
                var previous: List<PassiveUsbDevice>? = null
                val deadline = clock.elapsedMillis() + OBSERVATION_MS
                report.appendLine("Bounded passive observation ${OBSERVATION_MS}ms / poll ${POLL_MS}ms; original connection no longer used.")
                do {
                    checkActive()
                    val devices = access.devices()
                    if (devices != previous) {
                        report.appendLine("Inventory timestamp=${clock.timestamp()}")
                        describe(devices, report)
                        previous = devices
                    }
                    if (devices.none { it.name == original.name && it.vendorId == original.vendorId && it.productId == original.productId }) {
                        report.appendLine("Original device disappearance observed=true")
                        changed = true
                    }
                    if (appleState(devices) != appleState(baseline)) changed = true
                    val remaining = deadline - clock.elapsedMillis()
                    if (remaining <= 0) break
                    clock.waitMillis(minOf(POLL_MS, remaining))
                } while (true)
                val finalDevices = access.devices()
                report.appendLine("Final passive inventory timestamp=${clock.timestamp()}")
                describe(finalDevices, report)
                if (appleState(finalDevices) != appleState(baseline)) changed = true
                val apples = finalDevices.filter { it.vendorId == APPLE_VID }
                check(apples.size == 1) { "Post-transition Apple device count=${apples.size}; no device guessed" }
                val after = apples.single()
                if (!after.hasPermission) {
                    report.appendLine("POST-TRANSITION DEVICE DETECTED — PERMISSION NOT GRANTED")
                    report.appendLine("No USB permission requested; passive metadata preserved.")
                } else if (!originalClosed) {
                    report.appendLine("Post-transition open skipped: original connection cleanup not confirmed; passive metadata retained.")
                } else {
                    withConnection(after, report) { connection ->
                        val value = connection.configuration { report.appendLine(it) }
                        report.appendLine("Post-transition active configuration=$value name=${after.configurations.singleOrNull { it.id == value }?.name ?: "unavailable"}")
                        if (value != 1) changed = true
                        val active = after.configurations.filter { it.id == value }
                        report.appendLine("Active configuration descriptor matches=${active.size}; no value inferred from array order.")
                        if (active.size == 1) {
                            val interfaces = active.single().interfaces
                            report.appendLine("Active configuration USBMUX descriptors=${interfaces.count { it.interfaceClass == 255 && it.subclass == 254 && it.protocol == 2 }} Apple USB Ethernet descriptors=${interfaces.count { it.interfaceClass == 255 && it.subclass == 253 && it.protocol == 1 }} (metadata only; none claimed/activated)")
                        }
                        check(value == 0 || active.size == 1) { "Active configuration cannot be correlated with fresh descriptors" }
                        val inspection = inspectValeria(connection, report)
                        val result = inspection.valeria
                        report.appendLine("Post-transition Valeria=${when (result) { true -> "TRUE"; false -> "FALSE"; null -> "UNAVAILABLE" }}; neither branch executed.")
                        if (result == true) changed = true
                        val baselineInspection = initialInspection
                        val rawChanged = baselineInspection?.rawFingerprint != null &&
                            inspection.rawFingerprint != null &&
                            baselineInspection.rawFingerprint != inspection.rawFingerprint
                        val stringsChanged = baselineInspection?.strings?.any { (entry, text) ->
                            inspection.strings[entry]?.let { it != text } == true
                        } == true
                        if (rawChanged || stringsChanged) {
                            changed = true
                            report.appendLine("Raw descriptor / checked interface-string evidence changed=true")
                        }
                        check(result != null) { "Post-transition discriminator unavailable" }
                    }
                    check(access.devices().filter { it.vendorId == APPLE_VID } == apples) {
                        "Post-transition device changed during readback; stale result discarded"
                    }
                    inspectionComplete = true
                }
            } catch (error: Exception) {
                observationFailed = true
                failure(report, "observation / post-transition read-only inspection", error)
            }
        }
        try {
            observation?.close()
            if (observation != null) report.appendLine("cleanup USB event observer=PASS")
        } catch (error: Exception) {
            observationFailed = true
            failure(report, "USB event observer cleanup", error)
        }
        events.forEach { (time, event, afterAttempt) ->
            report.appendLine("USB event timestamp=$time action=${event.action} name=${event.name} VID=${hex(event.vendorId)} PID=${hex(event.productId)} afterRequestAttempt=$afterAttempt")
            if (afterAttempt && event.vendorId == APPLE_VID && event.action in listOf("ATTACH", "DETACH")) changed = true
            if (event.action == "ERROR") observationFailed = true
        }
        report.appendLine("Vendor request attempted=$sent; USB state change observed=$changed; post-inspection complete=$inspectionComplete")
        report.appendLine(when {
            !sent -> "PREFLIGHT FAILED — REQUEST NOT SENT"
            observationFailed || (changed && !inspectionComplete) -> "QDRIVE VENDOR TRANSITION INCONCLUSIVE"
            changed -> "QDRIVE VENDOR TRANSITION OBSERVED"
            else -> "QDRIVE VENDOR REQUEST SENT — NO TRANSITION OBSERVED"
        })
        report.appendLine("No PASS inferred from request return. STOP after Phase 3D.2E; no configuration setter, claim, alternate change, bulk or transport/authentication startup.")
        return report.toString()
    }

    private fun initialDevice(): PassiveUsbDevice {
        checkActive()
        val apples = access.devices().filter { it.vendorId == APPLE_VID }
        check(apples.size == 1) { "Exactly one appropriate Apple device required; count=${apples.size}" }
        return apples.single().also {
            check(it.productId == INITIAL_PID) { "Initial PID must be 0x12A8, got ${hex(it.productId)}" }
            check(it.hasPermission) { "Existing Android USB permission required; not requested" }
        }
    }

    private fun verifyInitial(connection: QDriveTransitionConnection, report: StringBuilder): QDriveTransitionInspection {
        checkActive()
        val value = connection.configuration { report.appendLine(it) }
        report.appendLine("Preflight active configuration=$value (required 1/PTP)")
        check(value == 1) { "Initial configuration != 1" }
        val inspection = inspectValeria(connection, report)
        check(inspection.valeria == false) { "Non-Valeria vendor-request path not established" }
        // String reads precede mutation; recheck active configuration on this same handle.
        check(connection.configuration { report.appendLine(it) } == 1) { "Configuration changed during discriminator" }
        return inspection
    }

    private fun inspectValeria(connection: QDriveTransitionConnection, report: StringBuilder): QDriveTransitionInspection {
        val deadline = clock.elapsedMillis() + INSPECTION_MS
        var fingerprint: String? = null
        var entry: String? = null
        val strings = mutableMapOf<String, String>()
        val result = connection.valeria({
            report.appendLine(it)
            when {
                it.startsWith("Raw descriptor SHA256=") -> fingerprint = it
                it.startsWith("Checked configuration ") -> entry = it
                it.startsWith("Retrieved interface ASCII string=") -> entry?.let { key -> strings[key] = it }
            }
        }) {
            checkActive()
            check(clock.elapsedMillis() < deadline) { "Read-only discriminator deadline exceeded" }
        }
        checkActive()
        check(clock.elapsedMillis() < deadline) { "Read-only discriminator deadline exceeded" }
        return QDriveTransitionInspection(result, fingerprint, strings)
    }

    private fun <T> withConnection(
        device: PassiveUsbDevice, report: StringBuilder, action: (QDriveTransitionConnection) -> T,
    ): T {
        val connection = access.open(device)
        report.appendLine("open name=${device.name} result=PASS; no interface claimed")
        try {
            return action(connection)
        } finally {
            try {
                connection.close()
                report.appendLine("cleanup connection close=PASS")
            } catch (error: Exception) {
                failure(report, "connection close", error)
                throw error
            }
        }
    }

    private fun checkActive() { check(!cancelled.get()) { "Diagnostic cancelled — STOP" } }

    companion object {
        const val TITLE = "Phase 3D.2E — Controlled QDrive USB vendor transition"
        const val SAFETY = "STATE-CHANGING USB TEST — sends one verified QDrive vendor request"
        val NOT_RUN = "$TITLE: not run\n$SAFETY"
        const val REQUEST_TIMEOUT_MS = 1_000
        const val OBSERVATION_MS = 10_000L
        const val POLL_MS = 250L
        const val INSPECTION_MS = 15_000L
        private const val APPLE_VID = 0x05ac
        private const val INITIAL_PID = 0x12a8
        private fun hex(value: Int) = "0x%04X".format(Locale.US, value)
        private fun failure(report: StringBuilder, stage: String, error: Exception) {
            report.appendLine("ERROR stage=$stage ${error.javaClass.simpleName}: ${error.message}")
        }
        private fun appleState(devices: List<PassiveUsbDevice>) =
            devices.filter { it.vendorId == APPLE_VID }.map { it.copy(hasPermission = false) }.sortedBy { it.name }

        private fun describe(devices: List<PassiveUsbDevice>, report: StringBuilder) {
            report.appendLine("Attached USB devices=${devices.size}")
            devices.sortedBy { it.name }.forEach { device ->
                report.appendLine("Device name=${device.name} VID=${hex(device.vendorId)} PID=${hex(device.productId)} permission=${device.hasPermission} configurationCount=${device.configurations.size} interfaceCount=${device.interfaces.size}")
                fun intf(intf: PassiveUsbInterface) {
                    report.appendLine("  interface ID=${intf.id} alt=${intf.alternateSetting} class=${intf.interfaceClass}/${intf.subclass}/${intf.protocol} cached name=${intf.name ?: "unavailable"}")
                    intf.endpoints.forEach {
                        report.appendLine("    endpoint=${hex(it.address)} number=${it.number} type=${it.type} direction=${if (it.direction == 128) "IN" else if (it.direction == 0) "OUT" else "UNKNOWN"} maxPacket=${it.maxPacketSize} interval=${it.interval}")
                    }
                }
                device.configurations.forEachIndexed { index, config ->
                    report.appendLine("Configuration index=$index ID=${config.id} name=${config.name ?: "unavailable"}")
                    config.interfaces.forEach(::intf)
                }
                if (device.configurations.isEmpty()) device.interfaces.forEach(::intf)
            }
        }
    }
}
