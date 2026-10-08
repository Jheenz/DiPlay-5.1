package com.shilapi.xcertplay

import android.content.Context
import android.security.KeyPairGeneratorSpec
import com.shilapi.xcertplay.transport.DiagnosticPairCandidate
import com.shilapi.xcertplay.transport.DiagnosticPairState
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Key
import java.security.PublicKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

typealias DiagnosticPairStore = com.shilapi.xcertplay.transport.DiagnosticPairStore

interface DiagnosticPairStorageKeys {
    fun publicKey(): PublicKey
    fun privateKey(): Key
}

/** Separate from normal startup's record slot; no imports, overwrites or silent regeneration. */
class AndroidDiagnosticPairStore(
    context: Context,
    private val storageKeys: DiagnosticPairStorageKeys? = null,
) : DiagnosticPairStore, LocalPairMetadataAccess {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("diplay_controlled_pair", Context.MODE_PRIVATE)

    @Synchronized override fun inspect(): LocalPairMetadata {
        val entries = prefs.all
        var aliasFailed = false
        val alias = try {
            if (storageKeys != null) true
            else KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(ALIAS)
        } catch (_: Exception) {
            aliasFailed = true
            null
        }
        val rows = entries.filterKeys { it.startsWith("device_") }.map { (entry, value) ->
            if (value !is String) PairRecordMetadata(null, null, null, false, PairMetadataStatus.WRONG_VALUE_TYPE)
            else {
                val plain = try { decrypt(value, entry) } catch (_: Exception) { null }
                if (plain == null) PairRecordMetadata(null, null, null, false, PairMetadataStatus.ENVELOPE_OR_DECRYPT_FAILED)
                else try {
                    inspectRecord(plain, entry)
                } finally { plain.fill(0) }
            }
        }
        val directory = File(app.applicationInfo.dataDir, "shared_prefs")
        return LocalPairMetadata(rows, entries.keys.count { !it.startsWith("device_") && it != "system_buid" },
            entries.containsKey("system_buid"), alias, aliasFailed,
            File(directory, "diplay_controlled_pair.xml").isFile,
            File(directory, "diplay_controlled_pair.xml.bak").isFile)
    }

    private fun inspectRecord(plain: ByteArray, entry: String): PairRecordMetadata {
        var association: Boolean? = null
        var matches: Boolean? = null
        var state: DiagnosticPairState? = null
        try {
            val header = DataInputStream(ByteArrayInputStream(plain))
            check(header.readInt() == 1)
            val device = header.readUTF()
            association = device.matches(Regex("[A-Za-z0-9-]{8,128}"))
            header.readUTF()
            header.readUTF()
            state = DiagnosticPairState.values().getOrNull(header.readInt())
            if (!association) return PairRecordMetadata(state, false, false, true, PairMetadataStatus.ASSOCIATION_INVALID)
            matches = name(device) == entry
            if (!matches) return PairRecordMetadata(state, true, false, true, PairMetadataStatus.ASSOCIATION_MISMATCH)
            val candidate = DiagnosticPairCodec.decode(plain)
            val record = candidate.record
            if (record != null) {
                try {
                    com.shilapi.xcertplay.transport.DiagnosticPairMaterial.verify(record)
                    check(record.hostId == candidate.hostId && record.systemBuid == candidate.systemBuid)
                } catch (_: Exception) {
                    return PairRecordMetadata(state, true, true, true, PairMetadataStatus.MATERIAL_INVALID)
                }
            }
            return PairRecordMetadata(state, true, true, true, PairMetadataStatus.READABLE)
        } catch (_: Exception) {
            return PairRecordMetadata(state, association, matches, true, PairMetadataStatus.CODEC_INVALID)
        }
    }

    @Synchronized override fun systemBuid(): String {
        rejectLegacy()
        val stored = prefs.getString("system_buid", null)
        if (stored != null) return stored.also { check(it.matches(UUID_PATTERN)) }
        val value = UUID.randomUUID().toString().uppercase(java.util.Locale.US)
        check(prefs.edit().putString("system_buid", value).commit()) { "Identity storage failed" }
        check(prefs.getString("system_buid", null) == value) { "Identity readback failed" }
        return value
    }

    @Synchronized override fun load(deviceId: String): DiagnosticPairCandidate? {
        rejectLegacy()
        val name = name(deviceId)
        val value = prefs.getString(name, null) ?: return null
        val plain = decrypt(value, name)
        try {
            return DiagnosticPairCodec.decode(plain).also {
                check(it.deviceId == deviceId) { "Record association failed" }
            }

        } finally { plain.fill(0) }
    }

    @Synchronized override fun acceptedRecords(): List<DiagnosticPairCandidate> {
        rejectLegacy()
        val records = mutableListOf<DiagnosticPairCandidate>()
        var prepared = false
        for (entry in prefs.all.keys.filter { it.startsWith("device_") }) {
            val plain = decrypt(checkNotNull(prefs.getString(entry, null)), entry)
            val candidate = try { DiagnosticPairCodec.decode(plain) } finally { plain.fill(0) }
            check(name(candidate.deviceId) == entry) { "Record association failed" }
            if (candidate.state == DiagnosticPairState.PREPARED) { prepared = true; continue }
            val record = checkNotNull(candidate.record)
            com.shilapi.xcertplay.transport.DiagnosticPairMaterial.verify(record)
            check(record.hostId == candidate.hostId && record.systemBuid == candidate.systemBuid)
            records += candidate
        }
        if (records.isEmpty() && prepared) throw com.shilapi.xcertplay.transport.ControlledPairFailure(
            "PAIR_RECORD_PREFLIGHT", "PAIR_RECORD_PREPARED")
        return records
    }

    @Synchronized override fun save(candidate: DiagnosticPairCandidate) {
        rejectLegacy()
        val name = name(candidate.deviceId)
        val old = load(candidate.deviceId)
        if (old != null) {
            check(old.hostId == candidate.hostId && old.systemBuid == candidate.systemBuid) { "Identity replacement refused" }
            check(candidate.state.ordinal >= old.state.ordinal) { "Record downgrade refused" }
            if (old.record != null) {
                check(candidate.record != null && DiagnosticPairCodec.sameMaterial(old, candidate)) { "Material replacement refused" }
            }
        }
        val plain = DiagnosticPairCodec.encode(candidate)
        try {
            if (old != null) {
                val previous = DiagnosticPairCodec.encode(old)
                try { if (MessageDigest.isEqual(plain, previous)) return }
                finally { previous.fill(0) }
            }
            val encrypted = encrypt(plain, name)
            check(prefs.edit().putString(name, encrypted).commit()) { "Protected record commit failed" }
            val saved = checkNotNull(prefs.getString(name, null)) { "Record readback absent" }
            val readback = decrypt(saved, name)
            try { check(MessageDigest.isEqual(plain, readback)) { "Record readback mismatch" } }
            finally { readback.fill(0) }
        } finally { plain.fill(0) }
    }

    private fun rejectLegacy() {
        check(!app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).contains("lockdown_host_id")) {
            "Legacy record exists; explicit migration required"
        }
    }

    private fun name(deviceId: String): String {
        require(deviceId.matches(Regex("[A-Za-z0-9-]{8,128}")))
        return "device_" + MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Suppress("DEPRECATION")
    private fun keyStore(create: Boolean): KeyStore {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            check(create && prefs.all.keys.none { it.startsWith("device_") }) { "Storage key unavailable; records retained" }
            val now = System.currentTimeMillis()
            val spec = KeyPairGeneratorSpec.Builder(app)
                .setAlias(ALIAS).setKeySize(2048)
                .setSubject(X500Principal("CN=DiPlay Controlled Pair Storage"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(Date(now - 86_400_000L))
                .setEndDate(Date(now + 30L * 365 * 86_400_000L)).build()
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        return store
    }

    private fun encrypt(plain: ByteArray, name: String): String {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(plain)
            val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            rsa.init(Cipher.ENCRYPT_MODE, storageKeys?.publicKey() ?: keyStore(true).getCertificate(ALIAS).publicKey)
            val wrapped = rsa.doFinal(key)
            val envelope = ByteArrayOutputStream().apply {
                DataOutputStream(this).use { out ->
                    out.writeInt(1); out.writeInt(wrapped.size); out.write(wrapped)
                    out.write(iv); out.writeInt(encrypted.size); out.write(encrypted)
                }
            }.toByteArray()
            return android.util.Base64.encodeToString(envelope, android.util.Base64.NO_WRAP)
        } finally { key.fill(0) }
    }

    private fun decrypt(encoded: String, name: String): ByteArray {
        check(encoded.length <= 256 * 1024) { "Protected record too large" }
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        check(input.readInt() == 1) { "Unknown storage format" }
        val wrappedSize = input.readInt()
        check(wrappedSize == 256) { "Invalid wrapped key length" }
        val wrapped = ByteArray(wrappedSize).also(input::readFully)
        val iv = ByteArray(12).also(input::readFully)
        val size = input.readInt()
        check(size in 16..128 * 1024 && size == input.available()) { "Invalid encrypted record size" }
        val encrypted = ByteArray(size).also(input::readFully)
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.DECRYPT_MODE, storageKeys?.privateKey() ?: keyStore(false).getKey(ALIAS, null))
        val key = rsa.doFinal(wrapped)
        try {
            check(key.size == 32)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(name.toByteArray(Charsets.UTF_8)); doFinal(encrypted)
            }
        } finally { key.fill(0) }
    }

    private companion object {
        const val ALIAS = "diplay-controlled-pair-storage-v1"
        val UUID_PATTERN = Regex("[0-9A-F]{8}(-[0-9A-F]{4}){3}-[0-9A-F]{12}")
    }
}

internal object DiagnosticPairCodec {
    fun encode(candidate: DiagnosticPairCandidate): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).use { out ->
            out.writeInt(1)
            out.writeUTF(candidate.deviceId); out.writeUTF(candidate.hostId); out.writeUTF(candidate.systemBuid)
            out.writeInt(candidate.state.ordinal)
            val record = candidate.record
            out.writeBoolean(record != null)
            if (record != null) {
                check(record.hostId == candidate.hostId && record.systemBuid == candidate.systemBuid)
                out.writeUTF(record.wifiMacAddress)
                listOf(record.devicePublicKeyPem, record.deviceCertificatePem, record.hostPrivateKeyPem,
                    record.hostCertificatePem, record.rootPrivateKeyPem, record.rootCertificatePem).forEach { bytes ->
                    try { check(bytes.size in 1..16 * 1024); out.writeInt(bytes.size); out.write(bytes) }
                    finally { bytes.fill(0) }
                }
            } else check(candidate.state == DiagnosticPairState.PREPARED)
            val escrow = candidate.escrowBag
            try {
                check(escrow == null || escrow.size in 1..64 * 1024)
                out.writeInt(escrow?.size ?: 0)
                if (escrow != null) out.write(escrow)
            } finally { escrow?.fill(0) }
        }
    }.toByteArray().also { check(it.size <= 128 * 1024) }

    fun decode(bytes: ByteArray): DiagnosticPairCandidate {
        check(bytes.size <= 128 * 1024)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        check(input.readInt() == 1)
        val device = input.readUTF()
        val host = input.readUTF()
        val buid = input.readUTF()
        check(device.matches(Regex("[A-Za-z0-9-]{8,128}")))
        val uuid = Regex("[0-9A-F]{8}(-[0-9A-F]{4}){3}-[0-9A-F]{12}")
        check(host.matches(uuid) && buid.matches(uuid))
        val state = DiagnosticPairState.values().getOrNull(input.readInt()) ?: error("Invalid record state")
        fun readBuffer(maximum: Int): ByteArray {
            val count = input.readInt()
            check(count in 1..maximum && count <= input.available())
            return ByteArray(count).also(input::readFully)
        }
        val record = if (input.readBoolean()) {
            val wifi = input.readUTF()
            val buffers = (0..5).map { readBuffer(16 * 1024) }
            try {
                LockdownPairRecord.restore(host, buid, wifi, buffers[0], buffers[1], buffers[2],
                    buffers[3], buffers[4], buffers[5])
            } finally { buffers.forEach { it.fill(0) } }
        } else null
        check(record != null || state == DiagnosticPairState.PREPARED)
        val count = input.readInt()
        check(count in 0..64 * 1024 && count <= input.available())
        val escrow = if (count > 0) ByteArray(count).also(input::readFully) else null
        check(input.available() == 0)
        return try { DiagnosticPairCandidate(device, host, buid, record, state, escrow) }
        finally { escrow?.fill(0) }
    }

    fun sameMaterial(first: DiagnosticPairCandidate, second: DiagnosticPairCandidate): Boolean {
        val a = encode(DiagnosticPairCandidate(first.deviceId, first.hostId, first.systemBuid, first.record))
        val b = encode(DiagnosticPairCandidate(second.deviceId, second.hostId, second.systemBuid, second.record))
        return try { MessageDigest.isEqual(a, b) } finally { a.fill(0); b.fill(0) }
    }
}
