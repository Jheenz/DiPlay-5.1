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
class Phase3ADeviceSettingsTest {
    @Test fun settingsShowDiagnosticsAndKeepProductionConnectionsDisabled() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val sample = "Local IPv4: 192.168.43.1\nManual readiness: FAIL apEnabled=false"
        DiPlayActivity::class.java.getDeclaredField("phase3AReport").apply { isAccessible = true }
            .set(activity, sample)
        val parent = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("launchTestSettings", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, parent)
        val views = descendants(parent).toList()
        assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == sample })
        val buttons = views.filterIsInstance<Button>().map { it.text.toString() }
        assertTrue(buttons.contains("Refresh network diagnostics"))
        assertTrue(buttons.contains("Start Phase 3A network test"))
        assertTrue(buttons.contains("Capture wireless capability snapshot"))
        assertTrue(buttons.contains("Save diagnostic report"))
        val collectionOnly = "Collect and export Apple/USB stack files"
        assertTrue(buttons.contains(collectionOnly))
        val allowedUsbDiagnostics = setOf(
            collectionOnly,
            "Test USBMUX + Lockdown",
            "Test USBMUX version exchange",
            "Inspect local pairing records (NO USB)",
            "Claim/release configuration-5 USBMUX",
            "Test QDrive USB mode transition",
            "Read active USB configuration",
            "Map iPhone USB configurations",
            "Scan connected USB devices",
        )
        assertEquals(allowedUsbDiagnostics, buttons.filter { it.contains("USB") }.toSet())
        assertFalse(buttons.contains("Connect"))
        assertFalse(LegacyLaunchBuild.CONNECTIONS_ENABLED)
        assertFalse(LegacyLaunchBuild.VENDOR_INTEGRATION_ENABLED)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
