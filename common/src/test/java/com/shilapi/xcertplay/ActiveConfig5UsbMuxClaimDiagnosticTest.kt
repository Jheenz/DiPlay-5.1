package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

internal fun claimTestDevice(): PassiveUsbDevice {
    val mux = PassiveUsbInterface(1, 255, 254, 2, listOf(
        PassiveUsbEndpoint(4, 4, 0, 2, 512, 0),
        PassiveUsbEndpoint(0x85, 5, 128, 2, 512, 0),
    ), 0, "Apple USB Multiplexor")
    return PassiveUsbDevice("apple", 0x05ac, 0x12a8, 0, 0, 0, true, emptyList(),
        (1..5).map { id -> PassiveUsbConfiguration(id, "config$id", false, false, 500,
            when (id) {
                3, 4 -> listOf(mux)
                5 -> listOf(mux, PassiveUsbInterface(2, 255, 42, 255, emptyList(), 0, "Valeria"),
                    PassiveUsbInterface(3, 2, 13, 0, emptyList(), 0),
                    PassiveUsbInterface(4, 10, 0, 1, emptyList(), 0),
                    PassiveUsbInterface(4, 10, 0, 1, emptyList(), 1))
                else -> emptyList()
            })
        })
}

class ActiveConfig5UsbMuxClaimDiagnosticTest {
    private class Fixture : ActiveConfig5UsbMuxClaimAccess, ActiveConfig5UsbMuxClaimConnection, QDriveTransitionClock {
        var inventory = listOf(claimTestDevice())
        var value = 5
        var openFails = false
        var readFails = false
        var claimFails = false
        var releaseFails = false
        var closeFails = false
        var claimResult = true
        var releaseResult = true
        var afterRead: (() -> Unit)? = null
        var afterClaim: (() -> Unit)? = null
        var afterRelease: (() -> Unit)? = null
        val calls = mutableListOf<String>()
        val diagnostic = ActiveConfig5UsbMuxClaimDiagnostic(this, this)
        override fun devices() = inventory
        override fun open(device: PassiveUsbDevice): ActiveConfig5UsbMuxClaimConnection {
            calls += "open"
            check(!openFails) { "open failed" }
            return this
        }
        override fun configuration(report: (String) -> Unit): Int {
            calls += "get"
            check(!readFails) { "GET_CONFIGURATION failed" }
            afterRead?.invoke()
            return value
        }
        override fun claimUsbMux(): Boolean {
            calls += "claim"
            check(!claimFails) { "claim disconnected" }
            afterClaim?.invoke()
            return claimResult
        }
        override fun releaseUsbMux(): Boolean {
            calls += "release"
            check(!releaseFails) { "release disconnected" }
            afterRelease?.invoke()
            return releaseResult
        }
        override fun close() { calls += "close"; check(!closeFails) { "close failed" } }
        override fun timestamp() = "test-time"
        override fun elapsedMillis() = calls.size.toLong()
        override fun waitMillis(milliseconds: Long) = error("No wait loop permitted")
    }

    private fun Fixture.failure(reason: String): String = diagnostic.run().also {
        assertTrue(it, it.contains(reason))
        assertFalse(it, it.contains(ActiveConfig5UsbMuxClaimDiagnostic.PASS))
    }
    private fun change5(device: PassiveUsbDevice, transform: (PassiveUsbConfiguration) -> PassiveUsbConfiguration) =
        device.copy(configurations = device.configurations.map { if (it.id == 5) transform(it) else it })

    @Test fun successUsesOneHandleReadClaimReleaseCloseAndDoesNotRetryWholeRun() {
        val f = Fixture()
        val report = f.diagnostic.run()
        assertTrue(report, report.contains("${ActiveConfig5UsbMuxClaimDiagnostic.PASS}\n${ActiveConfig5UsbMuxClaimDiagnostic.PROTOCOL_NOT_TESTED}"))
        assertTrue(report.contains("force=false"))
        assertTrue(report.contains("Bulk transfers=0; interrupt transfers=0"))
        assertEquals(listOf("open", "get", "claim", "release", "close"), f.calls)
        f.failure("ALREADY_RUN")
        assertEquals(1, f.calls.count { it == "claim" })
    }

    @Test fun activeConfigOtherThan5PreventsClaimAndCloses() {
        for (value in listOf(0, 1, 2, 3, 4, 6, 255)) {
            val f = Fixture().apply { this.value = value }
            f.failure("ACTIVE_CONFIGURATION_NOT_5")
            assertEquals(listOf("open", "get", "close"), f.calls)
        }
    }
    @Test fun getFailurePreventsClaimAndCloses() {
        val f = Fixture().apply { readFails = true }
        f.failure("GET_CONFIGURATION_FAILURE")
        assertEquals(listOf("open", "get", "close"), f.calls)
    }
    @Test fun wrongVidPidPermissionDeviceCountConfigCountOrMissing5NeverOpens() {
        val base = claimTestDevice()
        val cases = listOf(
            emptyList(), listOf(base, base.copy(name = "second")),
            listOf(base.copy(vendorId = 1)), listOf(base.copy(productId = 1)),
            listOf(base.copy(hasPermission = false)), listOf(base.copy(configurations = base.configurations.take(4))),
            listOf(base.copy(configurations = base.configurations.map { if (it.id == 5) it.copy(id = 6) else it })),
            listOf(base.copy(configurations = base.configurations.map { if (it.id == 4) it.copy(id = 5) else it })),
        )
        cases.forEach {
            val f = Fixture().apply { inventory = it }
            f.failure(if (it.singleOrNull()?.hasPermission == false) "PERMISSION_UNAVAILABLE" else "PRECONDITION_FAILURE")
            assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun missingValeriaOrEitherNcmDescriptorNeverOpens() {
        for (id in listOf(2, 3, 4)) {
            val f = Fixture().apply { inventory = listOf(change5(claimTestDevice()) { config ->
                config.copy(interfaces = config.interfaces.filter { it.id != id })
            }) }
            f.failure("PRECONDITION_FAILURE")
            assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun valeriaRequiresCaseSensitiveInterfaceNameNotConfigurationNameOrOtherConfig() {
        for (name in listOf(null, "valeria", "PTP")) {
            val f = Fixture().apply { inventory = listOf(change5(claimTestDevice()) { config ->
                config.copy(name = "Valeria", interfaces = config.interfaces.map { if (it.id == 2) it.copy(name = name) else it })
            }) }
            f.failure("PRECONDITION_FAILURE")
            assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun missingAmbiguousWrongIdOrAlternateMuxNeverOpens() {
        val base = claimTestDevice()
        val transforms: List<(PassiveUsbConfiguration) -> PassiveUsbConfiguration> = listOf(
            { it.copy(interfaces = it.interfaces.filter { intf -> intf.id != 1 }) },
            { it.copy(interfaces = it.interfaces + it.interfaces.first()) },
            { it.copy(interfaces = it.interfaces.map { intf -> if (intf.id == 1) intf.copy(id = 7) else intf }) },
            { it.copy(interfaces = it.interfaces.map { intf -> if (intf.id == 1) intf.copy(alternateSetting = 1) else intf }) },
        )
        transforms.forEach {
            val f = Fixture().apply { inventory = listOf(change5(base, it)) }
            f.failure("SCOPED_INTERFACE_MISSING_OR_AMBIGUOUS")
            assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun wrongEndpointsNeverOpen() {
        val base = claimTestDevice()
        val out = base.configurations.last().interfaces.first().endpoints.first()
        val bad = listOf(out.copy(address = 6), out.copy(number = 6), out.copy(direction = 128),
            out.copy(type = 3), out.copy(maxPacketSize = 0))
        bad.forEach { endpoint ->
            val f = Fixture().apply { inventory = listOf(change5(base) { config ->
                config.copy(interfaces = config.interfaces.map {
                    if (it.id == 1) it.copy(endpoints = listOf(endpoint, it.endpoints.last())) else it
                })
            }) }
            f.failure("SCOPED_INTERFACE_MISSING_OR_AMBIGUOUS")
            assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun falseClaimHasNoRetryOrRelease() {
        val f = Fixture().apply { claimResult = false }
        f.failure("CLAIM_RETURNED_FALSE")
        assertEquals(listOf("open", "get", "claim", "close"), f.calls)
    }
    @Test fun claimExceptionHasNoRetryOrReleaseAndCloses() {
        val f = Fixture().apply { claimFails = true }
        f.failure("CLAIM_EXCEPTION")
        assertEquals(listOf("open", "get", "claim", "close"), f.calls)
    }
    @Test fun releaseFalseAndExceptionStillCloseWithoutRetry() {
        for (throws in listOf(false, true)) {
            val f = Fixture().apply { releaseResult = false; releaseFails = throws }
            f.failure(if (throws) "RELEASE_EXCEPTION" else "RELEASE_RETURNED_FALSE")
            assertEquals(listOf("open", "get", "claim", "release", "close"), f.calls)
        }
    }
    @Test fun cleanupFailureSuppressesPass() {
        val f = Fixture().apply { closeFails = true }
        f.failure("CLEANUP_FAILURE")
        assertEquals(1, f.calls.count { it == "close" })
    }
    @Test fun openFailureNeverReadsClaimsOrReleases() {
        val f = Fixture().apply { openFails = true }
        f.failure("DEVICE_OPEN_FAILURE")
        assertEquals(listOf("open"), f.calls)
    }
    @Test fun disappearanceBeforeClaimPreventsClaimAndCloses() {
        val f = Fixture().apply { afterRead = { inventory = emptyList() } }
        f.failure("DEVICE_DISAPPEARED")
        assertEquals(listOf("open", "get", "close"), f.calls)
    }
    @Test fun disappearanceAfterClaimStillReleasesAndCloses() {
        val f = Fixture().apply { afterClaim = { inventory = emptyList() } }
        f.failure("DEVICE_DISAPPEARED")
        assertEquals(listOf("open", "get", "claim", "release", "close"), f.calls)
    }
    @Test fun disappearanceDuringReleaseSuppressesPass() {
        val f = Fixture().apply { afterRelease = { inventory = emptyList() } }
        f.failure("DEVICE_DISAPPEARED")
        assertEquals(1, f.calls.count { it == "release" })
    }
    @Test fun cancellationAfterClaimDoesNotSkipReleaseOrClose() {
        val f = Fixture()
        f.afterClaim = { f.diagnostic.cancel() }
        f.failure("CANCELLED")
        assertEquals(listOf("open", "get", "claim", "release", "close"), f.calls)
    }
    @Test fun cancelledBeforeRunDoesNotOpen() {
        val f = Fixture()
        f.diagnostic.cancel()
        f.failure("CANCELLED")
        assertTrue(f.calls.isEmpty())
    }
    @Test fun reorderedConfigurationsAndDuplicateFlattenedCandidatesDoNotAffectScopedSelection() {
        val f = Fixture()
        val base = claimTestDevice()
        f.inventory = listOf(base.copy(configurations = base.configurations.reversed(),
            interfaces = listOf(base.configurations.last().interfaces.first(), base.configurations.last().interfaces.first())))
        assertTrue(f.diagnostic.run().contains(ActiveConfig5UsbMuxClaimDiagnostic.PASS))
    }
    @Test fun permissionLossAfterReadPreventsClaim() {
        val f = Fixture().apply { afterRead = { inventory = inventory.map { it.copy(hasPermission = false) } } }
        f.failure("PERMISSION_UNAVAILABLE")
        assertEquals(listOf("open", "get", "close"), f.calls)
    }
}
