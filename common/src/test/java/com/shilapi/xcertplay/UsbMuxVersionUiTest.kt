package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "en")
class UsbMuxVersionUiTest {
    private val activeFlags = listOf("qdriveTransitionRunning", "qdriveConfigurationRunning",
        "activeConfig5ClaimRunning", "directUsbMuxRunning", "activeUsbConfigurationRunning",
        "qdriveDescriptorRunning")
    private val otherActions = listOf("confirmQDriveConfiguration", "runQDriveTransitionPreflight",
        "confirmQDriveTransition", "confirmActiveConfig5UsbMuxClaim", "readActiveUsbConfiguration",
        "inspectIphoneInterfaceStrings", "runDirectUsbMuxDiagnostic")

    private fun confirm(activity: DiPlayActivity) {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmUsbMuxVersionExchange")
    }

    private fun sendConfirmedExchange() {
        requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun finish(activity: DiPlayActivity) {
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("Confirmation must have created the diagnostic",
            ReflectionHelpers.getField<Any?>(activity, "usbMuxVersionDiagnostic"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (ReflectionHelpers.getField<Boolean>(activity, "usbMuxVersionRunning") &&
            System.nanoTime() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse("Worker must finish and clear running state",
            ReflectionHelpers.getField(activity, "usbMuxVersionRunning"))
    }

    private fun assertNoPriorPhaseStarted(activity: DiPlayActivity) {
        listOf("qdriveTransitionDiagnostic", "qdriveConfigurationDiagnostic",
            "activeConfig5ClaimDiagnostic", "directUsbMuxDiagnostic").forEach {
            assertNull(it, ReflectionHelpers.getField<Any?>(activity, it))
        }
    }

    @Test fun startupAndCancelledConfirmationNeverSampleUsbOrStartPriorPhases() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val reads = AtomicInteger()
        ReflectionHelpers.setField(activity, "usbMuxVersionAccess", object : UsbMuxVersionAccess {
            override fun devices(): List<PassiveUsbDevice> { reads.incrementAndGet(); return emptyList() }
            override fun open(device: PassiveUsbDevice): UsbMuxVersionConnection = error("No open")
        })
        try {
            assertEquals(UsbMuxVersionDiagnostic.NOT_RUN,
                ReflectionHelpers.getField<String>(activity, "usbMuxVersionReport"))
            confirm(activity)
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertEquals("Send ONE version exchange", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, reads.get())
            assertNull(ReflectionHelpers.getField<Any?>(activity, "usbMuxVersionDiagnostic"))
            assertNoPriorPhaseStarted(activity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun confirmedActionRunsInjectedAccessAndReportsPreconditionFailureWithoutPriorPhases() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val reads = AtomicInteger()
        ReflectionHelpers.setField(activity, "usbMuxVersionAccess", object : UsbMuxVersionAccess {
            override fun devices(): List<PassiveUsbDevice> { reads.incrementAndGet(); return emptyList() }
            override fun open(device: PassiveUsbDevice): UsbMuxVersionConnection = error("No open")
        })
        try {
            confirm(activity)
            assertEquals(0, reads.get())
            sendConfirmedExchange()
            finish(activity)
            assertEquals(1, reads.get())
            assertTrue(ReflectionHelpers.getField<String>(activity, "usbMuxVersionReport")
                .contains("PRECONDITION_FAILURE"))
            assertNoPriorPhaseStarted(activity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun versionExchangeBlocksEveryOtherActiveUsbDiagnostic() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            ReflectionHelpers.setField(activity, "usbMuxVersionRunning", true)
            otherActions.forEach { ReflectionHelpers.callInstanceMethod<Unit>(activity, it) }
            activeFlags.forEach { assertFalse(it, ReflectionHelpers.getField(activity, it)) }
            assertNoPriorPhaseStarted(activity)
        } finally {
            ReflectionHelpers.setField(activity, "usbMuxVersionRunning", false)
            controller.pause().stop().destroy()
        }
    }

    @Test fun everyOtherActiveUsbDiagnosticBlocksVersionExchangeBeforeDialogAndAtConfirmation() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            activeFlags.forEach { flag ->
                ReflectionHelpers.setField(activity, flag, true)
                confirm(activity)
                assertNull(ReflectionHelpers.getField<Any?>(activity, "usbMuxVersionDiagnostic"))
                assertFalse(ReflectionHelpers.getField(activity, "usbMuxVersionRunning"))
                ReflectionHelpers.setField(activity, flag, false)
                confirm(activity)
                ReflectionHelpers.setField(activity, flag, true)
                sendConfirmedExchange()
                assertNull(ReflectionHelpers.getField<Any?>(activity, "usbMuxVersionDiagnostic"))
                assertFalse(ReflectionHelpers.getField(activity, "usbMuxVersionRunning"))
                ReflectionHelpers.setField(activity, flag, false)
            }
        } finally { controller.pause().stop().destroy() }
    }

    private class FakeAccess(private val holdClaim: Boolean = false) :
        UsbMuxVersionAccess, UsbMuxVersionConnection {
        val calls = CopyOnWriteArrayList<String>()
        val claiming = CountDownLatch(1)
        val resumeClaim = CountDownLatch(1)
        override fun devices() = listOf(claimTestDevice())
        override fun open(device: PassiveUsbDevice): UsbMuxVersionConnection { calls += "open"; return this }
        override fun configuration(report: (String) -> Unit): Int { calls += "get5"; return 5 }
        override fun claimUsbMux(): Boolean {
            calls += "claim"
            claiming.countDown()
            if (holdClaim) check(resumeClaim.await(5, TimeUnit.SECONDS))
            return true
        }
        override fun writeVersion(packet: ByteArray): Int {
            calls += "out"
            assertEquals(20, packet.size)
            return 20
        }
        override fun readVersion(buffer: ByteArray): Int {
            calls += "in"
            assertEquals(1024, buffer.size)
            buffer[7] = 20
            buffer[11] = 2
            return 20
        }
        override fun releaseUsbMux(): Boolean { calls += "release"; return true }
        override fun close() { calls += "close" }
    }

    @Test fun successfulManualExchangeReportsPassWithoutSetupLockdownOrPriorPhases() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val access = FakeAccess()
        ReflectionHelpers.setField(activity, "usbMuxVersionAccess", access)
        try {
            confirm(activity)
            sendConfirmedExchange()
            finish(activity)
            assertEquals(listOf("open", "get5", "claim", "out", "in", "release", "close"), access.calls)
            val report = ReflectionHelpers.getField<String>(activity, "usbMuxVersionReport")
            assertTrue(report, report.contains(UsbMuxVersionDiagnostic.PASS))
            assertTrue(report, report.contains("SETUP07 / LOCKDOWN / PAIRING NOT STARTED"))
            assertTrue(report, report.contains("USB detach observation=REGISTERED"))
            assertTrue(report, report.contains("USB detach observer cleanup=PASS"))
            assertNoPriorPhaseStarted(activity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun observerRegistrationFailureIsReportedAndCancelsBeforeAnyUsbWork() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val access = FakeAccess()
        ReflectionHelpers.setField(activity, "usbMuxVersionAccess", access)
        val observer: (android.content.Context, (QDriveUsbEvent) -> Unit) -> java.io.Closeable =
            { _, _ -> throw IllegalStateException("register refused") }
        ReflectionHelpers.setField(activity, "usbMuxVersionObserver", observer)
        try {
            confirm(activity)
            sendConfirmedExchange()
            finish(activity)
            assertEquals(emptyList<String>(), access.calls)
            val report = ReflectionHelpers.getField<String>(activity, "usbMuxVersionReport")
            assertTrue(report, report.contains("USB detach observation=FAILED IllegalStateException: register refused"))
            assertTrue(report, report.contains("CANCELLED"))
            assertTrue(report, report.contains("USB detach observer cleanup=NOT NEEDED"))
            assertFalse(report, report.contains(UsbMuxVersionDiagnostic.PASS))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun observerCleanupFailureWithholdsOtherwiseSuccessfulPass() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val access = FakeAccess()
        ReflectionHelpers.setField(activity, "usbMuxVersionAccess", access)
        val observer: (android.content.Context, (QDriveUsbEvent) -> Unit) -> java.io.Closeable =
            { _, _ -> java.io.Closeable { throw java.io.IOException("unregister refused") } }
        ReflectionHelpers.setField(activity, "usbMuxVersionObserver", observer)
        try {
            confirm(activity)
            sendConfirmedExchange()
            finish(activity)
            assertEquals(listOf("open", "get5", "claim", "out", "in", "release", "close"), access.calls)
            val report = ReflectionHelpers.getField<String>(activity, "usbMuxVersionReport")
            assertTrue(report, report.contains("USB detach observer cleanup=FAILED IOException: unregister refused"))
            assertTrue(report, report.contains("Outcome=USB_EVENT_OBSERVER_CLEANUP_FAILURE"))
            assertFalse(report, report.contains(UsbMuxVersionDiagnostic.PASS))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun pauseCancelsExchangeAndKeepsReleaseCloseCleanupWithoutTraffic() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val access = FakeAccess(holdClaim = true)
        ReflectionHelpers.setField(activity, "usbMuxVersionAccess", access)
        try {
            confirm(activity)
            sendConfirmedExchange()
            assertTrue(access.claiming.await(5, TimeUnit.SECONDS))
            controller.pause()
            access.resumeClaim.countDown()
            finish(activity)
            assertEquals(listOf("open", "get5", "claim", "release", "close"), access.calls)
            val report = ReflectionHelpers.getField<String>(activity, "usbMuxVersionReport")
            assertTrue(report, report.contains("CANCELLED"))
            assertFalse(report, report.contains(UsbMuxVersionDiagnostic.PASS))
        } finally {
            access.resumeClaim.countDown()
            controller.stop().destroy()
        }
    }

    @Test fun transientAppleDetachBroadcastSuppressesPassEvenWhenInventoryStillMatches() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val access = FakeAccess(holdClaim = true)
        ReflectionHelpers.setField(activity, "usbMuxVersionAccess", access)
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        `when`(device.productId).thenReturn(0x12a8)
        `when`(device.deviceName).thenReturn("apple")
        try {
            confirm(activity)
            sendConfirmedExchange()
            assertTrue(access.claiming.await(5, TimeUnit.SECONDS))
            activity.applicationContext.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED)
                .putExtra(UsbManager.EXTRA_DEVICE, device))
            activity.applicationContext.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                .putExtra(UsbManager.EXTRA_DEVICE, device))
            shadowOf(Looper.getMainLooper()).idle()
            access.resumeClaim.countDown()
            finish(activity)
            assertEquals(listOf("open", "get5", "claim", "release", "close"), access.calls)
            val report = ReflectionHelpers.getField<String>(activity, "usbMuxVersionReport")
            assertTrue(report, report.contains("DEVICE_DISAPPEARED"))
            assertFalse(report, report.contains(UsbMuxVersionDiagnostic.PASS))
        } finally {
            access.resumeClaim.countDown()
            controller.pause().stop().destroy()
        }
    }
}
