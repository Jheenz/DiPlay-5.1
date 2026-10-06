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
        assertTrue(buttons.contains("Read cached NForetek Bluetooth status"))
        assertFalse(buttons.contains("Scan for devices"))
        assertFalse(buttons.contains("Test RFCOMM connection"))
        assertFalse(buttons.contains("Bluetooth settings"))
        assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == sample })
        assertFalse(buttons.contains("Connect"))
        assertTrue(LegacyLaunchBuild.PHASE3B_DIAGNOSTICS_ENABLED)
        assertFalse(LegacyLaunchBuild.PHASE3B_TRANSPORT_TESTS_ENABLED)
        assertFalse(LegacyLaunchBuild.CONNECTIONS_ENABLED)
        assertFalse(LegacyLaunchBuild.VENDOR_INTEGRATION_ENABLED)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
