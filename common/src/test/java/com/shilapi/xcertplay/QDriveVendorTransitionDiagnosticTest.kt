package com.shilapi.xcertplay

import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class QDriveVendorTransitionDiagnosticTest {
    @Test fun wrongVidPidPermissionAndAmbiguityNeverOpenOrSend() {
        for (devices in listOf(
            emptyList(), listOf(device.copy(vendorId = 1)), listOf(device.copy(productId = 0x12af)),
            listOf(device.copy(hasPermission = false)), listOf(device, device.copy(name = "second")),
        )) {
            val f = Fixture()
            f.before = devices
            assertNull(f.diagnostic.preflight().device)
            assertTrue(f.run().contains("PREFLIGHT FAILED — REQUEST NOT SENT"))
            assertEquals(0, f.requests)
            assertTrue(f.sessions.isEmpty())
        }
    }

    @Test fun wrongConfigurationValeriaTrueAndUnavailableNeverSendAndAlwaysClose() {
        for ((configuration, valeria) in listOf(2 to false, 1 to true, 1 to null)) {
            val f = Fixture()
            f.initialConfig = configuration
            f.initialValeria = valeria
            assertNull(f.diagnostic.preflight().device)
            assertTrue(f.run().contains("REQUEST NOT SENT"))
            assertEquals(0, f.requests)
            assertTrue(f.sessions.all { it.closed })
            assertTrue(f.observerClosed)
        }
    }

    @Test fun permissionReadOpenAndObserverFailuresStopWithoutRequest() {
        for (stage in listOf("open", "configuration", "valeria", "observe")) {
            val f = Fixture()
            f.failStage = stage
            val report = f.run()
            assertTrue(report, report.contains("REQUEST NOT SENT"))
            assertTrue(report.contains("ERROR"))
            assertEquals(0, f.requests)
            assertTrue(f.sessions.all { it.closed })
        }
    }

    @Test fun initialPreflightCleanupFailureDisablesActionAndPostSendCleanupFailureAllowsPassiveObservationOnly() {
        val f = Fixture()
        f.failStage = "close"
        assertNull(f.diagnostic.preflight().device)
        assertEquals(0, f.requests)
        val report = f.run()
        assertTrue(report.contains("QDRIVE VENDOR TRANSITION INCONCLUSIVE"))
        assertTrue(report.contains("Post-transition open skipped"))
        assertEquals(1, f.requests)
        assertEquals(2, f.sessions.size) // Read-only preflight, then the single send connection; no post open.
        assertTrue(f.order.contains("poll"))
    }

    @Test fun readOnlyPreflightClosesAndCannotSend() {
        val f = Fixture()
        val preflight = f.diagnostic.preflight()
        assertEquals(device, preflight.device)
        assertTrue(preflight.report.contains("PREFLIGHT PASS"))
        assertEquals(0, f.requests)
        assertEquals(listOf("open", "configuration", "valeria", "configuration", "close"), f.order)
    }

    @Test fun exactOneAttemptNoRetryUnchangedStateIsNotTransitionEvenWithReturnZero() {
        val f = Fixture()
        val report = f.run()
        assertTrue(report, report.contains("QDRIVE VENDOR REQUEST SENT — NO TRANSITION OBSERVED"))
        assertTrue(report.contains("Vendor request returned=0"))
        assertEquals(1, f.requests)
        assertEquals(10_000, f.clock.time.toInt())
        assertTrue(f.sessions.all { it.closed })
        assertTrue(f.observerClosed)
        assertTrue(f.order.indexOf("send") < f.order.indexOf("close"))
        assertTrue(f.run().contains("REQUEST NOT SENT"))
        assertEquals(1, f.requests)
    }

    @Test fun negativeOrThrowingTransferStillClosesAndObservesChangedPid() {
        for (throws in listOf(false, true)) {
            val f = Fixture()
            f.requestResult = -1
            f.throwTransfer = throws
            f.after = listOf(device.copy(name = "new-path", productId = 0x12ab))
            f.postConfig = 4
            f.postValeria = true
            val report = f.run()
            assertTrue(report, report.contains("QDRIVE VENDOR TRANSITION OBSERVED"))
            assertTrue(report.contains("PID=0x12AB"))
            assertTrue(report.contains("active configuration=4"))
            assertTrue(report.contains("Valeria=TRUE"))
            assertEquals(1, f.requests)
            assertTrue(f.sessions.all { it.closed })
            assertTrue(f.order.indexOf("close") < f.order.indexOf("poll"))
        }
    }

    @Test fun changedDeviceWithoutPermissionRetainsPassiveInventoryAndNeverOpensIt() {
        val f = Fixture()
        f.after = listOf(device.copy(name = "new", productId = 0x12ab, hasPermission = false))
        val report = f.run()
        assertTrue(report.contains("POST-TRANSITION DEVICE DETECTED — PERMISSION NOT GRANTED"))
        assertTrue(report.contains("QDRIVE VENDOR TRANSITION INCONCLUSIVE"))
        assertTrue(report.contains("name=new"))
        assertEquals(1, f.sessions.size)
        assertEquals(1, f.requests)
    }

    @Test fun detachWithoutReturnAmbiguityAndReadbackFailuresAreInconclusive() {
        for (stage in listOf("absent", "ambiguous", "postConfig", "postValeria", "postOpen", "postClose", "observerClose", "poll")) {
            val f = Fixture()
            f.after = when (stage) {
                "absent" -> emptyList()
                "ambiguous" -> listOf(device, device.copy(name = "second"))
                else -> listOf(device.copy(name = "new"))
            }
            f.failStage = stage
            val report = f.run()
            assertTrue(report, report.contains("QDRIVE VENDOR TRANSITION INCONCLUSIVE"))
            assertEquals(1, f.requests)
            assertTrue(f.sessions.all { it.closed })
            assertTrue(f.observerClosed)
        }
    }

    @Test fun inPlaceConfigurationAndValeriaChangesDoNotRequireChangedPidOrDetach() {
        val f = Fixture()
        f.postConfig = 3
        val report = f.run()
        assertTrue(report.contains("QDRIVE VENDOR TRANSITION OBSERVED"))
        assertTrue(report.contains("active configuration=3"))
        assertEquals(1, f.requests)
    }

    @Test fun changedReadOnlyDescriptorEvidenceWithoutValeriaIsAStateChange() {
        val f = Fixture()
        f.postString = "Apple USB Multiplexor"
        val report = f.run()
        assertTrue(report.contains("Raw descriptor / checked interface-string evidence changed=true"))
        assertTrue(report.contains("Post-transition Valeria=FALSE"))
        assertTrue(report.contains("QDRIVE VENDOR TRANSITION OBSERVED"))
        assertEquals(1, f.requests)
    }

    @Test fun transientNativeStringReadFailureIsNotInventedAsTransitionEvidence() {
        val f = Fixture()
        f.postStringFailure = true
        val report = f.run()
        assertTrue(report.contains("QDRIVE VENDOR REQUEST SENT — NO TRANSITION OBSERVED"))
        assertFalse(report.contains("evidence changed=true"))
    }

    @Test fun uncorrelatedActiveConfigurationAndUnavailablePostPredicateAreInconclusive() {
        val f = Fixture()
        f.postConfig = 5
        assertTrue(f.run().contains("QDRIVE VENDOR TRANSITION INCONCLUSIVE"))
        val missing = Fixture()
        missing.postValeria = null
        assertTrue(missing.run().contains("QDRIVE VENDOR TRANSITION INCONCLUSIVE"))
        assertTrue(missing.sessions.all { it.closed })
    }

    @Test fun attachDetachEventsCaptureTransientChangeDespiteSameFinalInventory() {
        val f = Fixture()
        f.emitEvents = true
        val report = f.run()
        assertTrue(report, report.contains("action=DETACH"))
        assertTrue(report.contains("action=ATTACH"))
        assertTrue(report.contains("QDRIVE VENDOR TRANSITION OBSERVED"))
    }

    @Test fun preparedSnapshotChangedOrCancelledNeverSends() {
        val f = Fixture()
        assertTrue(f.diagnostic.run(device.copy(name = "stale")).contains("REQUEST NOT SENT"))
        assertEquals(0, f.requests)
        f.diagnostic.cancel()
        assertTrue(f.run().contains("REQUEST NOT SENT"))
        assertEquals(0, f.requests)
    }

    @Test fun cancellationAfterTransferClosesBothResourcesAndNeverRetries() {
        val f = Fixture()
        f.cancelAfterSend = true
        assertTrue(f.run().contains("QDRIVE VENDOR TRANSITION INCONCLUSIVE"))
        assertEquals(1, f.requests)
        assertTrue(f.sessions.all { it.closed })
        assertTrue(f.observerClosed)
    }

    @Test fun discriminatorDeadlineFailureClosesWithoutMutation() {
        val f = Fixture()
        f.discriminatorDelay = 15_001
        assertTrue(f.run().contains("REQUEST NOT SENT"))
        assertEquals(0, f.requests)
        assertTrue(f.sessions.all { it.closed })
    }

    private class Clock : QDriveTransitionClock {
        var time = 0L
        override fun elapsedMillis() = time
        override fun timestamp() = "test-$time"
        override fun waitMillis(milliseconds: Long) { time += milliseconds }
    }

    private class Fixture : QDriveTransitionAccess {
        val clock = Clock()
        val diagnostic = QDriveVendorTransitionDiagnostic(this, clock)
        var before = listOf(device)
        var after = listOf(device)
        var initialConfig = 1
        var initialValeria: Boolean? = false
        var postConfig = 1
        var postValeria: Boolean? = false
        var postString = "PTP"
        var postStringFailure = false
        var requestResult = 0
        var throwTransfer = false
        var failStage = ""
        var emitEvents = false
        var cancelAfterSend = false
        var discriminatorDelay = 0L
        var requests = 0
        var observerClosed = false
        val sessions = mutableListOf<Session>()
        val order = mutableListOf<String>()
        private var event: ((QDriveUsbEvent) -> Unit)? = null
        fun run() = diagnostic.run(device)
        override fun devices(): List<PassiveUsbDevice> {
            if (requests > 0) {
                order += "poll"
                check(failStage != "poll") { "poll failed" }
                return after
            }
            return before
        }
        override fun observe(onEvent: (QDriveUsbEvent) -> Unit): Closeable {
            check(failStage != "observe") { "observer setup failed" }
            event = onEvent
            return Closeable {
                observerClosed = true
                check(failStage != "observerClose") { "observer close failed" }
            }
        }
        override fun open(device: PassiveUsbDevice): QDriveTransitionConnection {
            val post = requests > 0
            check(failStage != if (post) "postOpen" else "open") { "open failed" }
            order += "open"
            return Session(post).also { sessions += it }
        }
        inner class Session(private val post: Boolean) : QDriveTransitionConnection {
            var closed = false
            var sent = false
            override fun configuration(report: (String) -> Unit): Int {
                check(!closed && !sent)
                order += "configuration"
                check(failStage != if (post) "postConfig" else "configuration") { "configuration read failed" }
                return if (post) postConfig else initialConfig
            }
            override fun valeria(report: (String) -> Unit, checkActive: () -> Unit): Boolean? {
                check(!closed && !sent)
                order += "valeria"
                clock.time += discriminatorDelay
                checkActive()
                check(failStage != if (post) "postValeria" else "valeria") { "descriptor read failed" }
                report("Checked configuration index=0 ID=1 interface ID=0 alt=0 class=6/1/1 iInterface=1")
                if (post && postStringFailure) report("result=STRING_READ_FAILED native return=-1")
                else report("Retrieved interface ASCII string=\"${if (post) postString else "PTP"}\"")
                return if (post) postValeria else initialValeria
            }
            override fun sendAuditedRequest(): Int {
                check(!closed && !sent && !post)
                sent = true
                requests++
                order += "send"
                if (emitEvents) {
                    event?.invoke(QDriveUsbEvent("DETACH", device.name, device.vendorId, device.productId))
                    event?.invoke(QDriveUsbEvent("ATTACH", device.name, device.vendorId, device.productId))
                }
                if (cancelAfterSend) diagnostic.cancel()
                check(!throwTransfer) { "transfer disconnected" }
                return requestResult
            }
            override fun close() {
                check(!closed)
                closed = true
                order += "close"
                check(failStage != if (post) "postClose" else "close") { "close failed" }
            }
        }
    }

    companion object {
        private val device = PassiveUsbDevice("apple", 0x05ac, 0x12a8, 0, 0, 0, true, emptyList(),
            (1..4).map { PassiveUsbConfiguration(it, if (it == 1) "PTP" else "config $it", false, false, 500, emptyList()) })
    }
}
