package com.shilapi.xcertplay.transport

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider

/** Validate the diagnostic certificate chain and key association without starting TLS. */
object DiagnosticPairMaterial {
    fun verify(record: LockdownPairRecord) {
        val provider = BouncyCastleProvider()
        val factory = CertificateFactory.getInstance("X.509", provider)
        fun certificate(bytes: ByteArray) =
            (factory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate).also { it.checkValidity() }
        val root = certificate(record.rootCertificatePem)
        val host = certificate(record.hostCertificatePem)
        val device = certificate(record.deviceCertificatePem)
        check(root.subjectX500Principal == root.issuerX500Principal &&
            host.issuerX500Principal == root.subjectX500Principal &&
            device.issuerX500Principal == root.subjectX500Principal)
        check(root.basicConstraints >= 0 && host.basicConstraints == -1 && device.basicConstraints == -1)
        // X509Certificate.verify(PublicKey, Provider) is unavailable before API 24.
        root.verify(root.publicKey)
        host.verify(root.publicKey)
        device.verify(root.publicKey)
        val devicePem = record.devicePublicKeyPem
        try {
            val encoded = devicePem.toString(Charsets.US_ASCII)
                .removePrefix("-----BEGIN RSA PUBLIC KEY-----").trim()
                .removeSuffix("-----END RSA PUBLIC KEY-----").trim()
            val original = org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(Base64Compat.decode(encoded))
            val certificateKey = device.publicKey as? RSAPublicKey ?: error("Device key not RSA")
            check(original.modulus == certificateKey.modulus && original.publicExponent == certificateKey.publicExponent) {
                "Device certificate mismatch"
            }
        } finally { devicePem.fill(0) }
        for ((pem, cert) in listOf(record.rootPrivateKeyPem to root, record.hostPrivateKeyPem to host)) {
            var der: ByteArray? = null
            try {
                val encoded = pem.toString(Charsets.US_ASCII)
                    .removePrefix("-----BEGIN PRIVATE KEY-----").trim()
                    .removeSuffix("-----END PRIVATE KEY-----").trim()
                der = Base64Compat.decode(encoded)
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
                val challenge = byteArrayOf(0x50, 0x41, 0x49, 0x52)
                val signature = Signature.getInstance("SHA256withRSA").run {
                    initSign(key); update(challenge); sign()
                }
                check(Signature.getInstance("SHA256withRSA").run {
                    initVerify(cert.publicKey); update(challenge); verify(signature)
                }) { "Pair identity mismatch" }
            } finally {
                pem.fill(0)
                der?.fill(0)
            }
        }
    }
}
