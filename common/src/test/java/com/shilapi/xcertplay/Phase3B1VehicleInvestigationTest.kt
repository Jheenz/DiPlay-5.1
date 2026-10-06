package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Looper
import com.shilapi.xcertplay.transport.VehicleBluetoothInvestigation
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], manifest = Config.NONE)
class Phase3B1VehicleInvestigationTest {
    @Test fun constructorAndCloseAreLazy() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        clearInvocations(context)
        val investigation = VehicleBluetoothInvestigation(context) {}
        assertTrue(investigation.diagnosticReport().contains("not started"))
        investigation.close()
        verify(context, never()).getSystemService(anyString())
        verify(context, never()).packageManager
        verify(context, never()).registerReceiver(any(), any())
    }

    @Test fun publicInventoryContainsExportedMetadataAndBoundedBroadcastsWithoutValues() {
        val app = RuntimeEnvironment.getApplication()
        val info = ApplicationInfo().apply {
            packageName = "com.geely.bluetooth"
            name = "VehicleBluetoothApplication"
            nonLocalizedLabel = "GEELY_BT vehicle UI"
            enabled = true
        }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = info.packageName
            applicationInfo = info
            services = arrayOf(ServiceInfo().apply {
                packageName = info.packageName; name = "com.geely.bluetooth.BtProxy"
                applicationInfo = info; exported = true; enabled = true; permission = "geely.permission.BT"
            }, ServiceInfo().apply {
                packageName = info.packageName; name = "com.geely.bluetooth.PrivateService"
                applicationInfo = info; exported = false
            })
        })
        val ready = CountDownLatch(1)
        val investigation = VehicleBluetoothInvestigation(app) {
            if (it.contains("safe relevant entries=") || it.contains("Property file /system/vendor/build.prop FAIL")) ready.countDown()
        }
        try {
            investigation.start()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            awaitObservation(investigation)
            val report = investigation.diagnosticReport()
            assertTrue(report.contains("com.geely.bluetooth/com.geely.bluetooth.BtProxy"))
            assertTrue(report.contains("permission=geely.permission.BT"))
            assertFalse(report.contains("PrivateService"))
            investigation.start()
            assertEquals(report, investigation.diagnosticReport())
            app.sendBroadcast(Intent("android.bluetooth.adapter.action.STATE_CHANGED")
                .putExtra("android.bluetooth.adapter.extra.STATE", 12).putExtra("secret_test", "DO_NOT_EXPORT"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(investigation.diagnosticReport().contains("state=12"))
            assertFalse(investigation.diagnosticReport().contains("DO_NOT_EXPORT"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertFalse(registered(investigation))
            assertTrue(investigation.diagnosticReport().contains("30-second window completed"))
        } finally { investigation.close() }
    }

    @Test fun unavailableAndThrowingServicesAndPackageManagerAreContained() {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.packageManager).thenReturn(pm)
        `when`(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenThrow(SecurityException("Vendor service denied"))
        `when`(pm.getInstalledPackages(anyInt())).thenThrow(IllegalStateException("Package inventory denied"))
        doThrow(UnsupportedOperationException("Receiver unavailable")).`when`(context).registerReceiver(any(), any())
        val ready = CountDownLatch(1)
        val investigation = VehicleBluetoothInvestigation(context) {
            if (it.contains("Property file /system/vendor/build.prop")) ready.countDown()
        }
        try {
            investigation.start()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            // Drain worker's registration post after its inventory callback.
            for (i in 0 until 100) {
                shadowOf(Looper.getMainLooper()).idle()
                if (investigation.diagnosticReport().contains("Broadcast registration FAIL")) break
                Thread.sleep(10)
            }
            val report = investigation.diagnosticReport()
            assertTrue(report.contains("Vendor service denied"))
            assertTrue(report.contains("Package inventory denied"))
            assertTrue(report.contains("Receiver unavailable"))
            assertFalse(registered(investigation))
            verify(context, never()).bindService(any(), any(), anyInt())
            verify(context, never()).startService(any())
            verify(context, never()).sendBroadcast(any())
        } finally { investigation.close() }
    }

    @Test fun nameAndPropertyFiltersExcludeSecrets() {
        for (name in listOf("GEELY_BT", "com.ecarx.phone", "SmartPlatform.MCU", "BluetoothProxy", "car.hfp")) {
            assertTrue(VehicleBluetoothInvestigation.matchesName(name))
        }
        assertFalse(VehicleBluetoothInvestigation.matchesName("org.example.maps"))
        assertTrue(VehicleBluetoothInvestigation.safePropertyKey("ro.product.model"))
        assertTrue(VehicleBluetoothInvestigation.safePropertyKey("persist.bt.enabled"))
        for (key in listOf("persist.bt.password", "ro.serialno", "persist.bluetooth.address", "geely.private.key")) {
            assertFalse(VehicleBluetoothInvestigation.safePropertyKey(key))
        }
    }

    @Test fun activityDestructionUnregistersAndCloseIsIdempotent() {
        val app = RuntimeEnvironment.getApplication()
        val ready = CountDownLatch(1)
        val investigation = VehicleBluetoothInvestigation(app) {
            if (it.contains("Property file /system/vendor/build.prop")) ready.countDown()
        }
        investigation.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        awaitObservation(investigation)
        investigation.close()
        investigation.close()
        assertFalse(registered(investigation))
        val report = investigation.diagnosticReport()
        assertTrue(report.contains("activity destroyed"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(report, investigation.diagnosticReport())
    }

    @Test fun manifestDiscoveryUsesOnlyExportedReceiverActionsAndResolvesRelativeNames() {
        val xml = android.util.Xml.newPullParser()
        xml.setInput(java.io.StringReader("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application>
                <service android:name=".BtService"><intent-filter><action android:name="vendor.bt.SERVICE_COMMAND"/></intent-filter></service>
                <receiver android:name=".BtReceiver"><intent-filter><action android:name="vendor.bt.STATE"/></intent-filter></receiver>
                <receiver android:name="PhoneReceiver"><intent-filter><action android:name="vendor.phone.STATE"/></intent-filter></receiver>
                <receiver android:name=".Private"><intent-filter><action android:name="vendor.bt.PRIVATE"/></intent-filter></receiver>
              </application>
            </manifest>
        """.trimIndent()))
        assertEquals(listOf("com.geely.BtReceiver" to "vendor.bt.STATE", "com.geely.PhoneReceiver" to "vendor.phone.STATE"),
            VehicleBluetoothInvestigation.receiverActions(xml, "com.geely", setOf("com.geely.BtReceiver", "com.geely.PhoneReceiver")))
    }

    private fun awaitObservation(investigation: VehicleBluetoothInvestigation) {
        for (i in 0 until 100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (registered(investigation)) return
            Thread.sleep(10)
        }
        fail("Observation did not start: ${investigation.diagnosticReport()}")
    }

    private fun registered(investigation: VehicleBluetoothInvestigation): Boolean =
        VehicleBluetoothInvestigation::class.java.getDeclaredField("registered")
            .apply { isAccessible = true }.getBoolean(investigation)
}
