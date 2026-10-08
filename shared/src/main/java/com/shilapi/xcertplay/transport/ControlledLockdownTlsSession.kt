package com.shilapi.xcertplay.transport

/**
 * Upgrades the existing Lockdown TCP62078 stream that carried StartSession to TLS and exchanges
 * encrypted Lockdown plists on it. Implementations must not open a new TCP connection.
 */
interface LockdownTlsUpgrade {
    fun startSessionTls(record: LockdownPairRecord, peerReport: (LockdownPeerValidation) -> Unit)
    fun tlsSessionRequest(message: LockdownPlistValue.Dictionary): LockdownPlistValue.Dictionary
}

/**
 * Phase 3D.2U: one TLS handshake, one harmless encrypted QueryType, one encrypted
 * StopSession(SessionID), then stop. No retries; never StartService, Pair, or ValidatePair.
 */
class ControlledLockdownTlsSession(
    private val transport: LockdownTlsUpgrade,
    private val active: () -> Unit,
    private val report: (String) -> Unit,
) {
    private var ran = false
    var tlsHandshakeAttempts = 0
        private set
    var encryptedQueryTypeAttempts = 0
        private set
    var stopSessionAttempts = 0
        private set
    var stopSessionConfirmed = false
        private set

    fun run(record: LockdownPairRecord, sessionId: String): String {
        check(!ran) { "One TLS round trip only" }
        ran = true
        require(sessionId.isNotBlank() && sessionId.length <= 1024)
        active()
        var validation = LockdownPeerValidation.NOT_EVALUATED
        tlsHandshakeAttempts++
        report("TLS handshake attempted=true on the SAME TCP62078 stream (no new connection)")
        try {
            transport.startSessionTls(record) {
                validation = it
                report(it.safeSummary())
            }
        } catch (error: Exception) {
            val peerFailure = validation !== LockdownPeerValidation.NOT_EVALUATED && !validation.passed
            report("TLS handshake succeeded=false; StopSession not attempted (no TLS); SESSION STOP NOT CONFIRMED")
            throw StartSessionFailure(
                if (peerFailure) PEER_VALIDATION_FAILURE else HANDSHAKE_FAILURE,
                if (peerFailure) validation.safeReason else safeReason(error),
                error,
            )
        }
        report("TLS handshake succeeded=true; peer authenticated as paired device")

        var queryFailure: StartSessionFailure? = null
        try {
            active()
            encryptedQueryTypeAttempts++
            val reply = transport.tlsSessionRequest(message("QueryType"))
            requireReply(reply, "QueryType")
            val type = (reply.entries["Type"] as? LockdownPlistValue.Text)?.value
            if (type != "com.apple.mobile.lockdown") throw StartSessionFailure(QUERY_FAILURE, "UNEXPECTED_TYPE")
            report("Encrypted QueryType=com.apple.mobile.lockdown (application data round trip PASS)")
        } catch (error: Exception) {
            val failure = error as? StartSessionFailure ?: StartSessionFailure(QUERY_FAILURE, safeReason(error), error)
            report("Encrypted QueryType failed; reason=${failure.safeReason}")
            if (error is IphoneUsbException) {
                report("TLS transport failed; StopSession not attempted; SESSION STOP NOT CONFIRMED")
                throw failure
            }
            queryFailure = failure
        }

        stopSessionAttempts++
        report("Encrypted StopSession(active SessionID) attempted once inside TLS")
        try {
            active()
            requireReply(
                transport.tlsSessionRequest(message("StopSession", "SessionID" to LockdownPlistValue.Text(sessionId))),
                "StopSession",
            )
            stopSessionConfirmed = true
            report("StopSession confirmed=true")
        } catch (error: Exception) {
            val reason = (error as? StartSessionFailure)?.safeReason ?: safeReason(error)
            report("StopSession confirmed=false; reason=$reason; SESSION STOP NOT CONFIRMED; local cleanup continues")
            throw StartSessionFailure(STOPSESSION_FAILURE, reason, error)
        }
        queryFailure?.let { throw it }
        return CONFIRMED
    }

    private fun message(request: String, vararg extra: Pair<String, LockdownPlistValue>) =
        LockdownPlistValue.Dictionary(linkedMapOf<String, LockdownPlistValue>(
            "Label" to LockdownPlistValue.Text(LABEL),
            "Request" to LockdownPlistValue.Text(request),
        ).apply { putAll(extra) })

    private fun requireReply(reply: LockdownPlistValue.Dictionary, request: String) {
        if ((reply.entries["Request"] as? LockdownPlistValue.Text)?.value != request) {
            throw StartSessionFailure(if (request == "StopSession") STOPSESSION_FAILURE else QUERY_FAILURE, "RESPONSE_REQUEST_MISMATCH")
        }
        if (reply.entries.containsKey("Error")) {
            val code = (reply.entries["Error"] as? LockdownPlistValue.Text)?.value
            throw StartSessionFailure(
                if (request == "StopSession") STOPSESSION_FAILURE else QUERY_FAILURE,
                code?.takeIf { it in SAFE_ERRORS } ?: "UNKNOWN_ERROR",
            )
        }
        val result = reply.entries["Result"]
        if (result != null && (result as? LockdownPlistValue.Text)?.value != "Success") {
            throw StartSessionFailure(if (request == "StopSession") STOPSESSION_FAILURE else QUERY_FAILURE, "INVALID_RESULT")
        }
    }

    private fun safeReason(error: Throwable): String = when (error) {
        is StartSessionFailure -> error.safeReason
        is IphoneUsbException -> PairDiagnosticFailureDetails.transportReason(error)
        else -> error.javaClass.simpleName
    }

    companion object {
        const val LABEL = "DiPlay-ControlledLockdownTls"
        const val CONFIRMED = "LOCKDOWN TLS ROUND TRIP CONFIRMED — STOPSESSION CONFIRMED"
        const val HANDSHAKE_FAILURE = "LOCKDOWN TLS HANDSHAKE FAILURE"
        const val PEER_VALIDATION_FAILURE = "LOCKDOWN TLS PEER VALIDATION FAILURE"
        const val QUERY_FAILURE = "ENCRYPTED LOCKDOWN REQUEST FAILURE"
        const val STOPSESSION_FAILURE = "STOPSESSION NOT CONFIRMED"
        private val SAFE_ERRORS = setOf("InvalidHostID", "InvalidPairRecord", "SessionActive", "SessionInactive",
            "MissingSessionID", "InvalidSessionID", "MissingValue", "InvalidArgument", "InvalidConnection")
    }
}
