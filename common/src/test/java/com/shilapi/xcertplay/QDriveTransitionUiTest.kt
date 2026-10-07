package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import com.shilapi.xcertplay.orchestration.LegacyLaunchBuild
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
class QDriveTransitionUiTest {
    @Test fun cancelledConfirmationHasNoAccessAndPositiveActionIsManualOneShot() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val device = PassiveUsbDevice("apple", 0x05ac, 0x12a8, 0, 0, 0, true, emptyList(),
            listOf(PassiveUsbConfiguration(1, "PTP", false, false, 500, emptyList())))
        val snapshots = AtomicInteger()
        val requests = AtomicInteger()
        val closes = AtomicInteger()
        ReflectionHelpers.setField(activity, "qdriveTransitionAccess", object : QDriveTransitionAccess {
            override fun devices(): List<PassiveUsbDevice> {
                snapshots.incrementAndGet()
                check(requests.get() == 0) { "Simulated observation failure; no real USB" }
                return listOf(device)
            }
            override fun open(device: PassiveUsbDevice) = object : QDriveTransitionConnection {
                override fun configuration(report: (String) -> Unit) = 1
                override fun valeria(report: (String) -> Unit, checkActive: () -> Unit): Boolean {
                    checkActive()
                    return false
                }
                override fun sendAuditedRequest() = requests.incrementAndGet() - 1
                override fun close() { closes.incrementAndGet() }
            }
            override fun observe(onEvent: (QDriveUsbEvent) -> Unit) = Closeable {}
        })
        try {
            assertEquals(0, snapshots.get())
            ReflectionHelpers.setField(activity, "qdriveTransitionPrepared", device)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmQDriveTransition")
            val first = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertEquals(0, snapshots.get())
            first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(0, snapshots.get())
            assertEquals(0, requests.get())

            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmQDriveTransition")
            val second = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertEquals(0, requests.get())
            second.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ReflectionHelpers.getField<Boolean>(activity, "qdriveTransitionRunning") &&
                System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertFalse(ReflectionHelpers.getField(activity, "qdriveTransitionRunning"))
            assertEquals(ReflectionHelpers.getField<String>(activity, "qdriveTransitionReport"), 1, requests.get())
            assertEquals(1, closes.get())
            assertNull(ReflectionHelpers.getField(activity, "qdriveTransitionPrepared"))
            assertTrue(ReflectionHelpers.getField<String>(activity, "qdriveTransitionReport")
                .contains("QDRIVE VENDOR TRANSITION INCONCLUSIVE"))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmQDriveTransition")
            assertEquals(1, requests.get())
            assertFalse(LegacyLaunchBuild.CONNECTIONS_ENABLED)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
