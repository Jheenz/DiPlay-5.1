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
    @Test fun legacyServiceLookupAndReportDoNotRequireRuntimePermissionsOrStartTransport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(BluetoothCompatibility.missingPermissions(context, scan = true).isEmpty())
        BluetoothCompatibility.adapter(context) // Null is valid on an emulator without Bluetooth.
        val sampled = CountDownLatch(1)
        val diagnostics = Phase3BDeviceDiagnostics(context) {
            if (it.contains("Bluetooth adapter present:")) sampled.countDown()
        }
        try {
            assertTrue(sampled.await(5, TimeUnit.SECONDS))
            val report = diagnostics.diagnosticReport()
            assertTrue(report.contains("RFCOMM result: not started"))
            assertTrue(report.contains("Socket connected: no"))
            assertTrue(report.contains("Bytes sent=0; received=0"))
            assertTrue(report.contains("Manual scan: not started"))
        } finally { diagnostics.close() }
    }
}
