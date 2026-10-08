package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.ControlledPairFailure
import com.shilapi.xcertplay.transport.PairDiagnosticFailureDetails
import java.util.concurrent.atomic.AtomicBoolean

interface ControlledPairConnection {
    fun prepareAndPair(store: DiagnosticPairStore, checkActive: () -> Unit, report: (String) -> Unit)
    fun validateExisting(store: DiagnosticPairStore, checkActive: () -> Unit, report: (String) -> Unit) {
        throw ControlledPairFailure("VALIDATE_EXISTING", "VALIDATION_BOUNDARY_UNAVAILABLE")
    }
    fun transportState(): String = "USB_HANDLE_OPEN=UNKNOWN; USBMUX_HOST_ACTIVE=UNKNOWN; TCP_OPEN=UNKNOWN"
}

class ControlledPairDiagnostic(
    private val access: ReadOnlyLockdownAccess,
    private val store: DiagnosticPairStore,
    private val existingOnly: Boolean = false,
) {
    private val started = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val detached = AtomicBoolean()
    private val reenumerated = AtomicBoolean()
    fun cancel() { cancelled.set(true) }
    fun deviceDetached() { detached.set(true) }
    fun deviceAttached() { if (detached.get()) reenumerated.set(true) }

    fun run(): String {
        val title = if (existingOnly) ExistingPairValidationDiagnostic.TITLE else TITLE
        val safety = if (existingOnly) ExistingPairValidationDiagnostic.SAFETY else SAFETY
        val report = StringBuilder("$title\n$safety\n")
        var connection: ReadOnlyLockdownConnection? = null
        var baseline: PassiveUsbDevice? = null
        var claimed = false
        var validated = false
        var failed = false
        var stage = "PREFLIGHT"
        fun state() {
            report.appendLine("USB_DETACHED=${detached.get()}")
            report.appendLine("USB_REENUMERATED=${reenumerated.get()} (observed attach after detach; no recovery)")
            try {
                val devices = access.devices()
                val present = baseline?.let { selected -> devices.any { it.name == selected.name } }
                report.appendLine("DEVICE_PRESENT=${present?.toString() ?: "UNKNOWN (no selected device)"}")
                baseline?.let { selected ->
                    report.appendLine("DEVICE_STATE_UNCHANGED=${devices.any { it == selected }}")
                }
            } catch (error: Exception) {
                report.appendLine("DEVICE_PRESENT=UNKNOWN inventoryError=${error.javaClass.simpleName}")
            } catch (error: LinkageError) {
                report.appendLine("DEVICE_PRESENT=UNKNOWN inventoryError=${error.javaClass.simpleName}")
            }
            try {
                report.appendLine((connection as? ControlledPairConnection)?.transportState()
                    ?: "USB_HANDLE_OPEN=UNKNOWN; USBMUX_HOST_ACTIVE=UNKNOWN; TCP_OPEN=UNKNOWN")
            } catch (error: Exception) {
                report.appendLine("Transport state=UNKNOWN stateError=${error.javaClass.simpleName}")
            }
        }
        fun fail(error: Throwable) {
            failed = true
            val pair = error as? ControlledPairFailure
            val reason = pair?.reason ?: (error as? UsbMuxClaimFailure)?.reason?.name
                ?: (error as? com.shilapi.xcertplay.transport.IphoneUsbException)?.let {
                    PairDiagnosticFailureDetails.transportReason(it)
                }
                ?: if (stage == "PAIR_RECORD_PREFLIGHT") "PAIR_RECORD_INVALID" else error.javaClass.simpleName
            report.appendLine("Failure stage=${pair?.stage ?: stage} reason=$reason; STOP")
            report.append(pair?.linkageDetails ?: PairDiagnosticFailureDetails.format(pair?.stage ?: stage, error))
            var cause: Throwable? = error
            repeat(4) {
                (cause as? UsbMuxClaimFailure)?.let { inventory ->
                    report.appendLine("Original inventory/cancellation reason=${inventory.reason}")
                }
                cause = cause?.cause
            }
            state()
            if (reason == "TRUST_PENDING") report.appendLine(if (existingOnly)
                "Validation reports Trust pending: STOP; do not approve a new prompt or Pair from this diagnostic."
                else "Trust pending: cleanup and STOP. User may approve on iPhone; a NEW manually confirmed run reuses the same candidate. No automatic retry.")
            if (reason == "TRUST_DENIED") report.appendLine("Trust denied; retained candidate; no retry or workaround.")
            report.appendLine("Pairing may already persist on iPhone; cleanup is not rollback.")
        }
        fun stable() {
            requireUsbMuxClaim(!detached.get(), UsbMuxClaimFailureReason.DEVICE_DISAPPEARED, "Detached")
            requireUsbMuxClaim(!cancelled.get(), UsbMuxClaimFailureReason.CANCELLED, "Cancelled")
            baseline?.let { ActiveConfig5UsbMuxClaimSelection.unchanged(it, access.devices()) }
        }
        try {
            requireUsbMuxClaim(started.compareAndSet(false, true), UsbMuxClaimFailureReason.ALREADY_RUN, "One run")
            stable()
            if (existingOnly) {
                stage = "PAIR_RECORD_PREFLIGHT"
                val records = store.acceptedRecords()
                if (records.isEmpty()) throw ControlledPairFailure(stage, "PAIR_RECORD_NOT_FOUND")
                for (candidate in records) {
                    if (candidate.state == com.shilapi.xcertplay.transport.DiagnosticPairState.PREPARED) {
                        throw ControlledPairFailure(stage, "PAIR_RECORD_PREPARED")
                    }
                    val record = checkNotNull(candidate.record)
                    check(candidate.hostId == record.hostId && candidate.systemBuid == record.systemBuid)
                    com.shilapi.xcertplay.transport.DiagnosticPairMaterial.verify(record)
                }
                report.appendLine("Persisted accepted record preflight=VERIFIED; no identity generated")
            }
            val device = ActiveConfig5UsbMuxClaimSelection.device(access.devices())
            baseline = device
            val target = versionInterface(device)
            report.appendLine("Preflight=PASS VID=05AC PID=12A8 configurations=5 Valeria/NCM descriptors confirmed")
            report.appendLine("Scoped configuration5 USBMUX id=${target.id} alt=${target.alternateSetting} endpoints=0x04/0x85 force=false")
            stage = "OPEN"
            connection = access.open(device)
            stage = "GET_CONFIGURATION"
            val config = connection.configuration { report.appendLine(it) }
            report.appendLine("Same-connection GET_CONFIGURATION=$config")
            requireUsbMuxClaim(config == 5, UsbMuxClaimFailureReason.ACTIVE_CONFIGURATION_NOT_5, "Active5 required")
            stable()
            stage = "CLAIM"
            claimed = connection.claimUsbMux()
            report.appendLine("Claim(force=false)=$claimed")
            requireUsbMuxClaim(claimed, UsbMuxClaimFailureReason.CLAIM_RETURNED_FALSE, "No retry")
            stable()
            stage = "USBMUX_INIT"
            connection.initialize { report.appendLine(it) }
            stable()
            report.appendLine("USBMUX INIT CONFIRMED")
            stage = "LOCKDOWN_TCP"
            connection.connectLockdown()
            stable()
            stage = "QUERY_TYPE"
            check(connection.queryType() == "com.apple.mobile.lockdown")
            stable()
            report.appendLine("QueryType confirmed; pairing preconditions passed")
            stage = "PAIR_PREPARATION"
            val controlled = connection as? ControlledPairConnection
                ?: throw ControlledPairFailure(stage, "CONTROLLED_BOUNDARY_UNAVAILABLE")
            if (existingOnly) controlled.validateExisting(store, ::stable) { report.appendLine(it) }
            else controlled.prepareAndPair(store, ::stable) { report.appendLine(it) }
            stable()
            validated = true
            state()
        } catch (error: Exception) { fail(error) }
        catch (error: LinkageError) { fail(error) }
        finally {
            connection?.let { opened ->
                try {
                    if (claimed) {
                        stage = "RELEASE"
                        try {
                            val released = opened.releaseUsbMux()
                            report.appendLine("Release=$released")
                            check(released)
                        } catch (error: Exception) { fail(error) }
                        catch (error: LinkageError) { fail(error) }
                    } else report.appendLine("Release=not attempted (no successful claim)")
                } finally {
                    stage = "CLOSE"
                    try { opened.close(); report.appendLine("Cleanup close=PASS") }
                    catch (error: Exception) { fail(error) }
                    catch (error: LinkageError) { fail(error) }
                }
            }
        }
        stage = "FINAL_STATE"
        try { stable() } catch (error: Exception) { fail(error) }
        catch (error: LinkageError) { fail(error) }
        report.appendLine("Post-cleanup state:")
        state()
        if (validated && !failed) report.appendLine(if (existingOnly) ExistingPairValidationDiagnostic.PASS else PASS)
        report.appendLine(if (validated && !failed) "Outcome=PASS" else "Outcome=STOP")
        report.appendLine(COUNTERS)
        if (existingOnly) report.appendLine("Pair count = 0 (existing-record-only boundary)")
        report.appendLine("STARTSESSION / TLS / STARTSERVICE NOT STARTED; no automatic recovery. STOP.")
        return report.toString()
    }

    companion object {
        const val TITLE = "Phase 3D.2O — Controlled Lockdown Pair + ValidatePair"
        const val SAFETY = "PAIR / VALIDATE ONLY — NO SESSION / TLS / SERVICES"
        const val PASS = "CONTROLLED PAIR / VALIDATEPAIR CONFIRMED"
        const val COUNTERS = "StartSession count = 0; TLS starts = 0; StartService count = 0; CarKit requests = 0; iAP2 = 0; MFi = 0; NCM activation = 0; AirPlay = 0; CarPlay = 0 (excluded by diagnostic boundary)"
        val NOT_RUN = "$TITLE: not run\n$SAFETY"
    }
}

class ExistingPairValidationDiagnostic(access: ReadOnlyLockdownAccess, store: DiagnosticPairStore) {
    private val engine = ControlledPairDiagnostic(access, store, existingOnly = true)
    fun run() = engine.run()
    fun cancel() = engine.cancel()
    fun deviceDetached() = engine.deviceDetached()
    fun deviceAttached() = engine.deviceAttached()

    companion object {
        const val TITLE = "Phase 3D.2P.1 — Existing-record ValidatePair (NO PAIR)"
        const val SAFETY = "EXISTING ACCEPTED RECORD ONLY — NO PAIR / SESSION / TLS / SERVICES"
        const val PASS = "EXISTING PAIR VALIDATION CONFIRMED"
        val NOT_RUN = "$TITLE: not run\n$SAFETY"
    }
}
