package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.DiagnosticPairState

enum class PairMetadataStatus {
    READABLE, WRONG_VALUE_TYPE, ENVELOPE_OR_DECRYPT_FAILED, CODEC_INVALID,
    ASSOCIATION_INVALID, ASSOCIATION_MISMATCH, MATERIAL_INVALID,
}

data class PairRecordMetadata(
    val state: DiagnosticPairState?,
    val associationPresent: Boolean?,
    val associationMatches: Boolean?,
    val decryptable: Boolean,
    val status: PairMetadataStatus,
)

data class LocalPairMetadata(
    val records: List<PairRecordMetadata>,
    val unknownEntryCount: Int,
    val identityMetadataPresent: Boolean,
    val storageAliasPresent: Boolean?,
    val aliasCheckFailed: Boolean,
    val preferenceFilePresent: Boolean,
    val preferenceBackupPresent: Boolean,
)

fun interface LocalPairMetadataAccess {
    fun inspect(): LocalPairMetadata
}

class LocalPairMetadataDiagnostic(private val access: LocalPairMetadataAccess) {
    fun run(): String = buildString {
        appendLine(TITLE)
        appendLine(SAFETY)
        appendLine("Read source=Android preferences snapshot; fresh decrypt per entry; independent disk/process reopen not verified")
        try {
            val metadata = access.inspect()
            appendLine("Record entries=${metadata.records.size}; unknown entries=${metadata.unknownEntryCount}")
            for (state in DiagnosticPairState.values()) {
                appendLine("$state count=${metadata.records.count { it.state == state }}")
            }
            appendLine("UNKNOWN state count=${metadata.records.count { it.state == null }}")
            appendLine("Storage alias present=${metadata.storageAliasPresent ?: "UNKNOWN"}; alias check failed=${metadata.aliasCheckFailed}")
            appendLine("Preferences file present=${metadata.preferenceFilePresent}; backup present=${metadata.preferenceBackupPresent}")
            appendLine("Identity metadata present=${metadata.identityMetadataPresent}")
            appendLine("Orphaned alias=${metadata.storageAliasPresent == true && metadata.records.isEmpty()}")
            appendLine("Orphaned identity metadata=${metadata.identityMetadataPresent && metadata.records.isEmpty()}")
            appendLine("Accepted readable record exists=${metadata.records.any {
                it.status == PairMetadataStatus.READABLE && it.state in listOf(DiagnosticPairState.PAIRED, DiagnosticPairState.VALIDATED)
            }}")
            metadata.records.forEachIndexed { index, row ->
                appendLine("Record ${index + 1}: state=${row.state ?: "UNKNOWN"}; " +
                    "association present=${row.associationPresent ?: "UNKNOWN"}; " +
                    "association matches=${row.associationMatches ?: "UNKNOWN"}; " +
                    "decryptable=${row.decryptable}; status=${row.status}")
            }
            appendLine("Inspection incomplete=${metadata.aliasCheckFailed || metadata.records.any { it.status != PairMetadataStatus.READABLE }}")
            appendLine("Outcome=LOCAL_INSPECTION_COMPLETE; no repair or phone test authorized")
        } catch (error: Exception) {
            appendLine("Failure stage=LOCAL_STORE_READ; status=STORE_READ_FAILED; errorClass=${error.javaClass.simpleName}; counts=UNKNOWN")
            appendLine("Outcome=STOP")
        } catch (error: LinkageError) {
            appendLine("Failure stage=LOCAL_STORE_READ; status=API_LINKAGE_FAILURE; errorClass=${error.javaClass.simpleName}; counts=UNKNOWN")
            appendLine("Outcome=STOP")
        }
        appendLine("USB=0; USBMUX=0; Lockdown=0; Pair=0; ValidatePair=0; network=0; identity generation=0; writes=0")
        appendLine(ControlledPairDiagnostic.COUNTERS)
    }

    companion object {
        const val TITLE = "Phase 3D.2P.2 - Local pairing-record metadata"
        const val SAFETY = "LOCAL READ ONLY - NO USB / NETWORK / PAIR / VALIDATE / WRITES"
        val NOT_RUN = "$TITLE: not run\n$SAFETY"
    }
}
