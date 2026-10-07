package com.shilapi.xcertplay

import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

interface QDriveConfigurationConnection : Closeable {
    fun configuration(report: (String) -> Unit): Int
    fun valeria5(report: (String) -> Unit, checkActive: () -> Unit): Boolean?
    fun select5(): Boolean
}

interface QDriveConfigurationAccess {
    fun devices(): List<PassiveUsbDevice>
    fun open(device: PassiveUsbDevice, selectionAllowed: Boolean): QDriveConfigurationConnection
    fun observe(onEvent: (QDriveUsbEvent) -> Unit): Closeable
}

class QDriveConfigurationDiagnostic(
    private val access: QDriveConfigurationAccess,
    private val clock: QDriveTransitionClock = SystemQDriveTransitionClock,
) {
    private val cancelled = AtomicBoolean(false)
    private val attempted = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    private fun active() { check(!cancelled.get()) { "Diagnostic cancelled; STOP" } }

    fun run(): String {
        val report = StringBuilder("$TITLE\n$SAFETY\n")
        val events = ConcurrentLinkedQueue<String>()
        var observer: Closeable? = null
        var sent = false
        var setter: Boolean? = null
        var cleanupFailed = false
        var finalValue: Int? = null
        var finalVerified = false
        var baseline: PassiveUsbDevice? = null
        fun fail(stage: String, error: Exception) {
            report.appendLine("$stage=FAIL ${error.javaClass.simpleName}: ${error.message}")
        }
        fun <T> connected(device: PassiveUsbDevice, selection: Boolean, block: (QDriveConfigurationConnection) -> T): T {
            val connection = access.open(device, selection)
            report.appendLine("open=PASS selectionAllowed=$selection; no interface claimed")
            try { return block(connection) } finally {
                try {
                    connection.close()
                    report.appendLine("cleanup connection close=PASS")
                } catch (error: Exception) {
                    cleanupFailed = true
                    fail("cleanup connection close", error)
                    throw error
                }
            }
        }
        try {
            active()
            check(!attempted.get()) { "One-shot diagnostic already used; no retry" }
            val device = selected()
            baseline = device
            inventory(listOf(device), report)
            observer = access.observe { events.add("USB event timestamp=${clock.timestamp()} action=${it.action} name=${it.name} VID=${it.vendorId} PID=${it.productId}") }
            connected(device, true) { connection ->
                check(connection.configuration { report.appendLine(it) } == 1) { "Preflight GET_CONFIGURATION must be exactly 1 (already 5 also stops)" }
                report.appendLine("Preflight GET_CONFIGURATION=1")
                valeria(connection, report)
                check(selected() == device) { "Descriptor/identity changed during preflight" }
                check(connection.configuration { report.appendLine(it) } == 1) { "Active configuration changed during preflight" }
                active()
                check(attempted.compareAndSet(false, true)) { "One selection only" }
                sent = true
                report.appendLine("Setter timestamp=${clock.timestamp()} selected UsbConfiguration ID=5")
                val start = clock.elapsedMillis()
                try {
                    setter = connection.select5()
                    report.appendLine("Android setConfiguration returned=$setter")
                } catch (error: Exception) { fail("Android setConfiguration", error) } finally {
                    report.appendLine("Setter elapsedMs=${clock.elapsedMillis() - start}; no retry")
                }
                try {
                    finalValue = connection.configuration { report.appendLine(it) }
                    report.appendLine("Immediate post-set GET_CONFIGURATION=$finalValue")
                } catch (error: Exception) { fail("Immediate post-set GET_CONFIGURATION", error) }
            }
        } catch (error: Exception) { fail(if (sent) "selection/readback" else "precondition", error) }

        if (sent && baseline != null) {
            try {
                val deadline = clock.elapsedMillis() + 10_000
                var previous: List<PassiveUsbDevice>? = null
                do {
                    active()
                    val devices = access.devices()
                    if (previous != devices) {
                        report.appendLine("Passive observation timestamp=${clock.timestamp()}")
                        inventory(devices, report)
                        previous = devices
                    }
                    if (devices.none { it.name == baseline.name }) report.appendLine("Original device disappearance observed=true")
                    val remaining = deadline - clock.elapsedMillis()
                    if (remaining <= 0) break
                    clock.waitMillis(minOf(250, remaining))
                } while (true)
                val after = selected()
                inventory(listOf(after), report)
                check(after.configurations == baseline.configurations) { "Descriptor state unexpectedly changed" }
                check(!cleanupFailed) { "Original cleanup failed; no post-transition open allowed" }
                connected(after, false) { connection ->
                    finalValue = connection.configuration { report.appendLine(it) }
                    report.appendLine("Final post-set GET_CONFIGURATION=$finalValue")
                    valeria(connection, report)
                }
                check(selected() == after) { "USB state changed during final inspection" }
                active()
                finalVerified = true
            } catch (error: Exception) { fail("post-set observation/readback", error) }
        }
        try {
            observer?.close()
            if (observer != null) report.appendLine("cleanup USB observer=PASS")
        } catch (error: Exception) {
            cleanupFailed = true
            fail("cleanup USB observer", error)
        }
        events.forEach(report::appendLine)
        report.appendLine(when {
            !sent -> "PRECONDITION FAILED — REQUEST NOT SENT"
            setter == false -> "ANDROID setConfiguration RETURNED FALSE — STOP"
            setter == null -> "CONFIGURATION SELECTION EXCEPTION — STOP"
            cleanupFailed || !finalVerified || events.any { it.contains("action=ERROR") } ->
                "QDRIVE CONFIGURATION 5 SELECTION INCONCLUSIVE — STOP"
            finalValue == 5 -> "QDRIVE CONFIGURATION 5 SELECTION CONFIRMED"
            else -> "GET_CONFIGURATION=$finalValue (expected 5) — SELECTION NOT CONFIRMED"
        })
        report.appendLine(SAFETY)
        report.appendLine("STOP after Phase 3D.2G; no automatic configuration restore.")
        return report.toString()
    }

    private fun selected(): PassiveUsbDevice {
        active()
        val apples = access.devices().filter { it.vendorId == 0x05ac }
        check(apples.size == 1) { "Exactly one Apple device required; count=${apples.size}; device may have disappeared" }
        val device = apples.single()
        check(device.productId == 0x12a8) { "PID must be 0x12A8" }
        check(device.hasPermission) { "Permission unavailable; no permission requested" }
        check(device.configurations.size == 5) { "Configuration count must be exactly 5" }
        val config = device.configurations.singleOrNull { it.id == 5 } ?: error("Unique configuration ID 5 required")
        fun has(cls: Int, sub: Int, proto: Int) = config.interfaces.any {
            it.interfaceClass == cls && it.subclass == sub && it.protocol == proto
        }
        check(has(255, 254, 2)) { "Configuration 5 lacks Apple USB Multiplexor" }
        check(has(2, 13, 0)) { "Configuration 5 lacks CDC-NCM Control" }
        check(has(10, 0, 1)) { "Configuration 5 lacks CDC-NCM Data" }
        return device
    }

    private fun valeria(connection: QDriveConfigurationConnection, report: StringBuilder) {
        val deadline = clock.elapsedMillis() + 15_000
        val result = connection.valeria5({ report.appendLine(it) }) {
            active()
            check(clock.elapsedMillis() < deadline) { "Configuration 5 string inspection deadline exceeded" }
        }
        active()
        check(clock.elapsedMillis() < deadline) { "Configuration 5 string inspection deadline exceeded" }
        report.appendLine("Configuration 5 exact Valeria substring result=$result")
        check(result == true) { "Configuration 5 Valeria string not confirmed" }
    }

    private fun inventory(devices: List<PassiveUsbDevice>, report: StringBuilder) {
        report.appendLine("Device count=${devices.size}")
        devices.forEach {
            report.appendLine("name=${it.name} VID=${it.vendorId} PID=${it.productId} permission=${it.hasPermission} configurationCount=${it.configurations.size} flattenedInterfaces=${it.interfaces.size}")
            report.append(PassiveUsbConfigurationMapping.describe(it))
        }
    }

    companion object {
        const val TITLE = "Phase 3D.2G — Controlled post-Valeria configuration 5 selection"
        const val SAFETY = "No interface claimed. No vendor request, bulk/interrupt traffic, USBMUX, usbmuxd, Lockdown, pairing/trust, iAP2, MFi, NCM networking, AirPlay, CarPlay or QDrive JNI/native operation."
        val NOT_RUN = "$TITLE: not run\n$SAFETY"
    }
}
