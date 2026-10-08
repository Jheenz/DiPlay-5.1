package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ModernStartSessionDiagnosticTest {
    private class Fixture(val tlsMode: Boolean = false) : ReadOnlyLockdownAccess, ReadOnlyLockdownConnection, StartSessionConnection, AcceptedSessionRecords, LockdownTlsUpgrade {
        val record = LockdownPairRecord.restore("SECRET_HOST", "SECRET_BUID", "aa:bb:cc:dd:ee:ff",
            ByteArray(1) { 1 }, ByteArray(1) { 1 }, ByteArray(1) { 1 }, ByteArray(1) { 1 }, ByteArray(1) { 1 }, ByteArray(1) { 1 })
        var candidate = DiagnosticPairCandidate(DEVICE, record.hostId, record.systemBuid, record, DiagnosticPairState.PAIRED)
        var inventoryRecords = listOf(candidate)
        var associated: DiagnosticPairCandidate? = candidate
        var devices = listOf(claimTestDevice())
        var config = 5
        var claim = true
        var release = true
        var materialValid = true
        var ssl: LockdownPlistValue? = LockdownPlistValue.Boolean(false)
        var id: LockdownPlistValue? = LockdownPlistValue.Text("SECRET_SESSION")
        var failureAt: String? = null
        var remoteAt: String? = null
        var error = "InvalidHostID"
        var result: LockdownPlistValue? = null
        var echo = true
        var identity = DEVICE
        var storeFailure = false
        var after: ((String) -> Unit)? = null
        val calls = mutableListOf<String>()
        val requests = mutableListOf<LockdownPlistValue.Dictionary>()
        val diagnostic = ModernStartSessionDiagnostic(this, this, verify = { check(materialValid) { "SECRET material failure" } }, tlsRoundTrip = tlsMode)
        var handshakeFailure: Exception? = null
        var peer = LockdownPeerValidation(true, true, 1, true, true, true, "PAIRED_DEVICE_KEY")
        var tlsQueryType = "com.apple.mobile.lockdown"
        var tlsTransportAt: String? = null
        var tlsErrorAt: String? = null
        val tlsRequests = mutableListOf<LockdownPlistValue.Dictionary>()
        var tlsRecord: LockdownPairRecord? = null
        override fun startSessionTls(record: LockdownPairRecord, peerReport: (LockdownPeerValidation) -> Unit) {
            call("tls")
            tlsRecord = record
            peerReport(peer)
            handshakeFailure?.let { throw it }
            if (!peer.passed) throw LockdownPeerValidationException(peer)
        }
        override fun tlsSessionRequest(message: LockdownPlistValue.Dictionary): LockdownPlistValue.Dictionary {
            tlsRequests += message
            val op = op(message)
            assertTrue(op, op in setOf("QueryType", "StopSession"))
            call("tls$op")
            if (tlsTransportAt == op) throw IphoneUsbException.DeviceUnavailable("SECRET tls transport")
            return LockdownPlistValue.Dictionary(linkedMapOf<String, LockdownPlistValue>("Request" to LockdownPlistValue.Text(op)).apply {
                if (op == "QueryType") put("Type", LockdownPlistValue.Text(tlsQueryType))
                if (tlsErrorAt == op) put("Error", LockdownPlistValue.Text("SECRET remote"))
            })
        }
        private fun call(name: String) {
            calls += name
            if (failureAt == name) throw IphoneUsbException.DeviceUnavailable("SECRET arbitrary transport text")
            after?.invoke(name)
        }
        override fun acceptedRecords(): List<DiagnosticPairCandidate> {
            if (storeFailure) error("SECRET decryption error")
            return inventoryRecords
        }
        override fun load(deviceId: String) = associated
        override fun devices() = devices
        override fun open(device: PassiveUsbDevice): ReadOnlyLockdownConnection { call("open"); return this }
        override fun configuration(report: (String) -> Unit): Int { call("get"); return config }
        override fun claimUsbMux(): Boolean { call("claim"); return claim }
        override fun initialize(report: (String) -> Unit) { call("init") }
        override fun connectLockdown() { call("connect") }
        override fun queryType(): String { call("query"); return "com.apple.mobile.lockdown" }
        override fun productType(): String = error("Not allowed")
        override fun sessionRequest(message: LockdownPlistValue.Dictionary, report: (String) -> Unit): LockdownPlistValue.Dictionary {
            requests += message
            val op = (message.entries.getValue("Request") as LockdownPlistValue.Text).value
            assertTrue(op, op in setOf("GetValue", "StartSession", "StopSession"))
            call(op)
            return LockdownPlistValue.Dictionary(linkedMapOf<String, LockdownPlistValue>(
                "Request" to LockdownPlistValue.Text(if (echo) op else "WRONG"),
            ).apply {
                if (remoteAt == op) put("Error", LockdownPlistValue.Text(error))
                if (op == "GetValue") put("Value", LockdownPlistValue.Text(identity))
                if (op == "StartSession") {
                    ssl?.let { put("EnableSessionSSL", it) }
                    id?.let { put("SessionID", it) }
                    result?.let { put("Result", it) }
                }
            })
        }
        override fun sessionTransportState() = "TCP_OPEN=true"
        override fun releaseUsbMux(): Boolean { call("release"); return release }
        override fun close() { call("close") }
        fun run(): String = diagnostic.run().also { report ->
            for (secret in listOf("SECRET", DEVICE, record.hostId, record.systemBuid)) assertFalse(report, report.contains(secret))
            if (tlsMode) {
                assertTrue(report, report.contains("Pair=0 ValidatePair=0 SetValue=0 identityGeneration=0 persistenceWrites=0"))
                assertTrue(report.contains(ModernStartSessionDiagnostic.TLS_ZERO_COUNTERS))
            } else assertTrue(report.contains(ModernStartSessionDiagnostic.COUNTERS))
            assertTrue((requests + tlsRequests).none { op(it) in setOf("Pair", "ValidatePair", "SetValue", "StartService") })
            assertTrue(requests.count { op(it) == "StartSession" } <= 1)
        }
    }
    @Test fun pairedAndValidatedRecordsUseOneSessionAndPlaintextStopWithoutPersistenceMutation() {
        for (state in listOf(DiagnosticPairState.PAIRED, DiagnosticPairState.VALIDATED)) {
            val f = Fixture().apply {
                candidate = DiagnosticPairCandidate(DEVICE, record.hostId, record.systemBuid, record, state)
                inventoryRecords = listOf(candidate); associated = candidate
            }
            val original = f.candidate
            val report = f.run()
            assertTrue(report, report.contains(ControlledStartSession.SSL_NOT_REQUIRED))
            assertTrue(report.contains("SessionID present=true; valid=true; EnableSessionSSL=false"))
            assertTrue(report.contains("StopSession succeeded=true"))
            assertEquals(listOf("open", "get", "claim", "init", "connect", "query", "GetValue", "StartSession", "StopSession", "release", "close"), f.calls)
            assertSame(original, f.associated)
            assertEquals(state, f.associated?.state)
            val start = f.requests.single { op(it) == "StartSession" }
            assertEquals(setOf("Label", "Request", "HostID", "SystemBUID"), start.entries.keys)
            assertEquals(LockdownPlistValue.Text(f.record.hostId), start.entries["HostID"])
            assertEquals(LockdownPlistValue.Text(f.record.systemBuid), start.entries["SystemBUID"])
            assertEquals(LockdownPlistValue.Text("SECRET_SESSION"), f.requests.last().entries["SessionID"])
            f.run()
            assertEquals(1, f.requests.count { op(it) == "StartSession" })
        }
    }
    @Test fun sslTrueStopsWithoutPlaintextStopTlsOrServices() {
        val f = Fixture().apply { ssl = LockdownPlistValue.Boolean(true) }
        val report = f.run()
        assertTrue(report, report.contains(ControlledStartSession.TLS_REQUIRED))
        assertTrue(report.contains("STARTSESSION SUCCEEDED — TLS REQUIRED — SESSION STOP NOT CONFIRMED"))
        assertEquals(listOf("GetValue", "StartSession"), f.requests.map(::op))
        assertEquals(listOf("release", "close"), f.calls.takeLast(2))
    }
    @Test fun missingPreparedMultipleUnreadableAndInvalidMaterialFailBeforeUsb() {
        for (mode in listOf("missing", "prepared", "multiple", "decrypt", "material", "identity")) {
            val f = Fixture().apply {
                when (mode) {
                    "missing" -> inventoryRecords = emptyList()
                    "prepared" -> inventoryRecords = listOf(DiagnosticPairCandidate(DEVICE, record.hostId, record.systemBuid, record))
                    "multiple" -> inventoryRecords = listOf(candidate, candidate)
                    "decrypt" -> storeFailure = true
                    "material" -> materialValid = false
                    "identity" -> inventoryRecords = listOf(DiagnosticPairCandidate(DEVICE, "wrong", record.systemBuid, record, DiagnosticPairState.PAIRED))
                }
            }
            assertTrue(f.run().contains(ControlledStartSession.ASSOCIATION))
            assertTrue(f.calls.isEmpty())
            assertTrue(f.requests.isEmpty())
        }
    }
    @Test fun unknownMissingOrMismatchedLiveAssociationNeverStartsSession() {
        for (mode in listOf("invalid", "missing", "different", "changed")) {
            val f = Fixture().apply {
                when (mode) {
                    "invalid" -> identity = "?"
                    "missing" -> associated = null
                    "different" -> identity = "DIFFERENT0001"
                    "changed" -> associated = DiagnosticPairCandidate(DEVICE, record.hostId, record.systemBuid, record)
                }
            }
            assertTrue(f.run().contains(ControlledStartSession.ASSOCIATION))
            assertEquals(listOf("GetValue"), f.requests.map(::op))
            assertEquals(listOf("release", "close"), f.calls.takeLast(2))
        }
    }
    @Test fun malformedSessionFieldsNeverSendStopSession() {
        for (mode in listOf("noSsl", "wrongSsl", "noId", "wrongId", "emptyId", "hugeId", "badResult", "echo")) {
            val f = Fixture().apply {
                when (mode) {
                    "noSsl" -> ssl = null
                    "wrongSsl" -> ssl = LockdownPlistValue.Text("false")
                    "noId" -> id = null
                    "wrongId" -> id = LockdownPlistValue.Boolean(true)
                    "emptyId" -> id = LockdownPlistValue.Text("")
                    "hugeId" -> id = LockdownPlistValue.Text("x".repeat(1025))
                    "badResult" -> result = LockdownPlistValue.Text("Failure")
                    "echo" -> after = { if (it == "StartSession") echo = false }
                }
            }
            assertTrue(f.run().contains(ControlledStartSession.REJECTED))
            assertFalse(f.requests.any { op(it) == "StopSession" })
            assertEquals(listOf("release", "close"), f.calls.takeLast(2))
        }
    }
    @Test fun rejectedAndTransportFailuresAlwaysCloseNeverRetryAndRetainRecord() {
        for (at in listOf("open", "get", "claim", "init", "connect", "query", "GetValue", "StartSession", "StopSession", "release", "close")) {
            val f = Fixture().apply { failureAt = at }
            val report = f.run()
            assertTrue(report, report.contains(ControlledStartSession.TRANSPORT))
            assertFalse(report.contains(ControlledStartSession.SSL_NOT_REQUIRED))
            assertEquals(if (at == "open") 0 else 1, f.calls.count { it == "close" })
            assertEquals(DiagnosticPairState.PAIRED, f.associated?.state)
        }
        for (code in listOf("InvalidHostID", "PasswordProtected", "SECRET arbitrary remote text")) {
            val f = Fixture().apply { remoteAt = "StartSession"; error = code }
            assertTrue(f.run().contains(ControlledStartSession.REJECTED))
            assertFalse(f.requests.any { op(it) == "StopSession" })
            assertEquals(listOf("release", "close"), f.calls.takeLast(2))
        }
    }
    @Test fun associationAndStopErrorsDoNotMislabelTheStartSessionAsRejected() {
        val association = Fixture().apply { remoteAt = "GetValue" }
        assertTrue(association.run().contains(ControlledStartSession.ASSOCIATION))
        assertFalse(association.requests.any { op(it) == "StartSession" })
        val stop = Fixture().apply { remoteAt = "StopSession" }
        val report = stop.run()
        assertTrue(report.contains(ControlledStartSession.TRANSPORT))
        assertTrue(report.contains("stage=StopSession"))
        assertFalse(report.contains(ControlledStartSession.REJECTED))
        assertTrue(report.contains("StopSession succeeded=false"))
        assertEquals(listOf("release", "close"), stop.calls.takeLast(2))
    }
    @Test fun guardsAndCancellationStopBeforeSessionAndCleanupFailureWithholdsConfirmation() {
        for (mode in listOf("permission", "multiple", "config", "claim", "release")) {
            val f = Fixture().apply {
                when (mode) {
                    "permission" -> devices = listOf(devices.single().copy(hasPermission = false))
                    "multiple" -> devices = devices + devices.single().copy(name = "second")
                    "config" -> config = 1
                    "claim" -> claim = false
                    "release" -> release = false
                }
            }
            val report = f.run()
            assertFalse(report.contains(ControlledStartSession.SSL_NOT_REQUIRED))
            if (mode != "release") assertFalse(f.requests.any { op(it) == "StartSession" })
        }
        for (at in listOf("claim", "init", "connect", "query", "GetValue")) {
            val f = Fixture().apply { after = { if (it == at) diagnostic.deviceDetached() } }
            f.run()
            assertFalse(f.requests.any { op(it) == "StartSession" })
            assertEquals(listOf("release", "close"), f.calls.takeLast(2))
        }
    }
    @Test fun tlsModeSslTrueRunsSameStreamTlsEncryptedQueryAndStopSessionWithExistingRecord() {
        val f = Fixture(tlsMode = true).apply { ssl = LockdownPlistValue.Boolean(true) }
        val report = f.run()
        assertTrue(report, report.contains(ControlledLockdownTlsSession.CONFIRMED))
        assertTrue(report.contains("certificateValidation=PASS"))
        assertTrue(report.contains("StartSession=1 TLSHandshake=1"))
        assertTrue(report.contains("StopSession confirmed=true"))
        assertEquals(listOf("open", "get", "claim", "init", "connect", "query", "GetValue", "StartSession",
            "tls", "tlsQueryType", "tlsStopSession", "release", "close"), f.calls)
        assertEquals(listOf("GetValue", "StartSession"), f.requests.map(::op))
        assertSame(f.record, f.tlsRecord)
        val stop = f.tlsRequests.last()
        assertEquals(setOf("Label", "Request", "SessionID"), stop.entries.keys)
        assertEquals(LockdownPlistValue.Text("SECRET_SESSION"), stop.entries["SessionID"])
        assertEquals(setOf("Label", "Request"), f.tlsRequests.first().entries.keys)
        assertEquals(DiagnosticPairState.PAIRED, f.associated?.state)
    }
    @Test fun tlsModeSslFalseIsNotSubstitutedWithTls() {
        val f = Fixture(tlsMode = true)
        val report = f.run()
        assertTrue(report, report.contains(ControlledStartSession.SSL_NOT_REQUIRED))
        assertTrue(report.contains("TLS path not substituted"))
        assertFalse(f.calls.contains("tls"))
        assertTrue(f.tlsRequests.isEmpty())
        assertTrue(report.contains("TLSHandshake=0"))
    }
    @Test fun tlsModeHandshakeAndPeerFailuresStopWithoutStopSessionAndCleanUp() {
        val mismatch = LockdownPeerValidation(true, true, 1, false, false, false, "PEER_KEY_MISMATCH")
        val empty = LockdownPeerValidation(true, false, 0, false, false, false, "EMPTY_PEER_CHAIN")
        for (mode in listOf("mismatch", "empty", "rst", "eof", "handshake")) {
            val f = Fixture(tlsMode = true).apply {
                ssl = LockdownPlistValue.Boolean(true)
                when (mode) {
                    "mismatch" -> peer = mismatch
                    "empty" -> peer = empty
                    "rst" -> handshakeFailure = IphoneUsbException.DeviceUnavailable("SECRET RST")
                    "eof" -> handshakeFailure = java.io.EOFException("SECRET EOF")
                    "handshake" -> handshakeFailure = javax.net.ssl.SSLHandshakeException("SECRET alert")
                }
            }
            val report = f.run()
            val expected = if (mode == "mismatch" || mode == "empty") ControlledLockdownTlsSession.PEER_VALIDATION_FAILURE
                else ControlledLockdownTlsSession.HANDSHAKE_FAILURE
            assertTrue("$mode: $report", report.contains(expected))
            assertTrue(report.contains("SESSION STOP NOT CONFIRMED"))
            assertFalse(report.contains(ControlledLockdownTlsSession.CONFIRMED))
            assertTrue(f.tlsRequests.isEmpty())
            assertEquals(1, f.calls.count { it == "tls" })
            assertEquals(listOf("release", "close"), f.calls.takeLast(2))
        }
    }
    @Test fun tlsModeQueryAndStopFailuresAreReportedAndStillCleanUp() {
        for (mode in listOf("queryRemote", "queryType", "queryTransport", "stopRemote", "stopTransport")) {
            val f = Fixture(tlsMode = true).apply {
                ssl = LockdownPlistValue.Boolean(true)
                when (mode) {
                    "queryRemote" -> tlsErrorAt = "QueryType"
                    "queryType" -> tlsQueryType = "other"
                    "queryTransport" -> tlsTransportAt = "QueryType"
                    "stopRemote" -> tlsErrorAt = "StopSession"
                    "stopTransport" -> tlsTransportAt = "StopSession"
                }
            }
            val report = f.run()
            assertFalse(report, report.contains(ControlledLockdownTlsSession.CONFIRMED))
            assertEquals(listOf("release", "close"), f.calls.takeLast(2))
            assertEquals(1, f.requests.count { op(it) == "StartSession" })
            when (mode) {
                "queryTransport" -> assertFalse(f.tlsRequests.any { op(it) == "StopSession" })
                "stopRemote", "stopTransport" -> assertTrue(report.contains(ControlledLockdownTlsSession.STOPSESSION_FAILURE))
                else -> {
                    assertTrue(report.contains(ControlledLockdownTlsSession.QUERY_FAILURE))
                    assertTrue(report.contains("StopSession confirmed=true"))
                }
            }
            assertTrue(f.tlsRequests.count { op(it) == "StopSession" } <= 1)
        }
    }
    companion object {
        private const val DEVICE = "TESTDEVICE0001"
        private fun op(message: LockdownPlistValue.Dictionary) = (message.entries.getValue("Request") as LockdownPlistValue.Text).value
    }
}
