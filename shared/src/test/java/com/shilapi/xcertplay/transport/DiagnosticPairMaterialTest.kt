package com.shilapi.xcertplay.transport

import java.security.KeyPairGenerator
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import org.bouncycastle.asn1.pkcs.RSAPublicKey as AsnRsaPublicKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DiagnosticPairMaterialTest {
    @Test fun compiledVerifierDoesNotLinkApi24ProviderOverload() {
        val bytes = requireNotNull(DiagnosticPairMaterial::class.java.getResourceAsStream(
            "/com/shilapi/xcertplay/transport/DiagnosticPairMaterial.class")).use { it.readBytes() }
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(
            "(Ljava/security/PublicKey;Ljava/security/Provider;)V"))
        assertTrue(bytes.toString(Charsets.ISO_8859_1).contains("(Ljava/security/PublicKey;)V"))
    }

    @Test fun diagnosticProfileGeneratesParsesAndVerifiesWithBundledProviderNoTls() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        val der = AsnRsaPublicKey(key.modulus, key.publicExponent).encoded
        val pem = ("-----BEGIN RSA PUBLIC KEY-----\n" + Base64Compat.encodeMime(der) +
            "\n-----END RSA PUBLIC KEY-----\n").toByteArray(Charsets.US_ASCII)
        val record = LockdownPairRecordGenerator.generateDiagnostic(pem, "aa:bb:cc:dd:ee:ff", "TEST-HOST", "TEST-BUID")
        DiagnosticPairMaterial.verify(record)
        val legacy = LockdownPairRecordGenerator.generate(pem, "aa:bb:cc:dd:ee:ff", "TEST-HOST", "TEST-BUID")
        val legacyDer = Base64Compat.decode(legacy.rootCertificatePem.toString(Charsets.US_ASCII)
            .removePrefix("-----BEGIN CERTIFICATE-----").trim().removeSuffix("-----END CERTIFICATE-----").trim())
        val legacyTbs = org.bouncycastle.asn1.ASN1Sequence.getInstance(
            org.bouncycastle.asn1.ASN1Sequence.getInstance(legacyDer).getObjectAt(0))
        assertEquals(0, org.bouncycastle.asn1.ASN1Sequence.getInstance(legacyTbs.getObjectAt(3)).size())
        assertEquals(0, org.bouncycastle.asn1.ASN1Sequence.getInstance(legacyTbs.getObjectAt(5)).size())
        val factory = CertificateFactory.getInstance("X.509")
        for (certificate in listOf(record.rootCertificatePem, record.hostCertificatePem, record.deviceCertificatePem)) {
            factory.generateCertificate(ByteArrayInputStream(certificate)).verify(
                factory.generateCertificate(ByteArrayInputStream(record.rootCertificatePem)).publicKey)
        }
        assertEquals("LockdownPairRecord(redacted)", record.toString())
        val broken = LockdownPairRecord.restore(record.hostId, record.systemBuid, record.wifiMacAddress,
            record.devicePublicKeyPem, record.deviceCertificatePem, record.rootPrivateKeyPem,
            record.hostCertificatePem, record.rootPrivateKeyPem, record.rootCertificatePem)
        try { DiagnosticPairMaterial.verify(broken); fail("Wrong host private key") } catch (_: Exception) {}
        val wrongDevice = LockdownPairRecord.restore(record.hostId, record.systemBuid, record.wifiMacAddress,
            record.devicePublicKeyPem, record.hostCertificatePem, record.hostPrivateKeyPem,
            record.hostCertificatePem, record.rootPrivateKeyPem, record.rootCertificatePem)
        try { DiagnosticPairMaterial.verify(wrongDevice); fail("Wrong device certificate") } catch (_: Exception) {}
        val malformed = LockdownPairRecord.restore(record.hostId, record.systemBuid, record.wifiMacAddress,
            record.devicePublicKeyPem, record.deviceCertificatePem, record.hostPrivateKeyPem,
            byteArrayOf(1, 2, 3), record.rootPrivateKeyPem, record.rootCertificatePem)
        try { DiagnosticPairMaterial.verify(malformed); fail("Malformed certificate") } catch (_: Exception) {}
    }
}
