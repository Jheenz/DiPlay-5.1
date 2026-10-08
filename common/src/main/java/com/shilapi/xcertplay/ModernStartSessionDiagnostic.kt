package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.AcceptedSessionRecords
import com.shilapi.xcertplay.transport.ControlledLockdownTlsSession
import com.shilapi.xcertplay.transport.ControlledStartSession
import com.shilapi.xcertplay.transport.DiagnosticPairMaterial
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.LockdownPlistValue
import com.shilapi.xcertplay.transport.LockdownTlsUpgrade
import com.shilapi.xcertplay.transport.PairDiagnosticFailureDetails
import com.shilapi.xcertplay.transport.StartSessionFailure
import java.util.concurrent.atomic.AtomicBoolean

interface StartSessionConnection {
    fun sessionRequest(message: LockdownPlistValue.Dictionary, report: (String) -> Unit): LockdownPlistValue.Dictionary
    fun sessionTransportState(): String
}

class ModernStartSessionDiagnostic(
    private val access: ReadOnlyLockdownAccess,
    records: AcceptedSessionRecords,
    verify: (LockdownPairRecord) -> Unit = DiagnosticPairMaterial::verify,
    private val tlsRoundTrip: Boolean = false,
) {
    private val engine = ControlledStartSession(records, verify)
    private var tlsEngine: ControlledLockdownTlsSession? = null
    private val started = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    fun cancel() { cancelled.set(true) }
    fun deviceDetached() { cancelled.set(true) }

    fun run(): String {
        val report = StringBuilder(if (tlsRoundTrip) {
            "$TLS_TITLE\n$TLS_SAFETY\nStartSession and TLS change live device session state; disconnect is not confirmed rollback.\n"
        } else {
            "$TITLE\n$SAFETY\nStartSession changes live device session state; disconnect is not confirmed rollback.\n"
        })
        var opened: ReadOnlyLockdownConnection? = null
        var baseline: PassiveUsbDevice? = null
        var claimed = false
        var outcome: String? = null
        var cleanup = true
        var stage = "PAIR_RECORD_PREFLIGHT"
        fun stable() {
            requireUsbMuxClaim(!cancelled.get(), UsbMuxClaimFailureReason.CANCELLED, "Cancelled/detached")
            baseline?.let { ActiveConfig5UsbMuxClaimSelection.unchanged(it, access.devices()) }
        }
        fun failure(error: Throwable) {
            val session = error as? StartSessionFailure
            val classification = session?.classification ?: if (stage == "PAIR_RECORD_PREFLIGHT" || stage == "ASSOCIATION")
                ControlledStartSession.ASSOCIATION else ControlledStartSession.TRANSPORT
            val reason = session?.safeReason ?: (error as? UsbMuxClaimFailure)?.reason?.name
                ?: (error as? com.shilapi.xcertplay.transport.IphoneUsbException)?.let(PairDiagnosticFailureDetails::transportReason)
                ?: error.javaClass.simpleName
            outcome = classification
            report.appendLine("$classification; stage=$stage; safeReason=$reason; STOP")
            report.appendLine("Failure class=${error.javaClass.name}; cause class=${error.cause?.javaClass?.name ?: "NONE"}")
        }
        try {
            check(started.compareAndSet(false, true)) { "One manual attempt only" }
            stable()
            engine.preflight()
            report.appendLine("Exactly one readable accepted record=VERIFIED")
            stage = "USB_PREFLIGHT"
            val device = ActiveConfig5UsbMuxClaimSelection.device(access.devices())
            versionInterface(device)
            baseline = device
            report.appendLine("Permitted Apple 05AC:12A8; five configurations; scoped config5 USBMUX=VERIFIED")
            stage = "OPEN"
            val connection = access.open(device)
            opened = connection
            stage = "GET_CONFIGURATION"
            val config = connection.configuration { report.appendLine(it) }
            report.appendLine("Same-handle GET_CONFIGURATION=$config")
            requireUsbMuxClaim(config == 5, UsbMuxClaimFailureReason.ACTIVE_CONFIGURATION_NOT_5, "Config5 required")
            stable()
            stage = "CLAIM"
            claimed = connection.claimUsbMux()
            report.appendLine("claimInterface(force=false)=$claimed")
            requireUsbMuxClaim(claimed, UsbMuxClaimFailureReason.CLAIM_RETURNED_FALSE, "No retry")
            stable()
            stage = "USBMUX_INIT"
            connection.initialize { report.appendLine(it) }
            stable()
            report.appendLine("USBMUX version + setup07=CONFIRMED")
            stage = "LOCKDOWN_TCP"
            connection.connectLockdown()
            stable()
            report.appendLine("Fresh TCP62078=CONFIRMED")
            stage = "QUERY_TYPE"
            check(connection.queryType() == "com.apple.mobile.lockdown")
            report.appendLine("QueryType=com.apple.mobile.lockdown")
            stable()
            val session = connection as? StartSessionConnection ?: error("Isolated StartSession boundary unavailable")
            val tls = if (tlsRoundTrip) {
                val upgrade = connection as? LockdownTlsUpgrade ?: error("Isolated TLS boundary unavailable")
                ControlledLockdownTlsSession(upgrade, ::stable) { report.appendLine(it) }.also { tlsEngine = it }
            } else null
            stage = "ASSOCIATION"
            outcome = engine.run({ message ->
                stage = (message.entries["Request"] as LockdownPlistValue.Text).value.let {
                    if (it == "GetValue") "ASSOCIATION" else it
                }
                session.sessionRequest(message) { report.appendLine(it) }
            }, ::stable, { report.appendLine(it) }, tls?.let { controlled ->
                { record, sessionId -> stage = "TLS"; controlled.run(record, sessionId) }
            })
            stable()
        } catch (error: Exception) { failure(error) }
        catch (error: LinkageError) { failure(error) }
        finally {
            val session = opened as? StartSessionConnection
            if (session != null) {
                try { report.appendLine(session.sessionTransportState()) }
                catch (error: Exception) { report.appendLine("Transport snapshot unavailable=${error.javaClass.simpleName}") }
                catch (error: LinkageError) { report.appendLine("Transport snapshot unavailable=${error.javaClass.simpleName}") }
            }
            opened?.let { connection ->
                try {
                    if (claimed) {
                        stage = "RELEASE"
                        val released = connection.releaseUsbMux()
                        report.appendLine("Release=$released")
                        check(released)
                    }
                } catch (error: Exception) { cleanup = false; failure(error) }
                catch (error: LinkageError) { cleanup = false; failure(error) }
                finally {
                    stage = "CLOSE"
                    try { connection.close(); report.appendLine("Close=PASS") }
                    catch (error: Exception) { cleanup = false; failure(error) }
                    catch (error: LinkageError) { cleanup = false; failure(error) }
                }
            }
        }
        report.appendLine("Cleanup=$cleanup")
        val tls = tlsEngine
        if (tlsRoundTrip) {
            report.appendLine("StartSession attempts=${engine.startSessionAttempts}; TLS handshake attempts=${tls?.tlsHandshakeAttempts ?: 0}; " +
                "encrypted QueryType attempts=${tls?.encryptedQueryTypeAttempts ?: 0}; " +
                "StopSession attempts=${engine.stopSessionAttempts + (tls?.stopSessionAttempts ?: 0)}; " +
                "StopSession confirmed=${tls?.stopSessionConfirmed ?: false}; no retries/reconnect")
        } else {
            report.appendLine("StartSession attempts=${engine.startSessionAttempts}; StopSession attempts=${engine.stopSessionAttempts}; no retries/reconnect")
        }
        report.appendLine(outcome ?: ControlledStartSession.TRANSPORT)
        report.appendLine(if (tlsRoundTrip) {
            "Pair=0 ValidatePair=0 SetValue=0 identityGeneration=0 persistenceWrites=0 " +
                "StartSession=${engine.startSessionAttempts} TLSHandshake=${tls?.tlsHandshakeAttempts ?: 0} $TLS_ZERO_COUNTERS"
        } else COUNTERS)
        report.appendLine("No CarPlay readiness inferred. STOP.")
        return report.toString()
    }

    companion object {
        const val TITLE = "Phase 3D.2S — Modern Lockdown StartSession"
        const val SAFETY = "STARTSESSION ONLY — NO VALIDATEPAIR / TLS / STARTSERVICE"
        const val COUNTERS = "Pair=0 ValidatePair=0 SetValue=0 identity generation=0 persistence writes=0 TLS=0 StartService=0 CarKit=0 iAP2=0 MFi=0 NCM=0 AirPlay=0 CarPlay=0"
        val NOT_RUN = "$TITLE: not run\n$SAFETY"
        const val TLS_TITLE = "Phase 3D.2U — Controlled Lockdown TLS round trip"
        const val TLS_SAFETY = "STARTSESSION + TLS + ONE ENCRYPTED QUERYTYPE + STOPSESSION ONLY — NO PAIR / VALIDATEPAIR / STARTSERVICE"
        const val TLS_ZERO_COUNTERS = "StartService=0 CarKit=0 iAP2=0 MFi=0 NCM=0 AirPlay=0 CarPlay=0"
        val TLS_NOT_RUN = "$TLS_TITLE: not run\n$TLS_SAFETY"
    }
}
