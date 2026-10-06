package com.shilapi.xcertplay.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 22, maxSdkVersion = 22)
class LegacyBluetoothApi22DeviceTest {
    @Test fun manuallyInspectedNForetekMetadataNeverBindsAutomatically() {
        val inspected = CountDownLatch(1)
        val diagnostics = NForetekServiceDiagnostics(InstrumentationRegistry.getInstrumentation().targetContext) {
            if (it.contains("Inspection complete")) inspected.countDown()
        }
        try {
            assertTrue(diagnostics.diagnosticReport().contains("not inspected"))
            diagnostics.inspect()
            assertTrue(inspected.await(30, TimeUnit.SECONDS))
            assertTrue(diagnostics.diagnosticReport().contains("no BIND_AUTO_CREATE"))
            assertFalse(diagnostics.diagnosticReport().contains("bindService returned="))
        } finally { diagnostics.close() }
    }

    @Test fun manuallyStartedVendorInventoryIsApi22SafeAndDoesNotStartTransport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inventory = CountDownLatch(1)
        val diagnostics = VehicleBluetoothInvestigation(context) {
            if (it.contains("Property file /system/vendor/build.prop")) inventory.countDown()
        }
        try {
            assertTrue(diagnostics.diagnosticReport().contains("not started"))
            diagnostics.start()
            assertTrue(inventory.await(15, TimeUnit.SECONDS))
            assertTrue(diagnostics.diagnosticReport().contains("no binding, commands, adapter lookup, scan, RFCOMM or iAP2"))
            assertTrue(diagnostics.diagnosticReport().contains("Visible packages=") ||
                diagnostics.diagnosticReport().contains("Package enumeration FAIL"))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { diagnostics.close() }
        }
    }

    @Test fun legacyServiceLookupAndReportDoNotRequireRuntimePermissionsOrStartTransport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(BluetoothCompatibility.missingPermissions(context, scan = true).isEmpty())
        val sampled = CountDownLatch(1)
        val diagnostics = Phase3BDeviceDiagnostics(context) {}
        try {
            assertTrue(diagnostics.diagnosticReport().contains("not sampled"))
            diagnostics.refresh { sampled.countDown() }
            assertTrue(sampled.await(5, TimeUnit.SECONDS))
            val report = diagnostics.diagnosticReport()
            assertTrue(report.contains("Android Bluetooth service available:"))
            assertTrue(report.contains("Android Bluetooth adapter present:"))
            assertTrue(report.contains("Android adapter enabled:"))
            assertTrue(report.contains("Scan supported: unknown"))
            assertTrue(report.contains("RFCOMM result: not started"))
            assertTrue(report.contains("Socket connected: no"))
            assertTrue(report.contains("Bytes sent=0; received=0"))
            assertTrue(report.contains("Manual scan: not started"))
        } finally { diagnostics.close() }
    }
}
