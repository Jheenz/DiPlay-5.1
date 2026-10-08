package com.shilapi.xcertplay.transport

import java.io.IOException
import java.util.UUID

enum class DiagnosticPairState { PREPARED, PAIRED, VALIDATED }

class DiagnosticPairCandidate(
    val deviceId: String,
    val hostId: String,
    val systemBuid: String,
    val record: LockdownPairRecord? = null,
    val state: DiagnosticPairState = DiagnosticPairState.PREPARED,
    escrowBag: ByteArray? = null,
) {
    private val escrow = escrowBag?.copyOf()
    val escrowBag: ByteArray? get() = escrow?.copyOf()
    override fun toString() = "DiagnosticPairCandidate(redacted)"
}

interface DiagnosticPairStore {
    fun load(deviceId: String): DiagnosticPairCandidate?
    fun systemBuid(): String
    fun save(candidate: DiagnosticPairCandidate)
    fun acceptedRecords(): List<DiagnosticPairCandidate> =
        throw IllegalStateException("Verified existing-record inventory unavailable")
}

class ControlledPairFailure(
    val stage: String, val reason: String, val linkageDetails: String? = null,
    cause: Throwable? = null,
) : IOException("Controlled pairing stopped at $stage: $reason", cause)

/** One application attempt, never the normal pairing client's reconnect loop. */
class ControlledLockdownPairing(
    private val request: (LockdownPlistValue.Dictionary) -> LockdownPlistValue.Dictionary,
    private val store: DiagnosticPairStore,
    private val active: () -> Unit,
    private val report: (String) -> Unit,
    private val generate: (ByteArray, String, String, String) -> LockdownPairRecord =
        LockdownPairRecordGenerator::generateDiagnostic,
    private val verifyMaterial: (LockdownPairRecord) -> Unit = DiagnosticPairMaterial::verify,
    private val existingOnly: Boolean = false,
) {
    private var attempted = false
    private var stage = "DEVICE_ASSOCIATION"

    fun run() {
        check(!attempted) { "One controlled pairing attempt only" }
        attempted = true
        try {
            val deviceId = (exchange("GetValue", "UniqueDeviceID").entries["Value"] as? LockdownPlistValue.Text)?.value
                ?.takeIf { it.matches(Regex("[A-Za-z0-9-]{8,128}")) }
                ?: throw ControlledPairFailure(stage, "INVALID_DEVICE_IDENTIFIER")
            stage = "LOAD_CANDIDATE"
            active()
            var candidate = store.load(deviceId)
            if (existingOnly && candidate == null) throw ControlledPairFailure(stage, "PAIR_RECORD_NOT_FOUND")
            if (existingOnly && candidate?.state == DiagnosticPairState.PREPARED) {
                throw ControlledPairFailure(stage, "PAIR_RECORD_PREPARED")
            }
            if (candidate == null) {
                candidate = DiagnosticPairCandidate(deviceId, UUID.randomUUID().toString().uppercase(java.util.Locale.US), store.systemBuid())
                stage = "SAVE_IDENTITY"
                store.save(candidate)
                report("Host identity stored=verified (protected; per-device)")
            }
            check(candidate.deviceId == deviceId) { "Device association mismatch" }
            var record = candidate.record
            if (record != null) {
                stage = "VERIFY_MATERIAL"
                verifyMaterial(record)
                check(record.hostId == candidate.hostId && record.systemBuid == candidate.systemBuid) {
                    "Identity mismatch"
                }
            }
            if (candidate.state != DiagnosticPairState.PREPARED) {
                checkNotNull(record)
                report("Stored accepted/validated record found; VALIDATION ONLY; Pair not attempted")
            } else {
                stage = "SET_UNTRUSTED_HOST_BUID"
                exchange("SetValue", "UntrustedHostBUID", LockdownPlistValue.Text(candidate.systemBuid))
                if (record == null) {
                    stage = "GET_DEVICE_PUBLIC_KEY"
                    val publicKey = (exchange("GetValue", "DevicePublicKey").entries["Value"] as? LockdownPlistValue.Data)?.bytes
                        ?: throw ControlledPairFailure(stage, "INVALID_DEVICE_PUBLIC_KEY")
                    stage = "GET_WIFI_ADDRESS"
                    val wifi = (exchange("GetValue", "WiFiAddress").entries["Value"] as? LockdownPlistValue.Text)?.value
                        ?.takeIf { it.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")) }
                        ?: throw ControlledPairFailure(stage, "INVALID_WIFI_ADDRESS")
                    stage = "GENERATE_MATERIAL"
                    record = generate(publicKey, wifi, candidate.hostId, candidate.systemBuid)
                    publicKey.fill(0)
                    verifyMaterial(record)
                    candidate = DiagnosticPairCandidate(deviceId, candidate.hostId, candidate.systemBuid, record)
                    stage = "SAVE_CANDIDATE"
                    active()
                    store.save(candidate)
                    report("Prepared pairing candidate stored=verified; retained on timeout/pending/denial")
                }
                stage = "PAIR"
                report("Pair request attempted (once)")
                val reply = exchange("Pair", record = checkNotNull(record))
                val escrow = when (val value = reply.entries["EscrowBag"]) {
                    null -> candidate.escrowBag
                    is LockdownPlistValue.Data -> value.bytes
                    else -> throw ControlledPairFailure(stage, "INVALID_ESCROW_TYPE")
                }
                report("Pair response=SUCCESS; iPhone accepted this host (no automatic Trust approval)")
                candidate = DiagnosticPairCandidate(deviceId, candidate.hostId, candidate.systemBuid,
                    record, DiagnosticPairState.PAIRED, escrow)
                stage = "SAVE_PAIR_SUCCESS"
                // Preserve a successful reply even if cancellation arrives before ValidatePair.
                store.save(candidate)
                escrow?.fill(0)
                report("Pair success record stored=verified")
            }
            stage = "VALIDATE_PAIR"
            report("ValidatePair attempted (once)")
            exchange("ValidatePair", record = checkNotNull(record))
            stage = "SAVE_VALIDATION"
            store.save(DiagnosticPairCandidate(deviceId, candidate.hostId, candidate.systemBuid,
                record, DiagnosticPairState.VALIDATED, candidate.escrowBag))
            active()
            report("ValidatePair=SUCCESS; validated record stored=verified")
        } catch (error: ControlledPairFailure) {
            throw error
        } catch (error: LinkageError) {
            throw ControlledPairFailure(stage, "API_LINKAGE_FAILURE", PairDiagnosticFailureDetails.format(stage, error), error)
        } catch (error: Exception) {
            // Provider, parser and storage exception text may contain credential data.
            val reason = when (error) {
                is IphoneUsbException -> PairDiagnosticFailureDetails.transportReason(error)
                else -> if (existingOnly && stage in setOf("LOAD_CANDIDATE", "VERIFY_MATERIAL"))
                    "PAIR_RECORD_INVALID" else "LOCAL_OR_TRANSPORT_FAILURE"
            }
            throw ControlledPairFailure(stage, reason, cause = error)
        }
    }

    private fun exchange(
        operation: String,
        key: String? = null,
        value: LockdownPlistValue? = null,
        record: LockdownPairRecord? = null,
    ): LockdownPlistValue.Dictionary {
        active()
        check(!existingOnly || operation in setOf("GetValue", "ValidatePair")) { "Existing-record request boundary" }
        val entries = linkedMapOf<String, LockdownPlistValue>(
            "Label" to LockdownPlistValue.Text("DiPlay-ControlledPair"),
            "Request" to LockdownPlistValue.Text(operation),
        )
        if (key != null) entries["Key"] = LockdownPlistValue.Text(key)
        if (value != null) entries["Value"] = value
        if (record != null) {
            entries["PairRecord"] = record.toPairRequestDictionary()
            entries["ProtocolVersion"] = LockdownPlistValue.Text("2")
            if (operation == "Pair") entries["PairingOptions"] = LockdownPlistValue.Dictionary(
                mapOf("ExtendedPairingErrors" to LockdownPlistValue.Boolean(true)))
        }
        val reply = request(LockdownPlistValue.Dictionary(entries))
        if ((reply.entries["Request"] as? LockdownPlistValue.Text)?.value != operation) {
            throw ControlledPairFailure(stage, "RESPONSE_REQUEST_MISMATCH")
        }
        if (reply.entries.containsKey("Error")) {
            val reason = when ((reply.entries["Error"] as? LockdownPlistValue.Text)?.value) {
                "PairingDialogResponsePending" -> "TRUST_PENDING"
                "UserDeniedPairing" -> "TRUST_DENIED"
                "PasswordProtected" -> "PHONE_LOCKED"
                "InvalidHostID" -> "INVALID_HOST_ID"
                "InvalidPairRecord" -> "INVALID_PAIR_RECORD"
                else -> "REMOTE_ERROR"
            }
            report("LOCKDOWN_ERROR_RESPONSE operation=$operation safeCode=$reason")
            if (operation == "ValidatePair") report("VALIDATEPAIR_REJECTED safeCode=$reason")
            throw ControlledPairFailure(stage, reason)
        }
        val result = reply.entries["Result"]
        if (result != null && (result as? LockdownPlistValue.Text)?.value != "Success") {
            throw ControlledPairFailure(stage, "INVALID_RESULT")
        }
        return reply
    }
}
