package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidModernStartSessionAccessTest {
    private fun records(): AcceptedSessionRecords {
        val record = AndroidControlledPairAccessTest.record()
        val candidate = DiagnosticPairCandidate("TESTDEVICE0001", record.hostId, record.systemBuid, record, DiagnosticPairState.PAIRED)
        return object : AcceptedSessionRecords {
            override fun acceptedRecords() = listOf(candidate)
            override fun load(deviceId: String) = candidate.takeIf { it.deviceId == deviceId }
        }
    }
    private fun fixture(ssl: Boolean, reject: Boolean = false, mismatch: Boolean = false) =
        AndroidReadOnlyLockdownAccessTest.Fixture().apply {
            controlledReply = { xml ->
                val op = operation(xml)
                assertTrue(op, op in setOf("QueryType", "GetValue", "StartSession", "StopSession"))
                val fields = when {
                    op == "QueryType" -> "<key>Type</key><string>com.apple.mobile.lockdown</string>"
                    op == "GetValue" -> "<key>Value</key><string>${if (mismatch) "OTHERDEVICE0001" else "TESTDEVICE0001"}</string>"
                    op == "StartSession" && reject -> "<key>Error</key><string>InvalidHostID</string>"
                    op == "StartSession" -> "<key>SessionID</key><string>SECRET_SESSION</string><key>EnableSessionSSL</key><$ssl/>"
                    else -> ""
                }
                "<plist version=\"1.0\"><dict><key>Request</key><string>$op</string>$fields</dict></plist>"
            }
        }
    @Test fun actualScopedFramingSupportsBothSslOutcomesWithoutTlsOrServices() {
        for (ssl in listOf(false, true)) {
            val f = fixture(ssl)
            val diagnostic = ModernStartSessionDiagnostic(f.access, records())
            val report = diagnostic.run()
            assertTrue(report, report.contains(if (ssl) ControlledStartSession.TLS_REQUIRED else ControlledStartSession.SSL_NOT_REQUIRED))
            val operations = f.requests.map(::operation)
            assertEquals(if (ssl) listOf("QueryType", "GetValue", "StartSession")
                else listOf("QueryType", "GetValue", "StartSession", "StopSession"), operations)
            assertEquals(1, f.writes.count { it.size == 20 && it[3] == 0.toByte() })
            assertEquals(1, f.writes.count { it.size == 17 && it[3] == 2.toByte() })
            assertEquals(1, f.writes.count { it.size == 36 && it[29] == 2.toByte() })
            f.verifyCleanup()
            verify(f.base.manager, never()).requestPermission(any(android.hardware.usb.UsbDevice::class.java), any(android.app.PendingIntent::class.java))
            assertFalse(report.contains("SECRET_SESSION"))
            assertFalse(report.contains("TESTDEVICE0001"))
            val xmlBytes = f.requests.single { operation(it) == "StartSession" }.toByteArray().size
            assertTrue(report.contains("XML byte length=$xmlBytes; framed byte length=${xmlBytes + 4}"))
            diagnostic.run()
            assertEquals(1, operations.count { it == "StartSession" })
        }
    }
    @Test fun rejectionMismatchAndCleanupFailureNeverReconnectOrRepair() {
        for (mode in listOf("reject", "mismatch", "cleanup")) {
            val f = fixture(false, reject = mode == "reject", mismatch = mode == "mismatch").apply {
                finError = mode == "cleanup"
            }
            val report = ModernStartSessionDiagnostic(f.access, records()).run()
            assertFalse(report, report.contains(ControlledStartSession.SSL_NOT_REQUIRED))
            assertEquals(1, f.writes.count { it.size == 36 && it[29] == 2.toByte() })
            f.verifyCleanup()
        }
    }
    @Test fun missingRecordPreflightNeverOpensUsb() {
        val f = fixture(false)
        val absent = object : AcceptedSessionRecords {
            override fun acceptedRecords() = emptyList<DiagnosticPairCandidate>()
            override fun load(deviceId: String): DiagnosticPairCandidate? = error("Not allowed")
        }
        val report = ModernStartSessionDiagnostic(f.access, absent).run()
        assertTrue(report.contains(ControlledStartSession.ASSOCIATION))
        verify(f.base.manager, never()).openDevice(any())
        verify(f.base.manager, never()).requestPermission(any(android.hardware.usb.UsbDevice::class.java), any(android.app.PendingIntent::class.java))
        assertTrue(f.writes.isEmpty())
    }
    @Test fun tlsModeUpgradesTheSameTcpStreamAndPeerResetStopsWithoutStopSession() {
        for (peer in listOf("rst", "eof")) {
            val f = fixture(true).apply {
                tlsReply = { if (tlsRecords.size == 1) tcp(if (peer == "rst") 0x14 else 0x11, ByteArray(0)) else null }
            }
            val report = ModernStartSessionDiagnostic(f.access, records(), tlsRoundTrip = true).run()
            assertTrue(report, report.contains(ControlledLockdownTlsSession.HANDSHAKE_FAILURE))
            assertTrue(report.contains("SESSION STOP NOT CONFIRMED"))
            assertTrue(report.contains("StartSession=1 TLSHandshake=1"))
            assertEquals(listOf("QueryType", "GetValue", "StartSession"), f.requests.map(::operation))
            assertEquals("ClientHello starts TLS on the existing connection", 0x16, f.tlsRecords.first()[0].toInt())
            assertEquals("no new TCP62078 connection", 1, f.writes.count { it.size == 36 && it[29] == 2.toByte() })
            f.verifyCleanup()
            verify(f.base.manager, never()).requestPermission(any(android.hardware.usb.UsbDevice::class.java), any(android.app.PendingIntent::class.java))
            assertFalse(report.contains("SECRET_SESSION"))
            assertFalse(report.contains("TESTDEVICE0001"))
        }
    }
    @Test fun sslFalseInTlsModeNeverStartsTls() {
        val f = fixture(false).apply { tlsReply = { fail("TLS must not start"); null } }
        val report = ModernStartSessionDiagnostic(f.access, records(), tlsRoundTrip = true).run()
        assertTrue(report, report.contains(ControlledStartSession.SSL_NOT_REQUIRED))
        assertTrue(f.tlsRecords.isEmpty())
        f.verifyCleanup()
    }
    private fun operation(xml: String) = Regex("<key>Request</key>\\s*<string>([^<]+)</string>").find(xml)!!.groupValues[1]
}
