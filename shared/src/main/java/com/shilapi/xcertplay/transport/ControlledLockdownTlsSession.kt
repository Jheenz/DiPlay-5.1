package com.shilapi.xcertplay.transport

/**
 * Upgrades the existing Lockdown TCP62078 stream that carried StartSession to TLS and exchanges
 * encrypted Lockdown plists on it. Implementations must not open a new TCP connection.
 */
interface LockdownTlsUpgrade {
    fun startSessionTls(record: LockdownPairRecord, peerReport: (LockdownPeerValidation) -> Unit)
    fun tlsSessionRequest(message: LockdownPlistValue.Dictionary): LockdownPlistValue.Dictionary
}

/** The one authorized CarKit socket; exposes TLS only, never application-data writes. */
interface CarKitServiceTlsConnection {
    fun connectTcp(port: Int)
    fun startTls(record: LockdownPairRecord, peerReport: (LockdownPeerValidation) -> Unit)
    fun closeTls(): Boolean
    fun closeTcp(): Boolean
}

/**
 * Controlled same-stream TLS operations for 3D.2U and 3D.2V. Each mode has a one-shot
 * request allowlist and ends with an encrypted StopSession; neither retries or pairs.
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
    var encryptedStartServiceAttempts = 0
        private set
    var serviceTcpConnectAttempts = 0
        private set
    var serviceTlsHandshakeAttempts = 0
        private set
    var serviceTcpConnected = false
        private set
    var serviceTlsSucceeded = false
        private set
    var servicePeerValidation = LockdownPeerValidation.NOT_EVALUATED
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

    fun runCarKitServiceDiscovery(record: LockdownPairRecord, sessionId: String): String =
        runCarKitService(record, sessionId, null)

    fun runCarKitServiceConnection(
        record: LockdownPairRecord,
        sessionId: String,
        serviceConnection: CarKitServiceTlsConnection,
    ): String = runCarKitService(record, sessionId, serviceConnection)

    private fun runCarKitService(
        record: LockdownPairRecord,
        sessionId: String,
        serviceConnection: CarKitServiceTlsConnection?,
    ): String {
        check(!ran) { "One TLS discovery only" }
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
            report("TLS handshake succeeded=false; StartService and StopSession not attempted; SESSION STOP NOT CONFIRMED")
            throw StartSessionFailure(
                if (peerFailure) PEER_VALIDATION_FAILURE else HANDSHAKE_FAILURE,
                if (peerFailure) validation.safeReason else safeReason(error),
                error,
            )
        }
        report("TLS handshake succeeded=true; peer authenticated as paired device")

        var serviceFailure: StartSessionFailure? = null
        var servicePort: Int? = null
        if (serviceConnection != null) {
            report("CarKit service USBMUX TCP status=NOT_ATTEMPTED; TLS status=NOT_ATTEMPTED; peer validation=NOT_EVALUATED")
        }
        try {
            active()
            encryptedStartServiceAttempts++
            report("Encrypted StartService(com.apple.carkit.service) attempted once")
            val reply = transport.tlsSessionRequest(message(
                "StartService", "Service" to LockdownPlistValue.Text(CARKIT_SERVICE),
            ))
            val endpoint = inspectServiceReply(reply)
            servicePort = endpoint.port
            if (serviceConnection != null && !endpoint.enableServiceSsl) {
                throw StartSessionFailure(SERVICE_DISCOVERY_FAILURE, "ENABLE_SERVICE_SSL_NOT_TRUE")
            }
        } catch (error: Exception) {
            serviceFailure = error as? StartSessionFailure
                ?: StartSessionFailure(SERVICE_DISCOVERY_FAILURE, safeReason(error), error)
            report("StartService response could not be safely validated; safeReason=${serviceFailure.safeReason}")
        }

        if (serviceConnection != null && serviceFailure == null && servicePort != null) {
            try {
                active()
                serviceTcpConnectAttempts++
                serviceConnection.connectTcp(servicePort)
                serviceTcpConnected = true
                report("CarKit service USBMUX TCP connected=true")
            } catch (error: Exception) {
                serviceFailure = StartSessionFailure(SERVICE_TCP_FAILURE, safeReason(error), error)
                report("CarKit service USBMUX TCP connected=false; safeReason=${serviceFailure.safeReason}")
            }
            if (serviceTcpConnected) {
                try {
                    active()
                    serviceTlsHandshakeAttempts++
                    report("CarKit service TLS handshake attempted=true; application payloads=0")
                    serviceConnection.startTls(record) {
                        servicePeerValidation = it
                        report("CarKit service peer ${it.safeSummary()}")
                    }
                    serviceTlsSucceeded = true
                    report("CarKit service TLS handshake succeeded=true")
                } catch (error: Exception) {
                    val peerMismatch = servicePeerValidation !== LockdownPeerValidation.NOT_EVALUATED &&
                        !servicePeerValidation.passed
                    val classification = if (peerMismatch) SERVICE_PEER_VALIDATION_FAILURE else SERVICE_TLS_FAILURE
                    val reason = if (peerMismatch) servicePeerValidation.safeReason else safeReason(error)
                    serviceFailure = StartSessionFailure(classification, reason, error)
                    report("CarKit service TLS handshake succeeded=false; safeReason=$reason")
                }
            }
        }

        var serviceTlsClosed = true
        var serviceTcpClosed = true
        if (serviceConnection != null) {
            if (serviceTlsHandshakeAttempts > 0) {
                serviceTlsClosed = try { serviceConnection.closeTls() }
                catch (error: Exception) { false.also { report("CarKit service TLS close failed=${error.javaClass.simpleName}") } }
                report("CarKit service TLS close=${if (serviceTlsClosed) "PASS" else "FAIL"}")
            } else report("CarKit service TLS close=NOT_OPEN")
            if (serviceTcpConnectAttempts > 0) {
                serviceTcpClosed = try { serviceConnection.closeTcp() }
                catch (error: Exception) { false.also { report("CarKit service TCP close failed=${error.javaClass.simpleName}") } }
                report("CarKit service TCP close=${if (serviceTcpClosed) "PASS" else "FAIL"}")
            } else report("CarKit service TCP close=NOT_OPEN")
        }

        stopSessionAttempts++
        report("Encrypted StopSession(active SessionID) attempted once inside TLS")
        try {
            requireServiceStopReply(
                transport.tlsSessionRequest(message("StopSession", "SessionID" to LockdownPlistValue.Text(sessionId))),
            )
            stopSessionConfirmed = true
            report("StopSession confirmed=true")
        } catch (error: Exception) {
            val reason = (error as? StartSessionFailure)?.safeReason ?: safeReason(error)
            report("StopSession confirmed=false; reason=$reason; SESSION STOP NOT CONFIRMED; local cleanup continues")
            throw StartSessionFailure(STOPSESSION_FAILURE, reason, error)
        }
        serviceFailure?.let { throw it }
        if (serviceConnection != null && (!serviceTlsClosed || !serviceTcpClosed)) {
            throw StartSessionFailure(SERVICE_CLEANUP_FAILURE, "SERVICE_CONNECTION_CLOSE_FAILED")
        }
        if (serviceConnection != null && (!serviceTcpConnected || !serviceTlsSucceeded || !servicePeerValidation.passed)) {
            throw StartSessionFailure(SERVICE_TLS_FAILURE, "SERVICE_TLS_NOT_CONFIRMED")
        }
        return if (serviceConnection == null) SERVICE_DISCOVERY_CONFIRMED else SERVICE_CONNECTION_CONFIRMED
    }

    private data class ServiceEndpoint(val port: Int, val enableServiceSsl: Boolean)

    private fun inspectServiceReply(reply: LockdownPlistValue.Dictionary): ServiceEndpoint {
        val entries = reply.entries
        if ((entries["Request"] as? LockdownPlistValue.Text)?.value != "StartService") {
            report("StartService response error=UNKNOWN; validServicePort=false; EnableServiceSSL=UNKNOWN")
            throw StartSessionFailure(SERVICE_DISCOVERY_FAILURE, "RESPONSE_REQUEST_MISMATCH")
        }
        val unexpected = entries.keys - (if (entries.containsKey("Error")) setOf("Request", "Error") else SERVICE_RESPONSE_KEYS)
        if (unexpected.isNotEmpty()) {
            report("StartService response error=${entries.containsKey("Error")}; validServicePort=false; EnableServiceSSL=UNKNOWN")
            throw StartSessionFailure(SERVICE_DISCOVERY_FAILURE, "UNEXPECTED_RESPONSE_FIELDS")
        }
        if (entries.containsKey("Error")) {
            val code = (entries["Error"] as? LockdownPlistValue.Text)?.value
                ?.takeIf { it in SAFE_ERRORS } ?: "UNKNOWN_ERROR"
            report("StartService response error=true; safeError=$code; validServicePort=false; EnableServiceSSL=UNKNOWN")
            throw StartSessionFailure(SERVICE_UNAVAILABLE, code)
        }
        val result = entries["Result"]
        if (result != null && (result as? LockdownPlistValue.Text)?.value != "Success") {
            report("StartService response error=false; validServicePort=false; EnableServiceSSL=UNKNOWN")
            throw StartSessionFailure(SERVICE_DISCOVERY_FAILURE, "INVALID_RESULT")
        }
        if ((entries["Service"] as? LockdownPlistValue.Text)?.value != CARKIT_SERVICE) {
            report("StartService response error=false; validServicePort=false; EnableServiceSSL=UNKNOWN")
            throw StartSessionFailure(SERVICE_DISCOVERY_FAILURE, "SERVICE_MISMATCH")
        }
        val port = (entries["Port"] as? LockdownPlistValue.Integer)?.value
        val validPort = port != null && port in 1..65535
        val ssl = (entries["EnableServiceSSL"] as? LockdownPlistValue.Boolean)?.value
        report("StartService response error=false; validServicePort=$validPort; EnableServiceSSL=${ssl ?: "UNKNOWN"}; returned port not contacted")
        if (!validPort) throw StartSessionFailure(SERVICE_DISCOVERY_FAILURE, "INVALID_OR_MISSING_SERVICE_PORT")
        if (ssl == null) throw StartSessionFailure(SERVICE_DISCOVERY_FAILURE, "INVALID_OR_MISSING_ENABLE_SERVICE_SSL")
        return ServiceEndpoint(checkNotNull(port).toInt(), ssl)
    }

    private fun requireServiceStopReply(reply: LockdownPlistValue.Dictionary) {
        if (reply.entries.keys.any { it !in setOf("Request", "Result", "Error") }) {
            throw StartSessionFailure(STOPSESSION_FAILURE, "UNEXPECTED_RESPONSE_FIELDS")
        }
        requireReply(reply, "StopSession")
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
        const val SERVICE_DISCOVERY_FAILURE = "CARKIT SERVICE DISCOVERY RESPONSE INVALID"
        const val SERVICE_UNAVAILABLE = "CARKIT SERVICE UNAVAILABLE"
        const val SERVICE_TCP_FAILURE = "CARKIT SERVICE TCP CONNECTION FAILURE"
        const val SERVICE_TLS_FAILURE = "CARKIT SERVICE TLS HANDSHAKE FAILURE"
        const val SERVICE_PEER_VALIDATION_FAILURE = "CARKIT SERVICE PEER VALIDATION FAILURE"
        const val SERVICE_CLEANUP_FAILURE = "CARKIT SERVICE CONNECTION CLEANUP FAILURE"
        const val SERVICE_DISCOVERY_CONFIRMED = "CARKIT SERVICE DISCOVERY CONFIRMED — STOPSESSION CONFIRMED"
        const val SERVICE_CONNECTION_CONFIRMED = "CARKIT SERVICE TCP/TLS CONFIRMED — STOPSESSION CONFIRMED"
        const val CARKIT_SERVICE = "com.apple.carkit.service"
        private val SERVICE_RESPONSE_KEYS = setOf("Request", "Result", "Service", "Port", "EnableServiceSSL")
        private val SAFE_ERRORS = setOf("InvalidHostID", "InvalidPairRecord", "SessionActive", "SessionInactive",
            "MissingSessionID", "InvalidSessionID", "MissingValue", "InvalidArgument", "InvalidConnection",
            "ServiceLimit", "ServiceProhibited", "InvalidService")
    }
}
