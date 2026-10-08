package com.shilapi.xcertplay.transport

import android.annotation.SuppressLint
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/** Safe, non-identifying metadata about one Lockdown TLS peer validation. */
data class LockdownPeerValidation(
    val expectedDeviceCertificatePresent: Boolean,
    val peerCertificatePresent: Boolean,
    val peerCertificateCount: Int,
    val peerKeyMatch: Boolean,
    val peerCertificateMatch: Boolean,
    val passed: Boolean,
    val safeReason: String,
) {
    fun safeSummary(): String =
        "expectedDeviceCertificatePresent=$expectedDeviceCertificatePresent; " +
            "peerCertificatePresent=$peerCertificatePresent; peerCertificateCount=$peerCertificateCount; " +
            "peerKeyMatch=$peerKeyMatch; peerCertificateMatch=$peerCertificateMatch; " +
            "certificateValidation=${if (passed) "PASS" else "FAIL"}; reason=$safeReason"

    companion object {
        val NOT_EVALUATED = LockdownPeerValidation(false, false, 0, false, false, false, "NOT_EVALUATED")
    }
}

class LockdownPeerValidationException(val validation: LockdownPeerValidation) :
    CertificateException("Lockdown peer validation failed: ${validation.safeReason}")

/**
 * Authenticates the Lockdown TLS server as the paired iPhone.
 *
 * The accepted pair record contains the device's own RSA public key (Lockdown GetValue
 * DevicePublicKey at Pair time) and the DeviceCertificate the host issued for that key. The TLS
 * server's leaf must carry exactly that public key; the TLS handshake then proves possession of the
 * paired device's private key. No Web PKI, hostname, or time-based policy is applied. Missing,
 * empty, malformed, or different-key chains are rejected.
 */
class LockdownPeerCertificateValidator(expectedDeviceCertificatePem: ByteArray) {
    private val expectedCertificateDer: ByteArray?
    private val expectedPublicKey: ByteArray?
    @Volatile var lastValidation: LockdownPeerValidation = LockdownPeerValidation.NOT_EVALUATED
        private set

    init {
        val parsed = try {
            if (expectedDeviceCertificatePem.isEmpty()) null else parse(expectedDeviceCertificatePem)
        } catch (_: Exception) {
            null
        } finally {
            expectedDeviceCertificatePem.fill(0)
        }
        expectedCertificateDer = parsed?.encoded
        expectedPublicKey = parsed?.publicKey?.encoded
    }

    val expectedDeviceCertificatePresent: Boolean get() = expectedPublicKey != null

    /** Validates a peer chain given as DER-encoded certificates (leaf first). */
    fun validateDer(chain: List<ByteArray>?): LockdownPeerValidation {
        val count = chain?.size ?: 0
        fun result(keyMatch: Boolean, certMatch: Boolean, reason: String) = LockdownPeerValidation(
            expectedDeviceCertificatePresent, count > 0, count, keyMatch, certMatch, reason == "PAIRED_DEVICE_KEY",
            reason,
        ).also { lastValidation = it }
        val expectedKey = expectedPublicKey ?: return result(false, false, "EXPECTED_DEVICE_CERTIFICATE_MISSING")
        if (chain.isNullOrEmpty()) return result(false, false, "EMPTY_PEER_CHAIN")
        val leaf = try { parse(chain[0]) } catch (_: Exception) {
            return result(false, false, "MALFORMED_PEER_CERTIFICATE")
        }
        val leafKey = leaf.publicKey?.encoded ?: return result(false, false, "MALFORMED_PEER_CERTIFICATE")
        val keyMatch = MessageDigest.isEqual(leafKey, expectedKey)
        val certMatch = expectedCertificateDer != null && MessageDigest.isEqual(leaf.encoded, expectedCertificateDer)
        return result(keyMatch, certMatch, if (keyMatch) "PAIRED_DEVICE_KEY" else "PEER_KEY_MISMATCH")
    }

    @Throws(CertificateException::class)
    fun requireValid(chain: List<ByteArray>?) {
        val validation = validateDer(chain)
        if (!validation.passed) throw LockdownPeerValidationException(validation)
    }

    /** Re-checks the completed session's peer chain; anonymous or unverified sessions fail. */
    @Throws(CertificateException::class)
    fun requireValidSession(session: SSLSession) {
        val certificates = try {
            session.peerCertificates
        } catch (_: SSLPeerUnverifiedException) {
            null
        }
        requireValid(certificates?.map { it.encoded })
    }

    fun trustManager(): X509TrustManager = PinnedTrustManager(this)

    @SuppressLint("CustomX509TrustManager")
    private class PinnedTrustManager(private val validator: LockdownPeerCertificateValidator) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Lockdown TLS is client mode only")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val encoded = try {
                chain?.map { it.encoded }
            } catch (_: Exception) {
                listOf(ByteArray(0))
            }
            validator.requireValid(encoded)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        fun from(record: LockdownPairRecord) = LockdownPeerCertificateValidator(record.deviceCertificatePem)

        private fun parse(bytes: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }
}
