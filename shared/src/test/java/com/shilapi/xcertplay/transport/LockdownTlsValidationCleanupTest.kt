package com.shilapi.xcertplay.transport

import android.util.Base64
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3D.2T.1: real in-JVM TLS server standing in for the paired iPhone on a loopback socket.
 * Proves pinned peer validation, existing-material reuse, same-stream upgrade, encrypted
 * Lockdown round trip, StopSession inside TLS, and fail-closed handshake behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LockdownTlsValidationCleanupTest {
    private class Device(val keys: KeyPair, val record: LockdownPairRecord) {
        val certificate: X509Certificate = parse(record.deviceCertificatePem)
    }

    /** Socket-backed stream; [chunk] limits each read to model fragmented delivery. */
    private open class SocketStream(val socket: Socket, private val chunk: Int = Int.MAX_VALUE) : BlockingDuplexByteStream {
        val received = CopyOnWriteArrayList<Int>()
        var closed = false
        override fun send(data: ByteArray) { socket.getOutputStream().apply { write(data); flush() } }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            socket.soTimeout = timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val buffer = ByteArray(minOf(maxBytes, chunk))
            val count = try { socket.getInputStream().read(buffer) } catch (_: SocketTimeoutException) { return null }
            if (count < 0) return ByteArray(0)
            received += count
            return buffer.copyOf(count)
        }
        override fun close() { closed = true; socket.close() }
    }

    /** Read-ahead stream like the USBMUX TCP connection: coalesced reads, surplus pushed back. */
    private class ReadAheadStream(socket: Socket) : SocketStream(socket) {
        private var pending = ByteArray(0)
        var surplusRetained = 0
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            if (pending.isEmpty()) {
                pending = super.recv(65_536, timeoutMillis) ?: return null
                if (pending.isEmpty()) return pending
            }
            val count = minOf(maxBytes, pending.size)
            return pending.copyOf(count).also {
                pending = pending.copyOfRange(count, pending.size)
                if (pending.isNotEmpty()) surplusRetained++
            }
        }
    }

    private class Server(
        private val presented: Device,
        private val mode: String = "ok",
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val clientChain = AtomicReference<ByteArray?>()
        val tlsRequests = CopyOnWriteArrayList<LockdownPlistValue.Dictionary>()
        val plaintextRequests = CopyOnWriteArrayList<LockdownPlistValue.Dictionary>()
        val error = AtomicReference<Throwable?>()
        var accepted = 0
        private val thread = Thread {
            try { serve() } catch (t: Throwable) { error.set(t) }
        }.apply { isDaemon = true; start() }

        fun connect(): Socket = Socket(InetAddress.getLoopbackAddress(), listener.localPort)

        private fun serve() {
            val raw = listener.accept().also { accepted++ }
            listener.soTimeout = 300
            try { listener.accept(); accepted++ } catch (_: Exception) { }
            val plain = LockdownPlistChannel(SocketStream(raw))
            plaintextRequests += plain.receive()
            plain.send(dict("Request" to text("StartSession"), "SessionID" to text(SESSION),
                "EnableSessionSSL" to LockdownPlistValue.Boolean(true)))
            plain.detach()
            when (mode) {
                "rst" -> { raw.getInputStream().read(ByteArray(5)); raw.setSoLinger(true, 0); raw.close(); return }
                "eof" -> { raw.getInputStream().read(ByteArray(5)); raw.shutdownOutput(); Thread.sleep(500); raw.close(); return }
                "garbage" -> { raw.getInputStream().read(ByteArray(5)); raw.getOutputStream().write(ByteArray(64) { 0x41 }); Thread.sleep(500); raw.close(); return }
            }
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, PASSWORD)
                setKeyEntry("device", presented.keys.private, PASSWORD, arrayOf(presented.certificate))
            }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore, PASSWORD) }.keyManagers
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    clientChain.set(chain?.firstOrNull()?.encoded ?: throw CertificateException("no client cert"))
                }
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                    throw CertificateException("server only")
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(trust), null) }
            val tls = context.socketFactory.createSocket(raw, "localhost", raw.port, true) as SSLSocket
            tls.useClientMode = false
            tls.needClientAuth = true
            tls.soTimeout = 5_000
            try { tls.startHandshake() } catch (_: Exception) { tls.close(); return }
            val secure = LockdownPlistChannel(SocketStream(tls))
            while (true) {
                val request = try { secure.receive() } catch (_: Exception) { break }
                tlsRequests += request
                val op = (request.entries["Request"] as LockdownPlistValue.Text).value
                val reply = when {
                    op == "QueryType" -> dict("Request" to text(op), "Type" to text("com.apple.mobile.lockdown"))
                    op == "StopSession" && mode == "stopError" -> dict("Request" to text(op), "Error" to text("InvalidSessionID"))
                    else -> dict("Request" to text(op))
                }
                secure.send(reply)
                if (op == "StopSession") break
            }
            try { secure.receive(500) } catch (_: Exception) { }
            tls.close()
        }

        override fun close() {
            listener.close()
            thread.join(5_000)
        }
    }

    /** Test stand-in for the production same-stream upgrade (AndroidReadOnlyLockdownAccess). */
    private class Upgrade(val stream: BlockingDuplexByteStream) : LockdownTlsUpgrade {
        val plaintext = LockdownPlistChannel(stream)
        var tls: LockdownPlistChannel? = null
        var detached: BlockingDuplexByteStream? = null
        override fun startSessionTls(record: LockdownPairRecord, peerReport: (LockdownPeerValidation) -> Unit) {
            val same = plaintext.detach().also { detached = it }
            tls = LockdownPlistChannel(TlsDuplexChannel.open(same, record, 5_000, peerReport))
        }
        override fun tlsSessionRequest(message: LockdownPlistValue.Dictionary) = checkNotNull(tls).request(message)
    }

    private fun startSession(upgrade: Upgrade): String {
        val reply = upgrade.plaintext.request(dict("Label" to text("test"), "Request" to text("StartSession")))
        assertEquals(LockdownPlistValue.Boolean(true), reply.entries["EnableSessionSSL"])
        return (reply.entries.getValue("SessionID") as LockdownPlistValue.Text).value
    }

    @Test fun pairedDevicePeerAcceptedAndEncryptedRoundTripStopsSessionInsideTls() {
        val paired = device()
        val server = Server(paired)
        val stream: SocketStream
        val upgrade: Upgrade
        val engine: ControlledLockdownTlsSession
        server.use {
            stream = SocketStream(server.connect())
            upgrade = Upgrade(stream)
            engine = ControlledLockdownTlsSession(upgrade, {}) { }
            val sessionId = startSession(upgrade)
            assertEquals(ControlledLockdownTlsSession.CONFIRMED, engine.run(paired.record, sessionId))
            upgrade.tls?.close()
        }
        assertNull(server.error.get())
        assertEquals(1, server.accepted)
        assertSame(stream, upgrade.detached)
        assertEquals(listOf("StartSession"), server.plaintextRequests.map(::op))
        assertEquals("plaintext never carries StopSession", listOf("QueryType", "StopSession"), server.tlsRequests.map(::op))
        val stop = server.tlsRequests.last()
        assertEquals(setOf("Label", "Request", "SessionID"), stop.entries.keys)
        assertEquals(text(SESSION), stop.entries["SessionID"])
        assertTrue(server.tlsRequests.none { op(it) in FORBIDDEN })
        assertArrayEquals("existing RootCertificate reused as client identity", parse(paired.record.rootCertificatePem).encoded, server.clientChain.get())
    }

    @Test fun fragmentedAndCoalescedTlsBoundariesArePreservedExactlyOnce() {
        val fragmented = runConfirmed { SocketStream(it, chunk = 1) }
        assertTrue(fragmented.received.all { it == 1 })
        val coalesced = runConfirmed { ReadAheadStream(it) } as ReadAheadStream
        assertTrue("reads were coalesced and surplus retained", coalesced.surplusRetained > 0)
    }

    private fun runConfirmed(factory: (Socket) -> SocketStream): SocketStream {
        val paired = device()
        val server = Server(paired)
        val stream: SocketStream
        server.use {
            stream = factory(server.connect())
            val upgrade = Upgrade(stream)
            val engine = ControlledLockdownTlsSession(upgrade, {}) { }
            assertEquals(ControlledLockdownTlsSession.CONFIRMED, engine.run(paired.record, startSession(upgrade)))
            upgrade.tls?.close()
        }
        assertNull(server.error.get())
        assertEquals(listOf("QueryType", "StopSession"), server.tlsRequests.map(::op))
        return stream
    }

    @Test fun wrongPeerKeyIsRejectedAndNoEncryptedRequestOrStopSessionIsSent() {
        val paired = device()
        val stranger = device()
        val server = Server(stranger)
        val validations = mutableListOf<LockdownPeerValidation>()
        server.use {
            val stream = SocketStream(server.connect())
            val upgrade = Upgrade(stream)
            val reports = mutableListOf<String>()
            val engine = ControlledLockdownTlsSession(upgrade, {}) { reports += it }
            val sessionId = startSession(upgrade)
            val failure = try { engine.run(paired.record, sessionId); null } catch (e: StartSessionFailure) { e }
            assertEquals(ControlledLockdownTlsSession.PEER_VALIDATION_FAILURE, failure?.classification)
            assertEquals("PEER_KEY_MISMATCH", failure?.safeReason)
            assertTrue(reports.any { it.contains("certificateValidation=FAIL") && it.contains("peerCertificateMatch=false") })
            assertTrue(reports.any { it.contains("SESSION STOP NOT CONFIRMED") })
            assertEquals(0, engine.encryptedQueryTypeAttempts)
            assertEquals(0, engine.stopSessionAttempts)
            assertTrue("failed handshake closes the owned stream", stream.socket.isClosed)
            validations += LockdownPeerCertificateValidator.from(paired.record).validateDer(listOf(stranger.certificate.encoded))
        }
        assertTrue(server.tlsRequests.isEmpty())
        assertFalse(validations.single().passed)
    }

    @Test fun peerResetEofAndGarbageDuringHandshakeStopWithoutStopSession() {
        for (mode in listOf("rst", "eof", "garbage")) {
            val paired = device()
            val server = Server(paired, mode)
            server.use {
                val stream = SocketStream(server.connect())
                val upgrade = Upgrade(stream)
                val engine = ControlledLockdownTlsSession(upgrade, {}) { }
                val failure = try { engine.run(paired.record, startSession(upgrade)); null } catch (e: StartSessionFailure) { e }
                assertEquals(mode, ControlledLockdownTlsSession.HANDSHAKE_FAILURE, failure?.classification)
                assertEquals(1, engine.tlsHandshakeAttempts)
                assertEquals(0, engine.stopSessionAttempts)
                assertTrue(mode, stream.socket.isClosed)
            }
            assertTrue(server.tlsRequests.isEmpty())
        }
    }

    @Test fun stopSessionErrorIsReportedAndNotConfirmed() {
        val paired = device()
        val server = Server(paired, "stopError")
        server.use {
            val upgrade = Upgrade(SocketStream(server.connect()))
            val engine = ControlledLockdownTlsSession(upgrade, {}) { }
            val failure = try { engine.run(paired.record, startSession(upgrade)); null } catch (e: StartSessionFailure) { e }
            assertEquals(ControlledLockdownTlsSession.STOPSESSION_FAILURE, failure?.classification)
            assertEquals("InvalidSessionID", failure?.safeReason)
            assertFalse(engine.stopSessionConfirmed)
            assertEquals(1, engine.stopSessionAttempts)
            upgrade.tls?.close()
        }
        assertEquals(1, server.tlsRequests.count { op(it) == "StopSession" })
    }

    @Test fun validatorRejectsEmptyMalformedMismatchedAndMissingExpectedCertificate() {
        val paired = device()
        val validator = LockdownPeerCertificateValidator.from(paired.record)
        assertTrue(validator.validateDer(listOf(paired.certificate.encoded)).run { passed && peerKeyMatch && peerCertificateMatch })
        assertEquals("EMPTY_PEER_CHAIN", validator.validateDer(emptyList()).safeReason)
        assertEquals("EMPTY_PEER_CHAIN", validator.validateDer(null).safeReason)
        assertEquals("MALFORMED_PEER_CERTIFICATE", validator.validateDer(listOf(byteArrayOf(0x30, 0x03, 1, 2, 3))).safeReason)
        assertEquals("PEER_KEY_MISMATCH", validator.validateDer(listOf(device().certificate.encoded)).safeReason)
        // Root certificate is signed by the same root but carries a different key: still rejected.
        assertEquals("PEER_KEY_MISMATCH", validator.validateDer(listOf(parse(paired.record.rootCertificatePem).encoded)).safeReason)
        val missing = LockdownPeerCertificateValidator(ByteArray(0))
        assertFalse(missing.expectedDeviceCertificatePresent)
        assertEquals("EXPECTED_DEVICE_CERTIFICATE_MISSING", missing.validateDer(listOf(paired.certificate.encoded)).safeReason)
        val malformedExpected = LockdownPeerCertificateValidator("not a certificate".toByteArray())
        assertFalse(malformedExpected.validateDer(listOf(paired.certificate.encoded)).passed)
        val summary = validator.validateDer(listOf(paired.certificate.encoded)).safeSummary()
        assertFalse(summary.contains(paired.record.hostId))
        assertFalse(summary.contains("BEGIN"))
    }

    @Test fun trustManagerIsNotTrustAllAndRejectsEmptyChainAndClientUse() {
        val record = device().record
        val manager = LockdownPeerCertificateValidator.from(record).trustManager()
        for (chain in listOf(emptyArray<X509Certificate>(), null)) {
            try { manager.checkServerTrusted(chain, "RSA"); fail("empty chain must be rejected") }
            catch (_: CertificateException) { }
        }
        try { manager.checkClientTrusted(arrayOf(parse(record.deviceCertificatePem)), "RSA"); fail() }
        catch (_: CertificateException) { }
        assertEquals(0, manager.acceptedIssuers.size)
        try {
            Class.forName("com.shilapi.xcertplay.transport.LockdownTlsEngineFactory\$UsbLockdownTrustManager")
            fail("Trust-all manager must not exist")
        } catch (_: ClassNotFoundException) { }
    }

    @Test fun missingOrUnreadableMaterialStopsBeforeAnyHandshakeBytes() {
        val good = device().record
        val cases = mapOf(
            "deviceCert" to restore(good, deviceCert = byteArrayOf(0x20)),
            "rootCert" to restore(good, rootCert = byteArrayOf(0x20)),
            "rootKey" to restore(good, rootKey = byteArrayOf(0x20)),
            "badKey" to restore(good, rootKey = "-----BEGIN PRIVATE KEY-----\n!!\n-----END PRIVATE KEY-----".toByteArray()),
        )
        for ((name, record) in cases) {
            val writes = mutableListOf<ByteArray>()
            var closed = false
            val stream = object : BlockingDuplexByteStream {
                override fun send(data: ByteArray) { writes += data }
                override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = ByteArray(0)
                override fun close() { closed = true }
            }
            var report: LockdownPeerValidation? = null
            try { TlsDuplexChannel.open(stream, record, 1_000) { report = it }; fail(name) }
            catch (_: Exception) { }
            assertTrue("$name: no ClientHello without complete material", writes.isEmpty())
            assertTrue(name, closed)
            assertNotNull(name, report)
            assertFalse(name, report!!.passed)
        }
    }

    @Test fun existingMaterialIsReusedWithoutGenerationOrMutation() {
        val record = device().record
        val snapshot = material(record)
        val root = record.rootCertificatePem
        repeat(2) { LockdownTlsEngineFactory.create(record) }
        assertEquals(snapshot, material(record))
        assertArrayEquals(root, record.rootCertificatePem)
    }

    companion object {
        private const val SESSION = "SECRET-SESSION-ID"
        private val PASSWORD = "test".toCharArray()
        private val FORBIDDEN = setOf("Pair", "ValidatePair", "SetValue", "StartService")
        private fun material(r: LockdownPairRecord) = listOf(r.hostId, r.systemBuid, r.wifiMacAddress) + listOf(r.devicePublicKeyPem,
            r.deviceCertificatePem, r.hostPrivateKeyPem, r.hostCertificatePem, r.rootPrivateKeyPem, r.rootCertificatePem).map { it.toList() }
        private fun op(message: LockdownPlistValue.Dictionary) = (message.entries.getValue("Request") as LockdownPlistValue.Text).value
        private fun text(value: String) = LockdownPlistValue.Text(value)
        private fun dict(vararg entries: Pair<String, LockdownPlistValue>) = LockdownPlistValue.Dictionary(linkedMapOf(*entries))
        private fun parse(pem: ByteArray) = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem)) as X509Certificate

        private fun restore(
            record: LockdownPairRecord,
            deviceCert: ByteArray = record.deviceCertificatePem,
            rootCert: ByteArray = record.rootCertificatePem,
            rootKey: ByteArray = record.rootPrivateKeyPem,
        ) = LockdownPairRecord.restore(record.hostId, record.systemBuid, record.wifiMacAddress,
            record.devicePublicKeyPem, deviceCert, record.hostPrivateKeyPem, record.hostCertificatePem, rootKey, rootCert)

        private fun device(): Device {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val publicKey = keys.public as RSAPublicKey
            val body = derInteger(publicKey.modulus) + derInteger(publicKey.publicExponent)
            val rsa = byteArrayOf(0x30) + derLength(body.size) + body
            val pem = ("-----BEGIN RSA PUBLIC KEY-----\n" + Base64.encodeToString(rsa, Base64.NO_WRAP) +
                "\n-----END RSA PUBLIC KEY-----\n").toByteArray(Charsets.US_ASCII)
            val record = LockdownPairRecordGenerator.generateDiagnostic(pem, "aa:bb:cc:dd:ee:ff",
                "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002")
            return Device(keys, record)
        }

        private fun derInteger(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return byteArrayOf(2) + derLength(bytes.size) + bytes
        }

        private fun derLength(size: Int): ByteArray =
            if (size < 128) byteArrayOf(size.toByte()) else byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
    }
}
