package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.orchestration.LegacyLaunchBuild
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", manifest = Config.NONE)
class Phase3BDeviceSettingsTest {
    @Test fun exposesBluetoothDiagnosticButtonsAndKeepsProjectionGated() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val sample = "RFCOMM result: PASS\nBytes sent=6; received=6"
        DiPlayActivity::class.java.getDeclaredField("phase3BReport").apply { isAccessible = true }.set(activity, sample)
        val parent = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("launchTestSettings", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, parent)
        val views = descendants(parent).toList()
        val buttons = views.filterIsInstance<Button>().map { it.text.toString() }
        assertTrue(buttons.contains("Refresh Bluetooth diagnostics"))
        assertTrue(buttons.contains("Start read-only vehicle Bluetooth investigation"))
        assertTrue(buttons.contains("Inspect vendor Bluetooth services"))
        assertTrue(buttons.contains("Test read-only service bind"))
        assertTrue(buttons.contains("Export vendor Bluetooth APKs"))
        assertTrue(buttons.contains("Find and export stock Geely Bluetooth control APK"))
        assertTrue(buttons.contains("Collect and export Apple/USB stack files"))
        assertTrue(buttons.contains("Phase 3B.5b Apple implementation discovery"))
        assertTrue(buttons.contains("Run full read-only CarPlay inventory"))
        assertTrue(buttons.contains("Run passive MFi/I2C inventory"))
        assertTrue(buttons.contains("Scan connected USB devices"))
        assertTrue(buttons.contains("Map iPhone USB configurations"))
        assertTrue(buttons.contains("Read active USB configuration"))
        assertTrue(buttons.contains("Inspect iPhone interface strings"))
        assertTrue(buttons.contains("Check QDrive transition preflight (read-only)"))
        assertTrue(buttons.contains("Select post-Valeria configuration"))
        assertTrue(buttons.contains("Claim/release configuration-5 USBMUX"))
        assertNull(DiPlayActivity::class.java.getDeclaredField("activeConfig5ClaimDiagnostic")
            .apply { isAccessible = true }.get(activity))
        assertFalse(DiPlayActivity::class.java.getDeclaredField("activeConfig5ClaimRunning")
            .apply { isAccessible = true }.getBoolean(activity))
        assertNull(DiPlayActivity::class.java.getDeclaredField("qdriveConfigurationDiagnostic")
            .apply { isAccessible = true }.get(activity))
        assertFalse(DiPlayActivity::class.java.getDeclaredField("qdriveConfigurationRunning")
            .apply { isAccessible = true }.getBoolean(activity))
        val transitionButton = views.filterIsInstance<Button>().single { it.text.toString() == "Test QDrive USB mode transition" }
        assertFalse("State-changing action requires successful manual preflight", transitionButton.isEnabled)
        assertNull(DiPlayActivity::class.java.getDeclaredField("qdriveTransitionDiagnostic")
            .apply { isAccessible = true }.get(activity))
        assertFalse(DiPlayActivity::class.java.getDeclaredField("qdriveTransitionRunning")
            .apply { isAccessible = true }.getBoolean(activity))
        assertTrue((DiPlayActivity::class.java.getDeclaredField("qdriveTransitionReport")
            .apply { isAccessible = true }.get(activity) as String).contains("not run"))
        assertNull(DiPlayActivity::class.java.getDeclaredField("qdriveDescriptorAccess")
            .apply { isAccessible = true }.get(activity))
        assertFalse(DiPlayActivity::class.java.getDeclaredField("qdriveDescriptorRunning")
            .apply { isAccessible = true }.getBoolean(activity))
        assertTrue((DiPlayActivity::class.java.getDeclaredField("qdriveDescriptorReport")
            .apply { isAccessible = true }.get(activity) as String).contains("not run"))
        assertNull(DiPlayActivity::class.java.getDeclaredField("activeUsbConfigurationAccess")
            .apply { isAccessible = true }.get(activity))
        assertFalse(DiPlayActivity::class.java.getDeclaredField("activeUsbConfigurationRunning")
            .apply { isAccessible = true }.getBoolean(activity))
        assertTrue((DiPlayActivity::class.java.getDeclaredField("activeUsbConfigurationReport")
            .apply { isAccessible = true }.get(activity) as String).contains("not run"))
        assertTrue(views.filterIsInstance<TextView>().any {
            it.text.toString() == PassiveUsbDeviceDiagnostic.MAPPING_TITLE
        })
        assertFalse(DiPlayActivity::class.java.getDeclaredField("usbConfigurationRunning")
            .apply { isAccessible = true }.getBoolean(activity))
        assertTrue((DiPlayActivity::class.java.getDeclaredField("usbConfigurationReport")
            .apply { isAccessible = true }.get(activity) as String).contains("not run"))
        assertTrue(buttons.contains("Test USBMUX + Lockdown"))
        assertTrue(buttons.contains("Trust prompt appeared — STOP"))
        assertTrue(buttons.contains("Cancel transport test"))
        assertTrue(views.filterIsInstance<TextView>().any {
            it.text.toString() == PassiveUsbDeviceDiagnostic.TITLE
        })
        assertTrue(buttons.contains("Read cached NForetek Bluetooth status"))
        assertFalse(buttons.contains("Scan for devices"))
        assertFalse(buttons.contains("Test RFCOMM connection"))
        assertFalse(buttons.contains("Bluetooth settings"))
        assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == sample })
        assertTrue(views.filterIsInstance<TextView>().any {
            it.text.toString() == "Read-only system/vendor inventory. Does not activate CarPlay, USB, Bluetooth, Apple services, JNI or authentication."
        })
        assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "Ready" })
        val running = DiPlayActivity::class.java.getDeclaredField("phase3b8Running")
            .apply { isAccessible = true }.getBoolean(activity)
        val report = DiPlayActivity::class.java.getDeclaredField("phase3b8Report")
            .apply { isAccessible = true }.get(activity) as String
        assertFalse("Inventory must not start until its button is pressed", running)
        assertTrue(report.contains("not started"))
        val passiveReport = DiPlayActivity::class.java.getDeclaredField("passiveI2cInventoryReport")
            .apply { isAccessible = true }.get(activity) as String
        val passiveRunning = DiPlayActivity::class.java.getDeclaredField("passiveI2cInventoryRunning")
            .apply { isAccessible = true }.getBoolean(activity)
        assertTrue(passiveReport.contains("not run"))
        assertFalse("Passive I2C inventory must be manual", passiveRunning)
        val usbReport = DiPlayActivity::class.java.getDeclaredField("passiveUsbReport")
            .apply { isAccessible = true }.get(activity) as String
        assertTrue(usbReport.contains("not run"))
        assertTrue(usbReport.contains(PassiveUsbDeviceDiagnostic.SAFETY))
        assertFalse(DiPlayActivity::class.java.getDeclaredField("passiveUsbRunning")
            .apply { isAccessible = true }.getBoolean(activity))
        assertNull(DiPlayActivity::class.java.getDeclaredField("passiveUsbInventory")
            .apply { isAccessible = true }.get(activity))
        assertNull(DiPlayActivity::class.java.getDeclaredField("directUsbMuxDiagnostic")
            .apply { isAccessible = true }.get(activity))
        assertFalse(DiPlayActivity::class.java.getDeclaredField("directUsbMuxRunning")
            .apply { isAccessible = true }.getBoolean(activity))
        assertTrue((DiPlayActivity::class.java.getDeclaredField("directUsbMuxReport")
            .apply { isAccessible = true }.get(activity) as String).contains("not run"))
        assertFalse(buttons.contains("Connect"))
        assertTrue(LegacyLaunchBuild.PHASE3B_DIAGNOSTICS_ENABLED)
        assertFalse(LegacyLaunchBuild.PHASE3B_TRANSPORT_TESTS_ENABLED)
        assertFalse(LegacyLaunchBuild.CONNECTIONS_ENABLED)
        assertFalse(LegacyLaunchBuild.VENDOR_INTEGRATION_ENABLED)
    }

    @Test fun usbScanRunsOnlyOnManualClickAndUpdatesSettingsWithoutEnablingConnections() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        DiPlayActivity::class.java.getDeclaredField("passiveUsbInventory").apply { isAccessible = true }
            .set(activity, PassiveUsbInventory { calls.incrementAndGet(); emptyList() })
        val parent = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("launchTestSettings", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, parent)
        assertEquals(0, calls.get())
        val button = descendants(parent).filterIsInstance<Button>()
            .single { it.text.toString() == "Scan connected USB devices" }
        button.performClick()
        val running = DiPlayActivity::class.java.getDeclaredField("passiveUsbRunning").apply { isAccessible = true }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (running.getBoolean(activity) && System.nanoTime() < deadline) {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertFalse(running.getBoolean(activity))
        assertEquals(1, calls.get())
        assertTrue(button.isEnabled)
        assertTrue(descendants(parent).filterIsInstance<TextView>().any {
            it.text.contains("Attached USB device count=0") && it.text.contains(PassiveUsbDeviceDiagnostic.SAFETY)
        })
        assertFalse(LegacyLaunchBuild.CONNECTIONS_ENABLED)
        assertFalse(LegacyLaunchBuild.PHASE3B_TRANSPORT_TESTS_ENABLED)
    }

    @Test fun directUsbMuxTestIsManualAndReportsFailureWithoutEnablingRuntime() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val access = object : com.shilapi.xcertplay.transport.DirectUsbMuxAccess {
            override fun devices(): List<android.hardware.usb.UsbDevice> {
                calls.incrementAndGet()
                return emptyList()
            }
            override fun hasPermission(device: android.hardware.usb.UsbDevice): Boolean =
                error("Permission must not be checked without a selected device")
            override fun open(
                device: android.hardware.usb.UsbDevice,
                selection: com.shilapi.xcertplay.transport.DirectUsbMuxSelection,
                report: (String) -> Unit,
            ): com.shilapi.xcertplay.transport.UsbMuxBulkPipe = error("Must not open without a device")
        }
        DiPlayActivity::class.java.getDeclaredField("directUsbMuxAccess").apply { isAccessible = true }
            .set(activity, access)
        controller.setup()
        try {
            val parent = LinearLayout(activity)
            DiPlayActivity::class.java.getDeclaredMethod("launchTestSettings", LinearLayout::class.java)
                .apply { isAccessible = true }.invoke(activity, parent)
            assertEquals("Neither startup nor Settings may access USB", 0, calls.get())
            val button = descendants(parent).filterIsInstance<Button>()
                .single { it.text.toString() == "Test USBMUX + Lockdown" }
            button.performClick()
            val running = DiPlayActivity::class.java.getDeclaredField("directUsbMuxRunning").apply { isAccessible = true }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (running.getBoolean(activity) && System.nanoTime() < deadline) {
                Thread.sleep(20)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            assertFalse(running.getBoolean(activity))
            assertEquals(1, calls.get())
            assertTrue(button.isEnabled)
            assertTrue(descendants(parent).filterIsInstance<TextView>().any {
                it.text.contains("Apple device detected=false") &&
                    it.text.contains(com.shilapi.xcertplay.transport.DirectUsbMuxDiagnostic.SAFETY)
            })
            assertFalse(LegacyLaunchBuild.CONNECTIONS_ENABLED)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun configurationMappingIsManualAndNeverConstructsTheTransportDiagnostic() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        DiPlayActivity::class.java.getDeclaredField("passiveUsbInventory").apply { isAccessible = true }
            .set(activity, PassiveUsbInventory {
                calls.incrementAndGet()
                listOf(PassiveUsbDevice("apple", 0x05ac, 0x12a8, 0, 0, 0, true, emptyList()))
            })
        controller.setup()
        try {
            val parent = LinearLayout(activity)
            DiPlayActivity::class.java.getDeclaredMethod("launchTestSettings", LinearLayout::class.java)
                .apply { isAccessible = true }.invoke(activity, parent)
            assertEquals(0, calls.get())
            val button = descendants(parent).filterIsInstance<Button>()
                .single { it.text.toString() == "Map iPhone USB configurations" }
            button.performClick()
            val running = DiPlayActivity::class.java.getDeclaredField("usbConfigurationRunning")
                .apply { isAccessible = true }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (running.getBoolean(activity) && System.nanoTime() < deadline) {
                Thread.sleep(20)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            assertFalse(running.getBoolean(activity))
            assertEquals(1, calls.get())
            assertTrue(button.isEnabled)
            assertTrue(descendants(parent).filterIsInstance<TextView>().any {
                it.text.contains("configurationCount=0") && it.text.contains(PassiveUsbDeviceDiagnostic.SAFETY)
            })
            assertNull(DiPlayActivity::class.java.getDeclaredField("directUsbMuxDiagnostic")
                .apply { isAccessible = true }.get(activity))
            assertFalse(LegacyLaunchBuild.CONNECTIONS_ENABLED)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun activeConfigurationReadIsManualAndDoesNotConstructATransport() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val access = object : ActiveUsbConfigurationAccess {
            override fun devices(): List<PassiveUsbDevice> {
                calls.incrementAndGet()
                return listOf(PassiveUsbDevice("apple", 0x05ac, 0x12a8, 0, 0, 0, true,
                    emptyList(), listOf(PassiveUsbConfiguration(4, "test config", false, false, 500, emptyList()))))
            }
            override fun readConfiguration(device: PassiveUsbDevice, report: (String) -> Unit): Int {
                reads.incrementAndGet()
                report("cleanup connection close=PASS")
                return 4
            }
        }
        DiPlayActivity::class.java.getDeclaredField("activeUsbConfigurationAccess").apply { isAccessible = true }
            .set(activity, access)
        controller.setup()
        try {
            val parent = LinearLayout(activity)
            DiPlayActivity::class.java.getDeclaredMethod("launchTestSettings", LinearLayout::class.java)
                .apply { isAccessible = true }.invoke(activity, parent)
            assertEquals(0, calls.get())
            assertEquals(0, reads.get())
            val button = descendants(parent).filterIsInstance<Button>()
                .single { it.text.toString() == "Read active USB configuration" }
            button.performClick()
            val running = DiPlayActivity::class.java.getDeclaredField("activeUsbConfigurationRunning")
                .apply { isAccessible = true }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (running.getBoolean(activity) && System.nanoTime() < deadline) {
                Thread.sleep(20)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            assertFalse(running.getBoolean(activity))
            assertEquals(1, calls.get())
            assertEquals(1, reads.get())
            assertTrue(button.isEnabled)
            assertTrue(descendants(parent).filterIsInstance<TextView>().any {
                it.text.contains("Active configuration value=4") && it.text.contains(ActiveUsbConfigurationDiagnostic.SAFETY)
            })
            assertNull(DiPlayActivity::class.java.getDeclaredField("directUsbMuxDiagnostic")
                .apply { isAccessible = true }.get(activity))
            assertFalse(LegacyLaunchBuild.CONNECTIONS_ENABLED)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
