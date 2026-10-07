package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import java.io.Closeable
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
class QDriveConfigurationUiTest {
    @Test fun startupAndCancelledConfirmationDoNothingAndOnlyConfirmedActionRunsPreflight() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val reads = AtomicInteger()
        ReflectionHelpers.setField(activity, "qdriveConfigurationAccess", object : QDriveConfigurationAccess {
            override fun devices(): List<PassiveUsbDevice> { reads.incrementAndGet(); return emptyList() }
            override fun open(device: PassiveUsbDevice, selectionAllowed: Boolean): QDriveConfigurationConnection =
                error("Preflight must stop without opening")
            override fun observe(onEvent: (QDriveUsbEvent) -> Unit): Closeable = error("No observer before identity preflight")
        })
        try {
            assertEquals(0, reads.get())
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmQDriveConfiguration")
            val cancel = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertEquals(0, reads.get())
            cancel.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(0, reads.get())
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmQDriveConfiguration")
            requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ReflectionHelpers.getField<Boolean>(activity, "qdriveConfigurationRunning") &&
                System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertFalse(ReflectionHelpers.getField(activity, "qdriveConfigurationRunning"))
            assertEquals(1, reads.get())
            assertTrue(ReflectionHelpers.getField<String>(activity, "qdriveConfigurationReport").contains("REQUEST NOT SENT"))
            assertNull(ReflectionHelpers.getField(activity, "qdriveTransitionDiagnostic"))
        } finally { controller.pause().stop().destroy() }
    }
}
