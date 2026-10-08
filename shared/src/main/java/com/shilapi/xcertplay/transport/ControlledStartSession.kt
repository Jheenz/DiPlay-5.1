package com.shilapi.xcertplay.transport

import java.io.IOException

interface AcceptedSessionRecords {
    fun acceptedRecords(): List<DiagnosticPairCandidate>
    fun load(deviceId: String): DiagnosticPairCandidate?
}

class StartSessionFailure(val classification: String, val safeReason: String, cause: Throwable? = null) :
    IOException("$classification: $safeReason", cause)

class ControlledStartSession(
    private val records: AcceptedSessionRecords,
    private val verify: (LockdownPairRecord) -> Unit = DiagnosticPairMaterial::verify,
    private val requirePairedRecord: Boolean = false,
    private val requireTls: Boolean = false,
    private val strictResponseFields: Boolean = false,
) {
    private var preflight: DiagnosticPairCandidate? = null
    private var attempted = false
    var startSessionAttempts = 0
        private set
    var stopSessionAttempts = 0
        private set

    fun preflight() {
        check(!attempted && preflight == null)
        val candidates = records.acceptedRecords()
        if (candidates.size != 1) association(if (candidates.isEmpty()) "PAIR_RECORD_NOT_FOUND" else "AMBIGUOUS_RECORDS")
        preflight = candidates.single().also(::verifyCandidate)
    }

    fun run(
        request: (LockdownPlistValue.Dictionary) -> LockdownPlistValue.Dictionary,
        active: () -> Unit,
        report: (String) -> Unit,
        tls: ((LockdownPairRecord, String) -> String)? = null,
    ): String {
        check(!attempted) { "One StartSession diagnostic attempt only" }
        attempted = true
        val inventoryRecord = preflight ?: association("LOCAL_PREFLIGHT_REQUIRED")
        fun exchange(operation: String, fields: Map<String, LockdownPlistValue> = emptyMap()): LockdownPlistValue.Dictionary {
            check(operation in setOf("GetValue", "StartSession", "StopSession"))
            active()
            val message = LockdownPlistValue.Dictionary(linkedMapOf<String, LockdownPlistValue>(
                "Label" to LockdownPlistValue.Text("DiPlay-ControlledStartSession"),
                "Request" to LockdownPlistValue.Text(operation),
            ).apply { putAll(fields) })
            if (operation == "StartSession") {
                startSessionAttempts++
                report("StartSession request attempted once")
            }
            if (operation == "StopSession") stopSessionAttempts++
            val response = try {
                request(message)
            } catch (error: Exception) {
                report("$operation transport=FAILED; response presence=false (no complete parsed response)")
                val reason = (error as? IphoneUsbException)?.let(PairDiagnosticFailureDetails::transportReason)
                    ?: "LOCAL_OR_TRANSPORT_FAILURE"
                throw StartSessionFailure(TRANSPORT, reason, error)
            }
            report("$operation transport=SUCCESS; response presence=true")
            val rejection = when (operation) {
                "GetValue" -> ASSOCIATION
                "StopSession" -> TRANSPORT
                else -> REJECTED
            }
            if (strictResponseFields) {
                val allowed = when (operation) {
                    "GetValue" -> setOf("Request", "Value", "Result", "Error")
                    "StartSession" -> setOf("Request", "SessionID", "EnableSessionSSL", "Result", "Error")
                    else -> setOf("Request", "Result", "Error")
                }
                val extraFields = response.entries.keys - allowed
                if (extraFields.isNotEmpty() && operation == "GetValue") {
                    val recognized = extraFields.intersect(SAFE_GET_VALUE_METADATA_FIELDS).sorted()
                    report("GetValue optional fields ignored; count=${extraFields.size}; " +
                        "recognizedNames=${recognized.ifEmpty { listOf("NONE") }.joinToString(",")}; " +
                        "unrecognizedCount=${extraFields.size - recognized.size}; values=REDACTED")
                } else if (extraFields.isNotEmpty()) {
                    throw StartSessionFailure(rejection, "UNEXPECTED_RESPONSE_FIELDS")
                }
            }
            if ((response.entries["Request"] as? LockdownPlistValue.Text)?.value != operation) {
                throw StartSessionFailure(rejection, "RESPONSE_REQUEST_MISMATCH")
            }
            if (response.entries.containsKey("Error")) {
                val code = (response.entries["Error"] as? LockdownPlistValue.Text)?.value
                val safe = code?.takeIf { it in SAFE_ERRORS } ?: "UNKNOWN_ERROR"
                report("$operation safe Lockdown error=$safe")
                throw StartSessionFailure(rejection, safe)
            }
            val result = response.entries["Result"]
            if (result != null && (result as? LockdownPlistValue.Text)?.value != "Success") {
                throw StartSessionFailure(rejection, "INVALID_RESULT")
            }
            return response
        }
        val identityReply = exchange("GetValue", mapOf("Key" to LockdownPlistValue.Text("UniqueDeviceID")))
        val deviceId = (identityReply.entries["Value"] as? LockdownPlistValue.Text)?.value
            ?.takeIf { it.matches(Regex("[A-Za-z0-9-]{8,128}")) } ?: association("INVALID_DEVICE_IDENTIFIER")
        active()
        val candidate = records.load(deviceId) ?: association("ASSOCIATION_MISMATCH")
        verifyCandidate(candidate)
        if (candidate.deviceId != deviceId || inventoryRecord.deviceId != deviceId ||
            inventoryRecord.hostId != candidate.hostId || inventoryRecord.systemBuid != candidate.systemBuid) {
            association("ASSOCIATION_MISMATCH")
        }
        val record = checkNotNull(candidate.record)
        report("Existing accepted record association=VERIFIED; no persistence mutation")
        val reply = exchange("StartSession", mapOf(
            "HostID" to LockdownPlistValue.Text(record.hostId),
            "SystemBUID" to LockdownPlistValue.Text(record.systemBuid),
        ))
        val idValue = reply.entries["SessionID"]
        val sessionId = (idValue as? LockdownPlistValue.Text)?.value?.takeIf { it.isNotBlank() && it.length <= 1024 }
        val ssl = (reply.entries["EnableSessionSSL"] as? LockdownPlistValue.Boolean)?.value
        report("SessionID present=${idValue != null}; valid=${sessionId != null}; EnableSessionSSL=${ssl ?: "UNKNOWN"}")
        if (ssl == true) {
            if (tls != null) {
                if (sessionId == null) throw StartSessionFailure(REJECTED, "INVALID_SESSION_ID; SESSION_STOP_NOT_CONFIRMED")
                report("$TLS_REQUIRED; continuing to controlled TLS on the same TCP stream")
                return tls(record, sessionId)
            }
            report("STARTSESSION SUCCEEDED — TLS REQUIRED — SESSION STOP NOT CONFIRMED")
            if (sessionId == null) throw StartSessionFailure(REJECTED, "INVALID_SESSION_ID; SESSION_STOP_NOT_CONFIRMED")
            return TLS_REQUIRED
        }
        if (ssl == null || sessionId == null) {
            throw StartSessionFailure(REJECTED, "MALFORMED_SESSION_RESPONSE; SESSION_STOP_NOT_CONFIRMED")
        }
        if (tls != null) {
            if (requireTls) throw StartSessionFailure(REJECTED, "TLS_REQUIRED; SESSION STOP NOT CONFIRMED")
            // 3D.2U requires SSL; a plaintext session is closed via plaintext StopSession and never upgraded.
            report("EnableSessionSSL=false; TLS path not substituted")
        }
        try {
            exchange("StopSession", mapOf("SessionID" to LockdownPlistValue.Text(sessionId)))
            report("StopSession succeeded=true")
        } catch (error: Exception) {
            report("StopSession succeeded=false; SESSION STOP NOT CONFIRMED")
            throw error
        }
        return SSL_NOT_REQUIRED
    }

    private fun verifyCandidate(candidate: DiagnosticPairCandidate) {
        if (requirePairedRecord && candidate.state != DiagnosticPairState.PAIRED) association("PAIRED_RECORD_REQUIRED")
        if (candidate.state !in setOf(DiagnosticPairState.PAIRED, DiagnosticPairState.VALIDATED)) association("PAIR_RECORD_PREPARED")
        val record = candidate.record ?: association("PAIR_RECORD_INVALID")
        if (candidate.hostId != record.hostId || candidate.systemBuid != record.systemBuid) association("PAIR_RECORD_INVALID")
        verify(record)
    }

    private fun association(reason: String): Nothing = throw StartSessionFailure(ASSOCIATION, reason)

    companion object {
        const val SSL_NOT_REQUIRED = "STARTSESSION CONFIRMED — SSL NOT REQUIRED"
        const val TLS_REQUIRED = "STARTSESSION CONFIRMED — TLS REQUIRED"
        const val REJECTED = "STARTSESSION REJECTED"
        const val TRANSPORT = "STARTSESSION TRANSPORT FAILURE"
        const val ASSOCIATION = "PAIR RECORD ASSOCIATION FAILURE"
        private val SAFE_ERRORS = setOf("InvalidHostID", "InvalidPairRecord", "PasswordProtected", "SessionActive",
            "SessionInactive", "MissingHostID", "MissingValue", "InvalidArgument", "InvalidConnection",
            "PairingDialogResponsePending", "UserDeniedPairing")
        private val SAFE_GET_VALUE_METADATA_FIELDS = setOf("Domain", "Key")
    }
}
