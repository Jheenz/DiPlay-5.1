package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.ControlledPairFailure
import com.shilapi.xcertplay.transport.DiagnosticPairCandidate
import org.junit.Assert.*
import org.junit.Test

class ControlledPairDiagnosticTest {
    private class Fixture : ReadOnlyLockdownAccess, ReadOnlyLockdownConnection, ControlledPairConnection {
        var inventory = listOf(claimTestDevice())
        var config = 5
        var claim = true
        var release = true
        var throwsAt: String? = null
        var linkageAt: String? = null
        var after: ((String) -> Unit)? = null
        val calls = mutableListOf<String>()
        val store = object : DiagnosticPairStore {
            override fun load(deviceId: String): DiagnosticPairCandidate? = null
            override fun systemBuid() = error("Never used by fake")
            override fun save(candidate: DiagnosticPairCandidate) = error("Never used by fake")
        }
        val diagnostic = ControlledPairDiagnostic(this, store)
        private fun call(name: String) {
            calls += name
            if (name == linkageAt) throw NoSuchMethodError("No virtual method missing()V in class Landroid/view/View; SECRET")
            check(name != throwsAt) { "SECRET fake failure" }
            after?.invoke(name)
        }
        override fun devices() = inventory
        override fun open(device: PassiveUsbDevice): ReadOnlyLockdownConnection { call("open"); return this }
        override fun configuration(report: (String) -> Unit): Int { call("get"); return config }
        override fun claimUsbMux(): Boolean { call("claim"); return claim }
        override fun initialize(report: (String) -> Unit) { call("init") }
        override fun connectLockdown() { call("connect") }
        override fun queryType(): String { call("query"); return "com.apple.mobile.lockdown" }
        override fun productType(): String = error("Not required by pair diagnostic")
        override fun prepareAndPair(store: DiagnosticPairStore, checkActive: () -> Unit, report: (String) -> Unit) {
            checkActive(); call("pair"); checkActive()
        }
        override fun releaseUsbMux(): Boolean { call("release"); return release }
        override fun close() { call("close") }
    }
    @Test fun exactOrderAndCountersOnlyPassAfterCleanupOneShot() {
        val f = Fixture()
        val report = f.diagnostic.run()
        assertTrue(report, report.contains("Outcome=PASS"))
        assertTrue(report.contains(ControlledPairDiagnostic.COUNTERS))
        assertEquals(listOf("open", "get", "claim", "init", "connect", "query", "pair", "release", "close"), f.calls)
        assertFalse(f.diagnostic.run().contains("Outcome=PASS"))
        assertEquals(1, f.calls.count { it == "pair" })
    }
    @Test fun allTransportAndQueryFailuresPreventPairAndStillClose() {
        for (stage in listOf("open", "get", "claim", "init", "connect", "query", "pair", "release", "close")) {
            val f = Fixture().apply { throwsAt = stage }
            val report = f.diagnostic.run()
            assertFalse(report, report.contains("Outcome=PASS"))
            assertFalse(report.contains("SECRET"))
            assertEquals(if (stage == "open") 0 else 1, f.calls.count { it == "close" })
            if (stage in listOf("open", "get", "claim", "init", "connect", "query")) assertFalse(f.calls.contains("pair"))
        }
    }
    @Test fun everyProvenDescriptorPermissionAndDevicePreconditionStopsBeforeOpen() {
        val d = claimTestDevice()
        val config5 = d.configurations.single { it.id == 5 }
        val bad = listOf(
            emptyList(), listOf(d, d.copy(name = "second")),
            listOf(d.copy(vendorId = 1)), listOf(d.copy(productId = 1)), listOf(d.copy(hasPermission = false)),
            listOf(d.copy(configurations = d.configurations.take(4))),
            listOf(d.copy(configurations = d.configurations.filter { it.id != 5 } + config5.copy(id = 6))),
        ) + listOf(
            config5.copy(interfaces = config5.interfaces.filterNot { it.interfaceClass == 255 && it.subclass == 42 }),
            config5.copy(interfaces = config5.interfaces.filterNot { it.interfaceClass == 2 }),
            config5.copy(interfaces = config5.interfaces.filterNot { it.interfaceClass == 10 }),
            config5.copy(interfaces = config5.interfaces.filterNot { it.subclass == 254 }),
            config5.copy(interfaces = config5.interfaces + config5.interfaces.first { it.subclass == 254 }),
            config5.copy(interfaces = config5.interfaces.map { if (it.subclass == 254) it.copy(endpoints = emptyList()) else it }),
        ).map { cfg -> listOf(d.copy(configurations = d.configurations.map { if (it.id == 5) cfg else it })) }
        for (inventory in bad) {
            val f = Fixture().apply { this.inventory = inventory }
            assertFalse(f.diagnostic.run().contains("Outcome=PASS"))
            assertTrue(f.calls.isEmpty())
        }
        val wrongConfig = Fixture().apply { config = 1 }
        assertFalse(wrongConfig.diagnostic.run().contains("Outcome=PASS"))
        assertEquals(listOf("open", "get", "close"), wrongConfig.calls)
        val noClaim = Fixture().apply { claim = false }
        assertFalse(noClaim.diagnostic.run().contains("Outcome=PASS"))
        assertEquals(listOf("open", "get", "claim", "close"), noClaim.calls)
    }
    @Test fun cancellationDetachAndReleaseFailureWithholdPass() {
        for (stage in listOf("claim", "init", "connect", "query", "pair")) {
            for (detach in listOf(false, true)) {
                val f = Fixture().apply {
                    after = { if (it == stage) {
                        if (detach) diagnostic.deviceDetached() else diagnostic.cancel()
                    } }
                }

                assertFalse(f.diagnostic.run().contains("Outcome=PASS"))
                assertEquals(listOf("release", "close"), f.calls.takeLast(2))
                if (stage != "pair") assertFalse(f.calls.contains("pair"))
            }
        }
        val f = Fixture().apply { release = false }
        assertFalse(f.diagnostic.run().contains(ControlledPairDiagnostic.PASS))
    }

    @Test fun linkageFailuresPreserveStageAndStillReleaseAndCloseWithoutRetry() {
        for (stage in listOf("get", "init", "pair", "release", "close")) {
            val f = Fixture().apply { linkageAt = stage }
            val report = f.diagnostic.run()
            assertFalse(report.contains("Outcome=PASS"))
            assertTrue(report.contains("errorClass=java.lang.NoSuchMethodError"))
            assertTrue(report.contains("Landroid/view/View;->missing()V"))
            assertFalse(report.contains("SECRET"))
            assertEquals(1, f.calls.count { it == "close" })
            assertEquals(if (stage == "get") 0 else 1, f.calls.count { it == "release" })
            assertTrue(f.calls.count { it == "pair" } <= 1)
        }
    }
}
