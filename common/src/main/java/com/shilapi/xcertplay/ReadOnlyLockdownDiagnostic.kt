package com.shilapi.xcertplay

import java.util.concurrent.atomic.AtomicBoolean

interface ReadOnlyLockdownConnection : ActiveConfig5UsbMuxClaimConnection {
    fun initialize(report: (String) -> Unit)
    fun connectLockdown()
    fun queryType(): String
    fun productType(): String
}

interface ReadOnlyLockdownAccess {
    fun devices(): List<PassiveUsbDevice>
    fun open(device: PassiveUsbDevice): ReadOnlyLockdownConnection
}

class ReadOnlyLockdownDiagnostic(private val access: ReadOnlyLockdownAccess) {
    private val started = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val detached = AtomicBoolean()
    fun cancel() { cancelled.set(true) }
    fun deviceDetached() { detached.set(true) }
    private fun active() {
        requireUsbMuxClaim(!detached.get(), UsbMuxClaimFailureReason.DEVICE_DISAPPEARED, "Detach observed")
        requireUsbMuxClaim(!cancelled.get(), UsbMuxClaimFailureReason.CANCELLED, "Cancelled")
    }

    fun run(): String {
        val report = StringBuilder("$TITLE\n$SAFETY\n")
        var connection: ReadOnlyLockdownConnection? = null
        var baseline: PassiveUsbDevice? = null
        var claimed = false
        var init = false
        var tcp = false
        var discovery = false
        var cleanup = true
        var stage = "PREFLIGHT"
        var failure = false
        fun fail(error: Exception) {
            failure = true
            report.appendLine("Failure stage=$stage reason=${(error as? UsbMuxClaimFailure)?.reason ?: error.javaClass.simpleName}: ${error.message}; STOP")
            error.suppressed.forEach { report.appendLine("Additional cleanup failure=${it.javaClass.simpleName}: ${it.message}") }
        }
        fun stable() {
            active()
            ActiveConfig5UsbMuxClaimSelection.unchanged(checkNotNull(baseline), access.devices())
        }
        try {
            requireUsbMuxClaim(started.compareAndSet(false, true), UsbMuxClaimFailureReason.ALREADY_RUN, "One-shot instance")
            active()
            val devices = access.devices()
            devices.forEach { report.appendLine("Preflight inventory=$it") }
            val device = ActiveConfig5UsbMuxClaimSelection.device(devices)
            baseline = device
            val target = versionInterface(device)
            report.appendLine("Selected config5 interface=$target force=false; preflight=PASS")
            stage = "OPEN"
            connection = access.open(device)
            stage = "GET_CONFIGURATION"
            val config = connection.configuration { report.appendLine(it) }
            report.appendLine("Same-connection GET_CONFIGURATION=$config")
            requireUsbMuxClaim(config == 5, UsbMuxClaimFailureReason.ACTIVE_CONFIGURATION_NOT_5, "Active config must be5")
            stable()
            stage = "CLAIM"
            claimed = connection.claimUsbMux()
            report.appendLine("claimInterface(force=false)=$claimed")
            requireUsbMuxClaim(claimed, UsbMuxClaimFailureReason.CLAIM_RETURNED_FALSE, "No retry")
            stable()
            stage = "USBMUX_INIT"
            connection.initialize { report.appendLine(it) }
            stable()
            init = true
            report.appendLine("USBMUX INIT CONFIRMED (version valid; setup17 fully written; no dedicated setup ACK)")
            stage = "LOCKDOWN_TCP_CONNECT"
            connection.connectLockdown()
            stable()
            tcp = true
            report.appendLine("LOCKDOWN TCP CONNECTION CONFIRMED (port62078)")
            stage = "QUERY_TYPE"
            val type = connection.queryType()
            check(type == "com.apple.mobile.lockdown") { "Unexpected QueryType service" }
            report.appendLine("QueryType=$type")
            stable()
            stage = "GET_VALUE_PRODUCT_TYPE"
            val product = connection.productType()
            check(product.matches(Regex("[A-Za-z0-9,._-]{1,64}"))) { "Unsafe ProductType metadata" }
            stable()
            discovery = true
            report.appendLine("ProductType=$product")
        } catch (error: Exception) {
            fail(error)
        } finally {
            connection?.let { opened ->
                try {
                    if (claimed) {
                        stage = "RELEASE"
                        try {
                            val released = opened.releaseUsbMux()
                            report.appendLine("Release=$released")
                            check(released) { "Release returned false" }
                        } catch (error: Exception) { cleanup = false; fail(error) }
                    } else report.appendLine("Release=not attempted (claim unsuccessful)")
                } finally {
                    stage = "CLOSE"
                    try { opened.close(); report.appendLine("Cleanup close=PASS") }
                    catch (error: Exception) { cleanup = false; fail(error) }
                }
            }
        }
        if (baseline != null && connection != null) {
            stage = "FINAL_INVENTORY"
            try { stable() } catch (error: Exception) { fail(error) }
        }
        report.appendLine("Summary init=$init TCP=$tcp discovery=$discovery cleanup=$cleanup")
        if (!failure && discovery && cleanup) report.appendLine(PASS)
        report.appendLine(if (!failure && discovery && cleanup) "Outcome=PASS" else "Outcome=STOP (stage/error above; no fallback)")
        report.appendLine("Read-only requests: QueryType + GetValue(ProductType) only; no Pair/ValidatePair/StartSession, pairing records, Trust approval, service/TLS, CarKit/iAP2/MFi/NCM/AirPlay/CarPlay. STOP.")
        return report.toString()
    }

    companion object {
        const val TITLE = "USBMUX init + read-only Lockdown discovery"
        const val SAFETY = "READ-ONLY LOCKDOWN ONLY — NO PAIR / TRUST / SESSION"
        const val PASS = "READ-ONLY LOCKDOWN DISCOVERY CONFIRMED"
        val NOT_RUN = "$TITLE: not run\n$SAFETY"
    }
}
