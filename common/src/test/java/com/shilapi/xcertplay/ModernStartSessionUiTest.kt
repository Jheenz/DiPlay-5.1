package com.shilapi.xcertplay

import android.content.DialogInterface
import android.os.Looper
import android.widget.LinearLayout
import com.shilapi.xcertplay.transport.DiagnosticPairCandidate
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
@Config(sdk = [28], qualifiers = "en")
class ModernStartSessionUiTest {
    private fun confirm(activity: DiPlayActivity, tls: Boolean) =
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmModernStartSession",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, tls))

    @Test fun tlsRoundTripIsManualAndMissingRecordStopsBeforeUsbOrTls() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        var inventoryReads = 0
        ReflectionHelpers.setField(activity, "controlledPairStore", object : DiagnosticPairStore {
            override fun acceptedRecords(): List<DiagnosticPairCandidate> { inventoryReads++; return emptyList() }
            override fun load(deviceId: String): DiagnosticPairCandidate? = error("No record")
            override fun systemBuid(): String = error("No identity generation")
            override fun save(candidate: DiagnosticPairCandidate): Unit = error("No writes")
        })
        ReflectionHelpers.setField(activity, "controlledPairAccess", object : ReadOnlyLockdownAccess {
            override fun devices(): List<PassiveUsbDevice> = error("Preflight must stop before USB")
            override fun open(device: PassiveUsbDevice): ReadOnlyLockdownConnection = error("No USB")
        })
        ReflectionHelpers.setField(activity, "controlledPairObserver",
            { _: android.content.Context, _: (QDriveUsbEvent) -> Unit -> java.io.Closeable {} })
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "launchTestSettings",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, LinearLayout(activity)))
            assertEquals(0, inventoryReads)
            assertNull(ReflectionHelpers.getField<ModernStartSessionDiagnostic?>(activity, "modernStartSessionDiagnostic"))
            confirm(activity, true)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(shadowOf(dialog).message.toString().contains("TLS"))
            assertEquals(0, inventoryReads)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ReflectionHelpers.getField<Boolean>(activity, "controlledPairRunning") && System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertFalse(ReflectionHelpers.getField<Boolean>(activity, "controlledPairRunning"))
            assertEquals(1, inventoryReads)
            val report = ReflectionHelpers.getField<String>(activity, "modernLockdownTlsReport")
            assertTrue(report.contains("PAIR_RECORD_NOT_FOUND"))
            assertTrue(report.contains("StartSession attempts=0"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun startupSettingsAndCancelledConfirmationNeverAccessUsbOrStore() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val access = object : ReadOnlyLockdownAccess {
            override fun devices(): List<PassiveUsbDevice> = error("Must remain manual")
            override fun open(device: PassiveUsbDevice): ReadOnlyLockdownConnection = error("Must remain manual")
        }
        ReflectionHelpers.setField(activity, "controlledPairAccess", access)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "launchTestSettings",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, LinearLayout(activity)))
            assertNull(ReflectionHelpers.getField<ModernStartSessionDiagnostic?>(activity, "modernStartSessionDiagnostic"))
            confirm(activity, false)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog)
            assertTrue(shadowOf(dialog).message.toString().contains("SESSION"))
            assertTrue(shadowOf(dialog).message.toString().contains("changes live iPhone session state"))
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            assertFalse(ReflectionHelpers.getField<Boolean>(activity, "controlledPairRunning"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun manuallyConfirmedMissingRecordStopsBeforeUsbAndRetainsReport() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        var inventoryReads = 0
        val store = object : DiagnosticPairStore {
            override fun acceptedRecords(): List<DiagnosticPairCandidate> { inventoryReads++; return emptyList() }
            override fun load(deviceId: String): DiagnosticPairCandidate? = error("No record")
            override fun systemBuid(): String = error("No identity generation")
            override fun save(candidate: DiagnosticPairCandidate): Unit = error("No writes")
        }
        ReflectionHelpers.setField(activity, "controlledPairStore", store)
        ReflectionHelpers.setField(activity, "controlledPairAccess", object : ReadOnlyLockdownAccess {
            override fun devices(): List<PassiveUsbDevice> = error("Preflight must stop before USB")
            override fun open(device: PassiveUsbDevice): ReadOnlyLockdownConnection = error("No USB")
        })
        ReflectionHelpers.setField(activity, "controlledPairObserver",
            { _: android.content.Context, _: (QDriveUsbEvent) -> Unit -> java.io.Closeable {} })
        try {
            confirm(activity, false)
            assertEquals(0, inventoryReads)
            ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ReflectionHelpers.getField<Boolean>(activity, "controlledPairRunning") && System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertFalse(ReflectionHelpers.getField<Boolean>(activity, "controlledPairRunning"))
            assertEquals(1, inventoryReads)
            val report = ReflectionHelpers.getField<String>(activity, "modernStartSessionReport")
            assertTrue(report.contains("PAIR_RECORD_NOT_FOUND"))
            assertTrue(report.contains("StartSession attempts=0"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun carKitServiceDiscoveryIsManualAndMissingPairedRecordStopsBeforeUsb() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        var inventoryReads = 0
        ReflectionHelpers.setField(activity, "controlledPairStore", object : DiagnosticPairStore {
            override fun acceptedRecords(): List<DiagnosticPairCandidate> { inventoryReads++; return emptyList() }
            override fun load(deviceId: String): DiagnosticPairCandidate? = error("No record")
            override fun systemBuid(): String = error("No identity generation")
            override fun save(candidate: DiagnosticPairCandidate): Unit = error("No writes")
        })
        ReflectionHelpers.setField(activity, "controlledPairAccess", object : ReadOnlyLockdownAccess {
            override fun devices(): List<PassiveUsbDevice> = error("Preflight must stop before USB")
            override fun open(device: PassiveUsbDevice): ReadOnlyLockdownConnection = error("No USB")
        })
        ReflectionHelpers.setField(activity, "controlledPairObserver",
            { _: android.content.Context, _: (QDriveUsbEvent) -> Unit -> java.io.Closeable {} })
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "launchTestSettings",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, LinearLayout(activity)))
            assertEquals(0, inventoryReads)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmCarKitServiceDiscovery")
            var dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(shadowOf(dialog).message.toString().contains("PAIRED record"))
            assertTrue(shadowOf(dialog).message.toString().contains("NEVER contacted"))
            assertEquals(0, inventoryReads)
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            assertEquals(0, inventoryReads)

            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmCarKitServiceDiscovery")
            dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ReflectionHelpers.getField<Boolean>(activity, "controlledPairRunning") && System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertFalse(ReflectionHelpers.getField<Boolean>(activity, "controlledPairRunning"))
            assertEquals(1, inventoryReads)
            val report = ReflectionHelpers.getField<String>(activity, "carKitDiscoveryReport")
            assertTrue(report.contains("PAIR_RECORD_NOT_FOUND"))
            assertTrue(report.contains("StartService=0"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun carKitServiceTlsIsManualAndMissingPairedRecordStopsBeforeUsb() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        var inventoryReads = 0
        ReflectionHelpers.setField(activity, "controlledPairStore", object : DiagnosticPairStore {
            override fun acceptedRecords(): List<DiagnosticPairCandidate> { inventoryReads++; return emptyList() }
            override fun load(deviceId: String): DiagnosticPairCandidate? = error("No record")
            override fun systemBuid(): String = error("No identity generation")
            override fun save(candidate: DiagnosticPairCandidate): Unit = error("No writes")
        })
        ReflectionHelpers.setField(activity, "controlledPairAccess", object : ReadOnlyLockdownAccess {
            override fun devices(): List<PassiveUsbDevice> = error("Preflight must stop before USB")
            override fun open(device: PassiveUsbDevice): ReadOnlyLockdownConnection = error("No USB")
        })
        ReflectionHelpers.setField(activity, "controlledPairObserver",
            { _: android.content.Context, _: (QDriveUsbEvent) -> Unit -> java.io.Closeable {} })
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "launchTestSettings",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, LinearLayout(activity)))
            assertEquals(0, inventoryReads)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmCarKitServiceConnection")
            var dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(shadowOf(dialog).message.toString().contains("NO CARKIT APPLICATION TRAFFIC"))
            assertEquals(0, inventoryReads)
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            assertEquals(0, inventoryReads)

            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmCarKitServiceConnection")
            dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ReflectionHelpers.getField<Boolean>(activity, "controlledPairRunning") && System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertFalse(ReflectionHelpers.getField<Boolean>(activity, "controlledPairRunning"))
            assertEquals(1, inventoryReads)
            val report = ReflectionHelpers.getField<String>(activity, "carKitServiceConnectionReport")
            assertTrue(report.contains("PAIR_RECORD_NOT_FOUND"))
            assertTrue(report.contains("ServiceTCP=0 ServiceTLS=0"))
        } finally { controller.pause().stop().destroy() }
    }
}
