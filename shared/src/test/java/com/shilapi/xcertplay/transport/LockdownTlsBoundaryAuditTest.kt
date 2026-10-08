package com.shilapi.xcertplay.transport

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LockdownTlsBoundaryAuditTest {
    private class MemoryStream(
        initial: ByteArray,
        private val receiveFailure: Exception? = null,
    ) : BlockingDuplexByteStream {
        private val inbound = initial.copyOf()
        private var offset = 0
        val writes = mutableListOf<ByteArray>()
        var closed = false
        override fun send(data: ByteArray) { check(!closed); writes += data.copyOf() }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            check(!closed)
            receiveFailure?.let { throw it }
            if (offset == inbound.size) return ByteArray(0)
            val count = minOf(maxBytes, inbound.size - offset)
            return inbound.copyOfRange(offset, offset + count).also { offset += count }
        }
        override fun close() { closed = true }
        fun unreadBytes() = inbound.copyOfRange(offset, inbound.size)
    }

    @Test fun existingRecordMaterialBuildsClientEngineWithoutRegenerationOrMutation() {
        val record = record()
        DiagnosticPairMaterial.verify(record)
        val first = LockdownTlsEngineFactory.create(record)
        val second = LockdownTlsEngineFactory.create(record)
        assertTrue(first.useClientMode)
        assertTrue(second.useClientMode)
        assertTrue(first.supportedProtocols.contains("TLSv1.2"))
        assertEquals("LockdownPairRecord(redacted)", record.toString())
    }

    @Test fun plistFrameConsumesNoTlsBoundaryBytesAndDetachPreservesSameStream() {
        val xml = "<plist version=\"1.0\"><dict><key>Request</key><string>StartSession</string></dict></plist>"
            .toByteArray(Charsets.UTF_8)
        val tail = byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x04, 1, 2, 3, 4)
        val frame = byteArrayOf(
            (xml.size shr 24).toByte(), (xml.size shr 16).toByte(),
            (xml.size shr 8).toByte(), xml.size.toByte(),
        ) + xml + tail
        val stream = MemoryStream(frame)
        val channel = LockdownPlistChannel(stream)
        val response = channel.receive()
        assertEquals("StartSession", (response.entries.getValue("Request") as LockdownPlistValue.Text).value)
        val upgraded = channel.detach()
        assertSame(stream, upgraded)
        assertArrayEquals(tail, stream.unreadBytes())
    }

    @Test fun tlsHandshakeIsClientInitiatedOnOwnedStreamAndFailureClosesIt() {
        val stream = MemoryStream(byteArrayOf(0x15, 0x03, 0x03, 0, 2, 2, 40))
        try {
            TlsDuplexChannel.open(stream, record(), 1_000)
            fail("Invalid handshake response must fail closed")
        } catch (_: Exception) {
            assertFalse(stream.writes.isEmpty())
            assertEquals("ClientHello starts the TLS handshake", 0x16, stream.writes.first()[0].toInt() and 0xff)
            assertTrue(stream.closed)
        }
    }

    @Test fun resetAndEofDuringHandshakeFailAndCloseTheSameStream() {
        val reset = MemoryStream(byteArrayOf(), IphoneUsbException.DeviceUnavailable("peer reset"))
        try {
            TlsDuplexChannel.open(reset, record(), 1_000)
            fail("Reset must stop handshake")
        } catch (_: Exception) {
            assertFalse(reset.writes.isEmpty())
            assertTrue(reset.closed)
        }
        val eof = MemoryStream(ByteArray(0))
        try {
            TlsDuplexChannel.open(eof, record(), 1_000)
            fail("EOF must stop handshake")
        } catch (_: Exception) {
            assertFalse(eof.writes.isEmpty())
            assertTrue(eof.closed)
        }
    }

    @Test fun lockdownTrustManagerRejectsAnEmptyPeerCertificateChain() {
        val manager = LockdownPeerCertificateValidator.from(record()).trustManager()
        try {
            manager.checkServerTrusted(emptyArray(), "RSA")
            fail("Phase 3D.2T.1: empty peer chains must be rejected")
        } catch (_: java.security.cert.CertificateException) { }
    }

    private fun record(): LockdownPairRecord {
        val publicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            .generateKeyPair().public as RSAPublicKey
        val modulus = derInteger(publicKey.modulus)
        val exponent = derInteger(publicKey.publicExponent)
        val body = modulus + exponent
        val rsa = byteArrayOf(0x30) + derLength(body.size) + body
        val pem = ("-----BEGIN RSA PUBLIC KEY-----\n" + Base64.encodeToString(rsa, Base64.NO_WRAP) +
            "\n-----END RSA PUBLIC KEY-----\n").toByteArray(Charsets.US_ASCII)
        return LockdownPairRecordGenerator.generateDiagnostic(
            pem,
            "aa:bb:cc:dd:ee:ff",
            "00000000-0000-0000-0000-000000000001",
            "00000000-0000-0000-0000-000000000002",
        ).also { pem.fill(0) }
    }

    private fun derInteger(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return byteArrayOf(2) + derLength(bytes.size) + bytes
    }

    private fun derLength(size: Int): ByteArray =
        if (size < 128) byteArrayOf(size.toByte())
        else byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
}
