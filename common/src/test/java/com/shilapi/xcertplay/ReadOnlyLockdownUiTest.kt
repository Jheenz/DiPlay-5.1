package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.orchestration.LegacyLaunchBuild
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "en")
class ReadOnlyLockdownUiTest {
    private val pairExclusiveFlags = listOf("nforetekCacheRunning", "vendorApkExportRunning", "phase3b8Running",
        "passiveI2cInventoryRunning", "passiveUsbRunning", "usbConfigurationRunning",
        "activeUsbConfigurationRunning", "qdriveDescriptorRunning", "qdriveTransitionRunning",
        "qdriveConfigurationRunning", "activeConfig5ClaimRunning", "usbMuxVersionRunning",
        "readOnlyLockdownRunning", "directUsbMuxRunning")
    private val flags = listOf("qdriveTransitionRunning", "qdriveConfigurationRunning",
        "activeConfig5ClaimRunning", "usbMuxVersionRunning", "directUsbMuxRunning",
        "activeUsbConfigurationRunning", "qdriveDescriptorRunning", "passiveUsbRunning",
        "usbConfigurationRunning")
    private val actions = listOf("runQDriveTransitionPreflight", "confirmQDriveTransition",
        "confirmQDriveConfiguration", "confirmActiveConfig5UsbMuxClaim", "confirmUsbMuxVersionExchange",
        "runDirectUsbMuxDiagnostic", "readActiveUsbConfiguration", "inspectIphoneInterfaceStrings",
        "runPassiveUsbInventory")

    private fun confirm(activity: DiPlayActivity) =
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmReadOnlyLockdownDiscovery")

    private fun accept() {
        requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun finish(activity: DiPlayActivity): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (ReflectionHelpers.getField<Boolean>(activity, "readOnlyLockdownRunning") &&
            System.nanoTime() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse("Worker must finish", ReflectionHelpers.getField(activity, "readOnlyLockdownRunning"))
        return ReflectionHelpers.getField(activity, "readOnlyLockdownReport")
    }

    private fun noPriorPhases(activity: DiPlayActivity) {
        listOf("qdriveTransitionDiagnostic", "qdriveConfigurationDiagnostic",
            "activeConfig5ClaimDiagnostic", "usbMuxVersionDiagnostic", "directUsbMuxDiagnostic").forEach {
            assertNull(it, ReflectionHelpers.getField<Any?>(activity, it))
        }
    }

    private class Access(private val hold: Boolean = false) : ReadOnlyLockdownAccess, ReadOnlyLockdownConnection {
        val calls = CopyOnWriteArrayList<String>()
        val claiming = CountDownLatch(1)
        val resume = CountDownLatch(1)
        override fun devices() = listOf(claimTestDevice())
        override fun open(device: PassiveUsbDevice): ReadOnlyLockdownConnection { calls += "open"; return this }
        override fun configuration(report: (String) -> Unit): Int { calls += "get5"; return 5 }
        override fun claimUsbMux(): Boolean {
            calls += "claim"
            claiming.countDown()
            if (hold) check(resume.await(5, TimeUnit.SECONDS))
            return true
        }
        override fun initialize(report: (String) -> Unit) { calls += "init" }
        override fun connectLockdown() { calls += "connect" }
        override fun queryType(): String { calls += "query"; return "com.apple.mobile.lockdown" }
        override fun productType(): String { calls += "product"; return "iPhone11,2" }
        override fun releaseUsbMux(): Boolean { calls += "release"; return true }
        override fun close() { calls += "close" }
    }

    @Test fun startupAndCancelledConfirmationPerformNoUsbWork() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val access = Access()
        ReflectionHelpers.setField(activity, "readOnlyLockdownAccess", access)
        try {
            assertEquals(ReadOnlyLockdownDiagnostic.NOT_RUN,
                ReflectionHelpers.getField<String>(activity, "readOnlyLockdownReport"))
            assertFalse(LegacyLaunchBuild.CONNECTIONS_ENABLED)
            confirm(activity)
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertEquals("Run read-only discovery", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(access.calls.isEmpty())
            assertNull(ReflectionHelpers.getField<Any?>(activity, "readOnlyLockdownDiagnostic"))
            noPriorPhases(activity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun confirmedActionRunsOnlyInjectedReadOnlyBoundary() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val access = Access()
        ReflectionHelpers.setField(activity, "readOnlyLockdownAccess", access)
        try {
            confirm(activity)
            assertTrue(access.calls.isEmpty())
            accept()
            val report = finish(activity)
            assertEquals(listOf("open", "get5", "claim", "init", "connect", "query", "product", "release", "close"), access.calls)
            assertTrue(report, report.contains(ReadOnlyLockdownDiagnostic.PASS))
            assertTrue(report, report.contains("ProductType=iPhone11,2"))
            assertTrue(report, report.contains("USB detach observer cleanup=PASS"))
            noPriorPhases(activity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun everyUsbDiagnosticExcludesDiscoveryBeforeDialogAndAtConfirmation() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            flags.forEach { flag ->
                ReflectionHelpers.setField(activity, flag, true)
                confirm(activity)
                assertNull(ReflectionHelpers.getField<Any?>(activity, "readOnlyLockdownDiagnostic"))
                assertFalse(ReflectionHelpers.getField(activity, "readOnlyLockdownRunning"))
                ReflectionHelpers.setField(activity, flag, false)
                confirm(activity)
                ReflectionHelpers.setField(activity, flag, true)
                accept()
                assertNull(ReflectionHelpers.getField<Any?>(activity, "readOnlyLockdownDiagnostic"))
                assertFalse(ReflectionHelpers.getField(activity, "readOnlyLockdownRunning"))
                ReflectionHelpers.setField(activity, flag, false)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun discoveryExcludesEveryUsbDiagnosticIncludingPassiveMapping() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            ReflectionHelpers.setField(activity, "readOnlyLockdownRunning", true)
            actions.forEach { ReflectionHelpers.callInstanceMethod<Unit>(activity, it) }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "runPassiveUsbScan",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
            flags.forEach { assertFalse(it, ReflectionHelpers.getField(activity, it)) }
            noPriorPhases(activity)
        } finally {
            ReflectionHelpers.setField(activity, "readOnlyLockdownRunning", false)
            controller.pause().stop().destroy()
        }
    }

    @Test fun observerRegistrationFailureCancelsBeforeUsbAndIsSaved() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val access = Access()
        ReflectionHelpers.setField(activity, "readOnlyLockdownAccess", access)
        val observer: (Context, (QDriveUsbEvent) -> Unit) -> Closeable =
            { _, _ -> throw IllegalStateException("register refused") }
        ReflectionHelpers.setField(activity, "readOnlyLockdownObserver", observer)
        try {
            confirm(activity); accept()
            val report = finish(activity)
            assertTrue(access.calls.isEmpty())
            assertTrue(report, report.contains("CANCELLED"))
            assertTrue(report, report.contains("USB detach observation=FAILED IllegalStateException: register refused"))
            assertFalse(report, report.contains(ReadOnlyLockdownDiagnostic.PASS))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun observerCleanupFailureWithholdsPass() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "readOnlyLockdownAccess", Access())
        val observer: (Context, (QDriveUsbEvent) -> Unit) -> Closeable =
            { _, _ -> Closeable { throw java.io.IOException("unregister refused") } }
        ReflectionHelpers.setField(activity, "readOnlyLockdownObserver", observer)
        try {
            confirm(activity); accept()
            val report = finish(activity)
            assertTrue(report, report.contains("USB_EVENT_OBSERVER_CLEANUP_FAILURE"))
            assertTrue(report, report.contains("USB detach observer cleanup=FAILED IOException: unregister refused"))
            assertFalse(report, report.contains(ReadOnlyLockdownDiagnostic.PASS))
            assertFalse(report, report.contains("Outcome=PASS"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unexpectedWorkerFailureIsSavedAndObserverStillCloses() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        var closed = false
        ReflectionHelpers.setField(activity, "readOnlyLockdownAccess", object : ReadOnlyLockdownAccess {
            override fun devices(): List<PassiveUsbDevice> = throw AssertionError("injected fatal failure")
            override fun open(device: PassiveUsbDevice): ReadOnlyLockdownConnection = error("No open")
        })
        val observer: (Context, (QDriveUsbEvent) -> Unit) -> Closeable =
            { _, _ -> Closeable { closed = true } }
        ReflectionHelpers.setField(activity, "readOnlyLockdownObserver", observer)
        try {
            confirm(activity); accept()
            val report = finish(activity)
            assertTrue(closed)
            assertTrue(report, report.contains("UNEXPECTED_DIAGNOSTIC_FAILURE AssertionError: injected fatal failure"))
            assertTrue(report, report.contains("USB detach observer cleanup=PASS"))
            assertFalse(report, report.contains(ReadOnlyLockdownDiagnostic.PASS))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun pauseCancelsBeforeInitButRetainsReleaseAndClose() = interruptedRun("pause")
    @Test fun destroyCancelsBeforeInitButRetainsReleaseAndClose() = interruptedRun("destroy")
    @Test fun observerErrorLatchesDisappearanceWithoutInventoryChange() = interruptedRun("error")
    @Test fun appleDetachLatchesDisappearanceWithoutInventoryChange() = interruptedRun("detach")

    private fun interruptedRun(reason: String) {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val access = Access(hold = true)
        var event: ((QDriveUsbEvent) -> Unit)? = null
        val observer: (Context, (QDriveUsbEvent) -> Unit) -> Closeable =
            { _, callback -> event = callback; Closeable {} }
        ReflectionHelpers.setField(activity, "readOnlyLockdownAccess", access)
        ReflectionHelpers.setField(activity, "readOnlyLockdownObserver", observer)
        try {
            confirm(activity); accept()
            assertTrue(access.claiming.await(5, TimeUnit.SECONDS))
            when (reason) {
                "pause" -> controller.pause()
                "destroy" -> ReflectionHelpers.callInstanceMethod<Unit>(activity, "onDestroy")
                "detach" -> requireNotNull(event).invoke(QDriveUsbEvent("DETACH", "apple", 0x05ac, 0x12a8))
                else -> requireNotNull(event).invoke(QDriveUsbEvent("ERROR", "", -1, -1))
            }
            access.resume.countDown()
            val report = finish(activity)
            assertEquals(listOf("open", "get5", "claim", "release", "close"), access.calls)
            assertTrue(report, report.contains(if (reason == "error" || reason == "detach") "DEVICE_DISAPPEARED" else "CANCELLED"))
            assertFalse(report, report.contains(ReadOnlyLockdownDiagnostic.PASS))
        } finally {
            access.resume.countDown()
            if (reason != "destroy") {
                if (reason != "pause") controller.pause()
                controller.stop().destroy()
            }
        }
    }

    @Test fun controlledPairIsSeparateManualActionWithExplicitPersistentTrustWarning() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            assertEquals(ControlledPairDiagnostic.NOT_RUN,
                ReflectionHelpers.getField<String>(activity, "controlledPairReport"))
            assertNull(ReflectionHelpers.getField<Any?>(activity, "controlledPairDiagnostic"))
            assertFalse(LegacyLaunchBuild.CONNECTIONS_ENABLED)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmControlledPairDiagnostic")
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertEquals("Pair / validate DiPlay host", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            val message = requireNotNull(dialog.findViewById<android.widget.TextView>(android.R.id.message)).text.toString()
            assertTrue(message, message.contains("SetValue"))
            assertTrue(message, message.contains("Trust only on the iPhone"))
            assertTrue(message, message.contains("VALIDATED records use ValidatePair only, with no SetValue or Pair"))
            assertTrue(message, message.contains("No automatic retry"))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(ControlledPairDiagnostic.NOT_RUN,
                ReflectionHelpers.getField<String>(activity, "controlledPairReport"))
            assertNull(ReflectionHelpers.getField<Any?>(activity, "controlledPairDiagnostic"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun controlledPairPreflightLinkageFailureReportsSignatureWithoutUsbOrSecrets() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        var opened = false
        try {
            ReflectionHelpers.setField(activity, "controlledPairAccess", object : ReadOnlyLockdownAccess {
                override fun devices(): List<PassiveUsbDevice> = throw NoSuchMethodError(
                    "No virtual method missing()V in class Landroid/view/View; SECRET")
                override fun open(device: PassiveUsbDevice): ReadOnlyLockdownConnection {
                    opened = true
                    error("No USB open allowed")
                }
            })
            ReflectionHelpers.setField(activity, "controlledPairObserver",
                { _: Context, _: (QDriveUsbEvent) -> Unit -> Closeable {} })
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmControlledPairDiagnostic")
            accept()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (ReflectionHelpers.getField<Boolean>(activity, "controlledPairRunning") &&
                System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertFalse(ReflectionHelpers.getField(activity, "controlledPairRunning"))
            val report = ReflectionHelpers.getField<String>(activity, "controlledPairReport")
            assertTrue(report, report.contains("stage=PREFLIGHT"))
            assertTrue(report, report.contains("errorClass=java.lang.NoSuchMethodError"))
            assertTrue(report, report.contains("Landroid/view/View;->missing()V"))
            assertTrue(report, report.contains("Application frame=com.shilapi.xcertplay."))
            assertTrue(report, report.contains("Outcome=STOP"))
            assertFalse(report.contains("SECRET"))
            assertFalse(opened)
            assertFalse(LegacyLaunchBuild.CONNECTIONS_ENABLED)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun existingPairActionIsSeparateAndMissingRecordNeverOpensUsbOrPairs() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val access = Access()
        try {
            assertEquals(ExistingPairValidationDiagnostic.NOT_RUN,
                ReflectionHelpers.getField<String>(activity, "existingPairReport"))
            ReflectionHelpers.setField(activity, "controlledPairAccess", access)
            ReflectionHelpers.setField(activity, "controlledPairStore", object : DiagnosticPairStore {
                override fun acceptedRecords() = emptyList<com.shilapi.xcertplay.transport.DiagnosticPairCandidate>()
                override fun load(deviceId: String) = error("No load without accepted preflight")
                override fun systemBuid() = error("No identity generation")
                override fun save(candidate: com.shilapi.xcertplay.transport.DiagnosticPairCandidate) = error("No save")
            })
            ReflectionHelpers.setField(activity, "controlledPairObserver",
                { _: Context, _: (QDriveUsbEvent) -> Unit -> Closeable {} })
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmExistingPairValidation")
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString().contains("NO PAIR"))
            assertTrue(requireNotNull(dialog.findViewById<android.widget.TextView>(android.R.id.message))
                .text.toString().contains("exactly one ValidatePair"))
            accept()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (ReflectionHelpers.getField<Boolean>(activity, "controlledPairRunning") &&
                System.nanoTime() < deadline) {
                Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle()
            }
            assertFalse(ReflectionHelpers.getField(activity, "controlledPairRunning"))
            val report = ReflectionHelpers.getField<String>(activity, "existingPairReport")
            assertTrue(report, report.contains("PAIR_RECORD_NOT_FOUND"))
            assertTrue(access.calls.isEmpty())
            assertEquals(ControlledPairDiagnostic.NOT_RUN, ReflectionHelpers.getField<String>(activity, "controlledPairReport"))
            assertFalse(LegacyLaunchBuild.CONNECTIONS_ENABLED)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun controlledPairChecksEveryDiagnosticBeforeOpeningAndAgainAtConfirmation() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            pairExclusiveFlags.forEach { flag ->
                ShadowAlertDialog.reset()
                ReflectionHelpers.setField(activity, flag, true)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmControlledPairDiagnostic")
                assertNull(flag, ShadowAlertDialog.getLatestAlertDialog())
                assertNull(flag, ReflectionHelpers.getField<Any?>(activity, "controlledPairDiagnostic"))
                ReflectionHelpers.setField(activity, flag, false)

                ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmControlledPairDiagnostic")
                assertNotNull(ShadowAlertDialog.getLatestAlertDialog())
                ReflectionHelpers.setField(activity, flag, true)
                accept()
                assertNull(flag, ReflectionHelpers.getField<Any?>(activity, "controlledPairDiagnostic"))
                assertFalse(flag, ReflectionHelpers.getField(activity, "controlledPairRunning"))
                ReflectionHelpers.setField(activity, flag, false)
                ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
            }
        } finally {
            pairExclusiveFlags.forEach { ReflectionHelpers.setField(activity, it, false) }
            controller.pause().stop().destroy()
        }
    }

    @Test fun controlledPairBlocksExistingUsbDiagnosticsWhileActive() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            ReflectionHelpers.setField(activity, "controlledPairRunning", true)
            actions.forEach { ReflectionHelpers.callInstanceMethod<Unit>(activity, it) }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmReadOnlyLockdownDiscovery")
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "runPassiveUsbScan",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
            listOf("readNforetekCache", "runPhase3B8Inventory", "runPassiveI2cInventory",
                "nforetekAction", "startVendorInvestigation").forEach { name ->
                if (name == "nforetekAction") {
                    ReflectionHelpers.callInstanceMethod<Unit>(activity, name,
                        ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
                } else ReflectionHelpers.callInstanceMethod<Unit>(activity, name)
            }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "requestPhase3BAction",
                ReflectionHelpers.ClassParameter.from(String::class.java, "refresh"))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "exportVendorApks",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false),
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false),
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
            flags.forEach { assertFalse(it, ReflectionHelpers.getField(activity, it)) }
            assertFalse(ReflectionHelpers.getField(activity, "nforetekCacheRunning"))
            assertFalse(ReflectionHelpers.getField(activity, "phase3b8Running"))
            assertFalse(ReflectionHelpers.getField(activity, "passiveI2cInventoryRunning"))
            noPriorPhases(activity)
        } finally {
            ReflectionHelpers.setField(activity, "controlledPairRunning", false)
            controller.pause().stop().destroy()
        }
    }
}
