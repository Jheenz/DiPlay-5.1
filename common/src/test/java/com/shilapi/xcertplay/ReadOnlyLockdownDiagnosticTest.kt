package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class ReadOnlyLockdownDiagnosticTest {
    private class Fixture : ReadOnlyLockdownAccess, ReadOnlyLockdownConnection {
        var inventory=listOf(claimTestDevice())
        var config=5
        var claim=true
        var release=true
        var throwsAt: String?=null
        var after: ((String)->Unit)?=null
        val calls=mutableListOf<String>()
        val diagnostic=ReadOnlyLockdownDiagnostic(this)
        private fun call(stage:String) { calls+=stage; check(throwsAt!=stage) { "$stage failed" }; after?.invoke(stage) }
        override fun devices()=inventory
        override fun open(device:PassiveUsbDevice):ReadOnlyLockdownConnection { call("open");return this }
        override fun configuration(report:(String)->Unit):Int { call("get");return config }
        override fun claimUsbMux():Boolean { call("claim");return claim }
        override fun initialize(report:(String)->Unit) { call("init") }
        override fun connectLockdown() { call("connect") }
        override fun queryType():String { call("query");return "com.apple.mobile.lockdown" }
        override fun productType():String { call("value");return "iPhone15,2" }
        override fun releaseUsbMux():Boolean { call("release");return release }
        override fun close() { call("close") }
    }
    @Test fun successHasSeparateMilestonesAndExactOrder() {
        val f=Fixture()
        val report=f.diagnostic.run()
        for(text in listOf("USBMUX INIT CONFIRMED","LOCKDOWN TCP CONNECTION CONFIRMED",
            ReadOnlyLockdownDiagnostic.PASS,"Outcome=PASS")) assertTrue(report,report.contains(text))
        assertEquals(listOf("open","get","claim","init","connect","query","value","release","close"),f.calls)
        assertFalse(f.diagnostic.run().contains("Outcome=PASS"))
        assertEquals(1,f.calls.count { it=="init" })
    }
    @Test fun everyFailureStopsLaterStagesAndClosesWithoutRetry() {
        val order=listOf("open","get","claim","init","connect","query","value","release","close")
        for(stage in order) {
            val f=Fixture().apply { throwsAt=stage }
            val report=f.diagnostic.run()
            assertFalse(report,report.contains("Outcome=PASS"))
            assertTrue(report.contains("Failure stage="))
            assertEquals(if(stage=="open")0 else 1,f.calls.count { it=="close" })
            assertEquals(if(stage in listOf("open","get","claim"))0 else 1,f.calls.count { it=="release" })
            assertTrue(f.calls.filter { it !in listOf("release","close") }.size <= order.indexOf(stage)+1)
        }
    }
    @Test fun preconditionsConfigAndClaimPreventInit() {
        val base=claimTestDevice()
        val bad=listOf(base.copy(productId=1),base.copy(hasPermission=false),
            base.copy(configurations=base.configurations.take(4)),
            base.copy(configurations=base.configurations.map { if(it.id==5) it.copy(interfaces=emptyList()) else it }))
        bad.forEach { d ->
            val f=Fixture().apply { inventory=listOf(d) }
            assertFalse(f.diagnostic.run().contains("Outcome=PASS"))
            assertTrue(f.calls.isEmpty())
        }
        val f=Fixture().apply { config=1 }
        assertTrue(f.diagnostic.run().contains("ACTIVE_CONFIGURATION_NOT_5"))
        assertEquals(listOf("open","get","close"),f.calls)
        val failed=Fixture().apply { claim=false }
        assertFalse(failed.diagnostic.run().contains("Outcome=PASS"))
        assertEquals(listOf("open","get","claim","close"),failed.calls)
    }
    @Test fun cancelDetachAndCleanupFailureWithholdOverallPass() {
        for(stage in listOf("claim","init","connect","query","value")) {
            for(detach in listOf(false,true)) {
                val f=Fixture().apply { after={ if(it==stage) {
                    if(detach) diagnostic.deviceDetached() else diagnostic.cancel()
                } } }
                assertFalse(f.diagnostic.run().contains("Outcome=PASS"))
                assertEquals(listOf("release","close"),f.calls.takeLast(2))
                if(stage=="query") assertFalse(f.calls.contains("value"))
            }
        }
        val f=Fixture().apply { release=false }
        assertFalse(f.diagnostic.run().contains("Outcome=PASS"))
        assertEquals("close",f.calls.last())
    }
}
