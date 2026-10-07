package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger
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
class ActiveConfig5UsbMuxClaimUiTest {
    @Test fun onlyConfirmedManualActionRunsAndNeverInvokesTransitionConfigurationOrTransport() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val reads = AtomicInteger()
        ReflectionHelpers.setField(activity, "activeConfig5ClaimAccess", object : ActiveConfig5UsbMuxClaimAccess {
            override fun devices(): List<PassiveUsbDevice> { reads.incrementAndGet(); return emptyList() }
            override fun open(device: PassiveUsbDevice): ActiveConfig5UsbMuxClaimConnection = error("Must stop before open")
        })
        try {
            assertEquals(0, reads.get())
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmActiveConfig5UsbMuxClaim")
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertEquals(0, reads.get())
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(0, reads.get())
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmActiveConfig5UsbMuxClaim")
            requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ReflectionHelpers.getField<Boolean>(activity, "activeConfig5ClaimRunning") &&
                System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertFalse(ReflectionHelpers.getField(activity, "activeConfig5ClaimRunning"))
            assertEquals(1, reads.get())
            assertTrue(ReflectionHelpers.getField<String>(activity, "activeConfig5ClaimReport").contains("PRECONDITION_FAILURE"))
            assertNull(ReflectionHelpers.getField(activity, "qdriveTransitionDiagnostic"))
            assertNull(ReflectionHelpers.getField(activity, "qdriveConfigurationDiagnostic"))
            assertNull(ReflectionHelpers.getField(activity, "directUsbMuxDiagnostic"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun runningClaimBlocksOtherUsbActionsAndOtherActionsBlockClaimConfirmation() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            ReflectionHelpers.setField(activity, "activeConfig5ClaimRunning", true)
            listOf("confirmQDriveConfiguration", "runQDriveTransitionPreflight", "readActiveUsbConfiguration",
                "inspectIphoneInterfaceStrings", "runDirectUsbMuxDiagnostic").forEach {
                ReflectionHelpers.callInstanceMethod<Unit>(activity, it)
            }
            assertFalse(ReflectionHelpers.getField(activity, "qdriveConfigurationRunning"))
            assertFalse(ReflectionHelpers.getField(activity, "qdriveTransitionRunning"))
            assertFalse(ReflectionHelpers.getField(activity, "qdriveDescriptorRunning"))
            assertFalse(ReflectionHelpers.getField(activity, "activeUsbConfigurationRunning"))
            assertFalse(ReflectionHelpers.getField(activity, "directUsbMuxRunning"))
            ReflectionHelpers.setField(activity, "activeConfig5ClaimRunning", false)
            ReflectionHelpers.setField(activity, "qdriveConfigurationRunning", true)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmActiveConfig5UsbMuxClaim")
            assertNull(ReflectionHelpers.getField(activity, "activeConfig5ClaimDiagnostic"))
        } finally { controller.pause().stop().destroy() }
    }
}
