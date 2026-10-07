package com.shilapi.xcertplay

import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class QDriveConfigurationDiagnosticTest {
    private fun intf(cls: Int, sub: Int, proto: Int) =
        PassiveUsbInterface(cls, cls, sub, proto, emptyList(), 0)
    private fun device() = PassiveUsbDevice("apple", 0x05ac, 0x12a8, 0, 0, 0, true, emptyList(),
        (1..5).map { id -> PassiveUsbConfiguration(id, "config$id", false, false, 500,
            if (id == 5) listOf(intf(255, 254, 2), intf(255, 42, 255), intf(2, 13, 0), intf(10, 0, 1))
            else emptyList()) })

    private inner class Fixture : QDriveConfigurationAccess, QDriveTransitionClock {
        var devices = listOf(device())
        var current = 1
        var postValue = 5
        var exactValeria: Boolean? = true
        var postValeria: Boolean? = true
        var setter = true
        var setterThrows = false
        var openThrows = false
        var postReadThrows = false
        var immediateReadThrows = false
        var closeThrows = false
        var observerCloseThrows = false
        var sends = 0
        var opens = 0
        var closes = 0
        var time = 0L
        var mutate: (() -> Unit)? = null
        var events: ((QDriveUsbEvent) -> Unit)? = null
        val diagnostic = QDriveConfigurationDiagnostic(this, this)
        override fun devices() = devices
        override fun elapsedMillis() = time
        override fun timestamp() = "time=$time"
        override fun waitMillis(milliseconds: Long) {
            time += milliseconds
            mutate?.invoke()
        }
        override fun observe(onEvent: (QDriveUsbEvent) -> Unit): Closeable {
            events = onEvent
            return Closeable { if (observerCloseThrows) error("observer cleanup") }
        }
        override fun open(device: PassiveUsbDevice, selectionAllowed: Boolean): QDriveConfigurationConnection {
            check(!openThrows) { "open failed" }
            opens++
            return object : QDriveConfigurationConnection {
                override fun configuration(report: (String) -> Unit): Int {
                    if (sends == 0) return current
                    check(!(if (selectionAllowed) immediateReadThrows else postReadThrows)) { "GET_CONFIGURATION failed" }
                    return postValue
                }
                override fun valeria5(report: (String) -> Unit, checkActive: () -> Unit): Boolean? {
                    checkActive()
                    return if (sends == 0) exactValeria else postValeria
                }
                override fun select5(): Boolean {
                    check(selectionAllowed)
                    sends++
                    check(!setterThrows) { "setter disconnected" }
                    return setter
                }
                override fun close() {
                    closes++
                    check(!closeThrows) { "close failed" }
                }
            }
        }
    }

    @Test fun allIdentityPermissionCountAndIdFailuresNeverSelect() {
        val base = device()
        val bad = listOf(
            emptyList(), listOf(base, base.copy(name = "second")),
            listOf(base.copy(vendorId = 1)), listOf(base.copy(productId = 1)),
            listOf(base.copy(hasPermission = false)),
            listOf(base.copy(configurations = base.configurations.take(4))),
            listOf(base.copy(configurations = base.configurations.map { if (it.id == 5) it.copy(id = 6) else it })),
            listOf(base.copy(configurations = base.configurations.map { if (it.id == 4) it.copy(id = 5) else it }))
        )
        bad.forEach {
            val f = Fixture()
            f.devices = it
            assertTrue(f.diagnostic.run().contains("PRECONDITION FAILED"))
            assertEquals(0, f.sends)
        }
    }

    @Test fun eachRequiredInterfaceTripleMustExistInConfiguration5() {
        val base = device()
        for (cls in listOf(255, 2, 10)) {
            val f = Fixture()
            f.devices = listOf(base.copy(configurations = base.configurations.map {
                if (it.id == 5) it.copy(interfaces = it.interfaces.filter { intf -> intf.interfaceClass != cls }) else it
            }))
            assertTrue(f.diagnostic.run().contains("PRECONDITION FAILED"))
            assertEquals(0, f.sends)
        }
    }

    @Test fun absentOrUncertainExactValeriaNeverSelectsEvenWithCachedNames() {
        for (result in listOf(false, null)) {
            val f = Fixture()
            f.exactValeria = result
            assertTrue(f.diagnostic.run().contains("Valeria string not confirmed"))
            assertEquals(0, f.sends)
            assertEquals(f.opens, f.closes)
        }
    }

    @Test fun activeOtherThan1IncludingAlready5NeverSelects() {
        for (value in listOf(0, 2, 4, 5)) {
            val f = Fixture()
            f.current = value
            assertTrue(f.diagnostic.run().contains("PRECONDITION FAILED"))
            assertEquals(0, f.sends)
            assertEquals(1, f.closes)
        }
    }

    @Test fun successfulSelectionRequiresFinal5AndClosesBothHandles() {
        val f = Fixture()
        val report = f.diagnostic.run()
        assertTrue(report, report.contains("QDRIVE CONFIGURATION 5 SELECTION CONFIRMED"))
        assertEquals(1, f.sends)
        assertEquals(2, f.opens)
        assertEquals(2, f.closes)
        assertEquals(10_000L, f.time)
        assertTrue(report.contains("No interface claimed"))
    }

    @Test fun falseSetterAndExceptionNeverRetryOrPass() {
        for (throws in listOf(false, true)) {
            val f = Fixture()
            f.setter = false
            f.setterThrows = throws
            val report = f.diagnostic.run()
            assertFalse(report.contains("SELECTION CONFIRMED"))
            assertTrue(report.contains(if (throws) "SELECTION EXCEPTION" else "RETURNED FALSE"))
            assertEquals(1, f.sends)
            assertEquals(f.opens, f.closes)
        }
    }

    @Test fun setterTrueWithOtherActiveValueIsNotPass() {
        val f = Fixture()
        f.postValue = 1
        assertTrue(f.diagnostic.run().contains("GET_CONFIGURATION=1 (expected 5)"))
    }

    @Test fun readbackFailureIsInconclusiveAndCleanupRuns() {
        val f = Fixture()
        f.immediateReadThrows = true
        f.postReadThrows = true
        assertTrue(f.diagnostic.run().contains("SELECTION INCONCLUSIVE"))
        assertEquals(2, f.closes)
    }

    @Test fun reenumerationUsesFreshPermittedReadOnlyHandleWithoutSetterRetry() {
        val f = Fixture()
        f.immediateReadThrows = true
        f.mutate = {
            f.devices = listOf(device().copy(name = "newApple"))
            f.events?.invoke(QDriveUsbEvent("ATTACH", "newApple", 0x05ac, 0x12a8))
            f.mutate = null
        }
        val report = f.diagnostic.run()
        assertTrue(report, report.contains("SELECTION CONFIRMED"))
        assertTrue(report.contains("action=ATTACH"))
        assertEquals(1, f.sends)
    }

    @Test fun disappearancePermissionLossAndDescriptorChangeAreDistinctInconclusiveOutcomes() {
        for (condition in listOf("missing", "permission", "descriptor")) {
            val f = Fixture()
            f.mutate = {
                f.devices = when (condition) {
                    "missing" -> emptyList()
                    "permission" -> listOf(device().copy(hasPermission = false))
                    else -> listOf(device().copy(configurations = device().configurations.map { it.copy(name = "changed") }))
                }
                f.mutate = null
            }
            val report = f.diagnostic.run()
            assertTrue(report, report.contains("SELECTION INCONCLUSIVE"))
            assertTrue(report, report.contains(when (condition) {
                "missing" -> "disappeared"
                "permission" -> "Permission unavailable"
                else -> "Descriptor state unexpectedly changed"
            }))
            assertEquals(1, f.sends)
            assertEquals(1, f.opens)
        }
    }

    @Test fun finalValeriaLossPreventsPass() {
        val f = Fixture()
        f.postValeria = false
        assertTrue(f.diagnostic.run().contains("SELECTION INCONCLUSIVE"))
    }

    @Test fun cleanupFailureBlocksPostOpenAndPass() {
        val f = Fixture()
        f.closeThrows = true
        val report = f.diagnostic.run()
        assertTrue(report.contains("SELECTION INCONCLUSIVE"))
        assertEquals(1, f.opens)
        assertEquals(1, f.closes)
    }

    @Test fun observerCleanupFailurePreventsPass() {
        val f = Fixture()
        f.observerCloseThrows = true
        assertTrue(f.diagnostic.run().contains("SELECTION INCONCLUSIVE"))
    }

    @Test fun openFailureAndCancellationNeverSelect() {
        val f = Fixture()
        f.openThrows = true
        assertTrue(f.diagnostic.run().contains("PRECONDITION FAILED"))
        assertEquals(0, f.sends)
        val cancelled = Fixture()
        cancelled.diagnostic.cancel()
        assertTrue(cancelled.diagnostic.run().contains("REQUEST NOT SENT"))
        assertEquals(0, cancelled.opens)
    }

    @Test fun cancellationAfterSendClosesAndDoesNotRetry() {
        val f = Fixture()
        f.mutate = { f.diagnostic.cancel() }
        assertTrue(f.diagnostic.run().contains("SELECTION INCONCLUSIVE"))
        assertEquals(1, f.sends)
        assertEquals(f.opens, f.closes)
    }

    @Test fun sameDiagnosticCannotSelectAgain() {
        val f = Fixture()
        f.diagnostic.run()
        assertTrue(f.diagnostic.run().contains("REQUEST NOT SENT"))
        assertEquals(1, f.sends)
    }
}
