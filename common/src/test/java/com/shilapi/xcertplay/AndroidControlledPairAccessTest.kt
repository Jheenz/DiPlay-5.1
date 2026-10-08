package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.DiagnosticPairCandidate
import com.shilapi.xcertplay.transport.DiagnosticPairState
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.LockdownPairRecordGenerator
import android.util.Base64
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
class AndroidControlledPairAccessTest {
    private class Store(record: LockdownPairRecord) : DiagnosticPairStore {
        var current = DiagnosticPairCandidate(DEVICE, record.hostId, record.systemBuid, record)
        override fun load(deviceId: String) = current
        override fun systemBuid() = current.systemBuid
        override fun save(candidate: DiagnosticPairCandidate) { current = candidate }
        override fun acceptedRecords() = if (current.state == DiagnosticPairState.PREPARED) emptyList() else listOf(current)
    }
    private fun fixture(store: Store, failure: String? = null): AndroidReadOnlyLockdownAccessTest.Fixture =
        AndroidReadOnlyLockdownAccessTest.Fixture().apply {
            controlledReply = { xml ->
                val op = Regex("<key>Request</key>\\s*<string>([^<]+)</string>").find(xml)!!.groupValues[1]
                assertTrue(op, op in setOf("QueryType", "GetValue", "SetValue", "Pair", "ValidatePair"))
                for (forbidden in listOf("StartSession", "StartService", "ValidateSession", "com.apple.carkit.service",
                    "StopSession", "Unpair")) assertFalse(xml.contains(forbidden))
                val fields = when {
                    op == failure -> "<key>Error</key><string>PairingDialogResponsePending</string>"
                    op == "QueryType" -> "<key>Type</key><string>com.apple.mobile.lockdown</string>"
                    op == "GetValue" -> "<key>Value</key><string>$DEVICE</string>"
                    else -> ""
                }
                "<plist version=\"1.0\"><dict><key>Request</key><string>$op</string>$fields</dict></plist>"
            }
        }
    @Test fun realFramingScopedTransportOnePairOneValidationAndNoSessionCleanup() {
        val store = Store(record())
        val f = fixture(store)
        val report = ControlledPairDiagnostic(f.access, store).run()
        assertTrue(report, report.contains("Outcome=PASS"))
        assertEquals(DiagnosticPairState.VALIDATED, store.current.state)
        assertEquals(listOf("QueryType", "GetValue", "SetValue", "Pair", "ValidatePair"), f.requests.map {
            Regex("<key>Request</key>\\s*<string>([^<]+)</string>").find(it)!!.groupValues[1]
        })
        assertEquals(1, f.writes.count { it.size == 20 && it[3] == 0.toByte() })
        assertEquals(1, f.writes.count { it.size == 17 && it[3] == 2.toByte() })
        assertEquals(1, f.writes.count { it.size == 36 && it[29] == 2.toByte() })
        f.verifyCleanup()
        assertFalse(report.contains("CERTIFICATE"))
        assertFalse(report.contains("PRIVATE KEY"))
        assertFalse(report.contains(DEVICE))
    }
    @Test fun pendingOrValidationErrorStopsNoReconnectAndStillReleasesCloses() {
        for (failure in listOf("Pair", "ValidatePair")) {
            val store = Store(record())
            val f = fixture(store, failure)
            val report = ControlledPairDiagnostic(f.access, store).run()
            assertFalse(report, report.contains("Outcome=PASS"))
            assertTrue(report.contains("TRUST_PENDING"))
            assertEquals(1, f.requests.count { it.contains("<string>Pair</string>") })
            assertEquals(if (failure == "Pair") 0 else 1, f.requests.count { it.contains("<string>ValidatePair</string>") })
            assertEquals(1, f.writes.count { it.size == 36 && it[29] == 2.toByte() })
            f.verifyCleanup()
        }
    }
    @Test fun acceptedRecordValidationOnlyAndCleanupFailureNeverPasses() {
        val store = Store(record()).apply {
            current = DiagnosticPairCandidate(current.deviceId, current.hostId, current.systemBuid,
                current.record, DiagnosticPairState.VALIDATED)
        }
        val f = fixture(store).apply { finError = true }
        val report = ControlledPairDiagnostic(f.access, store).run()
        assertFalse(report.contains("Outcome=PASS"))
        assertFalse(f.requests.any { it.contains("<string>Pair</string>") || it.contains("<string>SetValue</string>") })
        assertEquals(1, f.requests.count { it.contains("<string>ValidatePair</string>") })
        f.verifyCleanup()
    }

    @Test fun noPairDiagnosticMissingOrPreparedBlocksBeforeUsbOpen() {
        for (state in listOf(null, DiagnosticPairState.PREPARED)) {
            val store = Store(record())
            val f = fixture(store)
            val guarded = object : DiagnosticPairStore by store {
                override fun acceptedRecords() = if (state == null) emptyList() else listOf(store.current)
            }
            val report = ExistingPairValidationDiagnostic(f.access, guarded).run()
            assertTrue(report, report.contains(if (state == null) "PAIR_RECORD_NOT_FOUND" else "PAIR_RECORD_PREPARED"))
            assertTrue(f.writes.isEmpty())
            assertTrue(f.requests.isEmpty())
            org.mockito.Mockito.verify(f.base.manager, org.mockito.Mockito.never()).openDevice(f.base.device)
        }
    }

    @Test fun noPairDiagnosticAcceptedRecordRunsOneValidationOnlyAndReportsState() {
        for (state in listOf(DiagnosticPairState.PAIRED, DiagnosticPairState.VALIDATED)) {
            val store = Store(record()).apply {
                current = DiagnosticPairCandidate(current.deviceId, current.hostId, current.systemBuid, current.record, state)
            }
            val f = fixture(store)
            val diagnostic = ExistingPairValidationDiagnostic(f.access, store)
            val report = diagnostic.run()
            assertTrue(report, report.contains(ExistingPairValidationDiagnostic.PASS))
            assertEquals(listOf("QueryType", "GetValue", "ValidatePair"), f.requests.map {
                Regex("<key>Request</key>\\s*<string>([^<]+)</string>").find(it)!!.groupValues[1]
            })
            assertTrue(report.contains("Pair count = 0"))
            assertTrue(report.contains("USBMUX_HOST_ACTIVE=true"))
            assertTrue(report.contains("TCP_OPEN=true"))
            assertTrue(report.contains("FIN=false; RST=false"))
            f.verifyCleanup()
            diagnostic.run()
            assertEquals(1, f.requests.count { it.contains("<string>ValidatePair</string>") })
            assertFalse(report.contains(DEVICE))
            assertFalse(report.contains("PRIVATE KEY"))
        }
    }

    @Test fun noPairDiagnosticsPreserveResetEofAndShortWriteDetailsWithCleanup() {
        for (mode in listOf("RST", "FIN", "SHORT", "TIMEOUT")) {
            val store = Store(record()).apply {
                current = DiagnosticPairCandidate(current.deviceId, current.hostId, current.systemBuid,
                    current.record, DiagnosticPairState.PAIRED)
            }
            val f = fixture(store).apply {
                if (mode == "TIMEOUT") validateNoReply = true
                else if (mode == "SHORT") validateShortWrite = true
                else validateControl = if (mode == "RST") 0x14 else 0x11
            }
            val report = ExistingPairValidationDiagnostic(f.access, store).run()
            assertFalse(report, report.contains("Outcome=PASS"))
            assertEquals(1, f.requests.count { it.contains("<string>ValidatePair</string>") })
            assertFalse(f.requests.any { it.contains("<string>Pair</string>") })
            assertEquals(DiagnosticPairState.PAIRED, store.current.state)
            assertTrue(report.contains("Original cause class=com.shilapi.xcertplay.transport.IphoneUsbException"))
            assertTrue(report.contains("Original application frame="))
            when (mode) {
                "RST" -> { assertTrue(report, report.contains("reason=USBMUX_TCP_RESET")); assertTrue(report.contains("RST=true")) }
                "FIN" -> { assertTrue(report, report.contains("reason=LOCKDOWN_EOF")); assertTrue(report.contains("FIN=true")); assertTrue(report.contains("EOF=true")) }
                "SHORT" -> { assertTrue(report, report.contains("reason=USB_SHORT_WRITE")); assertTrue(report.contains("USB_SHORT_WRITE=true")) }
                "TIMEOUT" -> { assertTrue(report, report.contains("reason=LOCKDOWN_TIMEOUT")); assertTrue(report.contains("TCP_RECEIVE_TIMEOUT=true")) }
            }

            f.verifyCleanup()
            assertFalse(report.contains(DEVICE))
            assertFalse(report.contains("PRIVATE KEY"))
        }
    }

    @Test fun acceptedPreflightForOtherDeviceNeverFallsBackToPairOrValidation() {
        val store = Store(record()).apply {
            current = DiagnosticPairCandidate("OTHERDEVICE0001", current.hostId, current.systemBuid,
                current.record, DiagnosticPairState.PAIRED)
        }
        val f = fixture(store)
        val report = ExistingPairValidationDiagnostic(f.access, store).run()
        assertTrue(report, report.contains("PAIR_RECORD_INVALID"))
        assertEquals(listOf("QueryType", "GetValue"), f.requests.map {
            Regex("<key>Request</key>\\s*<string>([^<]+)</string>").find(it)!!.groupValues[1]
        })
        assertFalse(report.contains("OTHERDEVICE"))
        assertEquals(DiagnosticPairState.PAIRED, store.current.state)
        f.verifyCleanup()
    }

    companion object {
        private const val DEVICE = "TESTDEVICE0001"
        private var cached: LockdownPairRecord? = null
        internal fun record(): LockdownPairRecord {
            cached?.let { return it }
            val public = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
            fun length(size: Int): ByteArray = if (size < 128) byteArrayOf(size.toByte()) else
                byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
            fun integer(value: BigInteger): ByteArray = value.toByteArray().let { byteArrayOf(2) + length(it.size) + it }
            val body = integer(public.modulus) + integer(public.publicExponent)
            val der = byteArrayOf(0x30) + length(body.size) + body
            val pem = ("-----BEGIN RSA PUBLIC KEY-----\n" + Base64.encodeToString(der, Base64.NO_WRAP) +
                "\n-----END RSA PUBLIC KEY-----\n").toByteArray()
            return LockdownPairRecordGenerator.generateDiagnostic(pem, "aa:bb:cc:dd:ee:ff",
                "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002")
                .also { cached = it }
        }
    }
}
