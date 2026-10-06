package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import com.shilapi.xcertplay.transport.Phase3BDeviceDiagnostics
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

class StartupApplication : Application() {
    override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
        vendorServiceBinds++
        throw AssertionError("Launcher must not bind a vendor service")
    }
    override fun getSystemService(name: String): Any? {
        if (name == Context.BLUETOOTH_SERVICE) {
            bluetoothServiceLookups++
            if (bluetoothServiceMissing) return null
        }
        return super.getSystemService(name)
    }

    companion object {
        var bluetoothServiceMissing = false
        var bluetoothServiceLookups = 0
        var vendorServiceBinds = 0
    }
}

@Implements(BluetoothManager::class)
class StartupBluetoothManagerShadow {
    @Implementation fun getAdapter(): BluetoothAdapter? {
        lookups++
        failure?.let { throw it }
        return adapter
    }

    companion object {
        var lookups = 0
        var adapter: BluetoothAdapter? = null
        var failure: Throwable? = null
    }
}

@Implements(BluetoothAdapter::class)
class StartupBluetoothAdapterShadow {
    companion object {
        var lookups = 0
        @JvmStatic @Implementation fun getDefaultAdapter(): BluetoothAdapter? {
            lookups++
            StartupBluetoothManagerShadow.failure?.let { throw it }
            return StartupBluetoothManagerShadow.adapter
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], qualifiers = "en", manifest = Config.NONE,
    application = StartupApplication::class,
    shadows = [StartupBluetoothManagerShadow::class, StartupBluetoothAdapterShadow::class])
class Phase3BLazyStartupTest {
    @Before fun resetVendor() {
        StartupBluetoothManagerShadow.lookups = 0
        StartupBluetoothAdapterShadow.lookups = 0
        StartupBluetoothManagerShadow.adapter = null
        StartupBluetoothManagerShadow.failure = null
        StartupApplication.bluetoothServiceMissing = false
        StartupApplication.bluetoothServiceLookups = 0
        StartupApplication.vendorServiceBinds = 0
    }

    @Test fun launcherAndSettingsInitializeWithNullBluetooth() = verifyLazyLaunch()

    @Test fun launcherAndSettingsInitializeWithUnavailableBluetoothService() {
        StartupApplication.bluetoothServiceMissing = true
        verifyLazyLaunch()
    }

    @Test fun launcherAndSettingsInitializeWithDisabledBluetooth() {
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(adapter.isEnabled).thenReturn(false)
        clearInvocations(adapter)
        StartupBluetoothManagerShadow.adapter = adapter
        verifyLazyLaunch()
        verifyNoInteractions(adapter)
    }

    @Test fun launcherAndSettingsInitializeWithThrowingVendorStack() {
        StartupBluetoothManagerShadow.failure = NoSuchMethodError("Vendor Bluetooth API missing")
        verifyLazyLaunch()
    }

    @Test fun launcherAndSettingsInitializeWithThrowingAdapterState() {
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(adapter.isEnabled).thenThrow(SecurityException("Vendor denies adapter state"))
        clearInvocations(adapter)
        StartupBluetoothManagerShadow.adapter = adapter
        verifyLazyLaunch()
        verifyNoInteractions(adapter)
    }

    @Test fun launcherAndSettingsInitializeWithThrowingBondedDeviceAccess() {
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(adapter.bondedDevices).thenThrow(IllegalStateException("Vendor denies bonded devices"))
        clearInvocations(adapter)
        StartupBluetoothManagerShadow.adapter = adapter
        verifyLazyLaunch()
        verifyNoInteractions(adapter)
    }

    @Test fun explicitRefreshContainsVendorRuntimeFailureWithoutRegisteringReceiver() =
        verifyRefreshFailure(IllegalStateException("Vendor binder failed"))

    @Test fun explicitRefreshContainsVendorLinkageFailureWithoutRegisteringReceiver() =
        verifyRefreshFailure(NoSuchMethodError("Vendor API missing"))

    private fun verifyRefreshFailure(error: Throwable) {
        StartupBluetoothManagerShadow.failure = error
        val completed = CountDownLatch(1)
        val diagnostics = Phase3BDeviceDiagnostics(RuntimeEnvironment.getApplication()) {}
        try {
            assertEquals("Phase 3B Bluetooth diagnostics: not sampled", diagnostics.diagnosticReport())
            diagnostics.refresh { completed.countDown() }
            assertTrue("Refresh did not complete: ${diagnostics.diagnosticReport()}", completed.await(5, TimeUnit.SECONDS))
            assertTrue("Expected ${error.javaClass.simpleName}: ${diagnostics.diagnosticReport()}",
                diagnostics.diagnosticReport().contains(error.javaClass.simpleName))
            assertFalse(receiverRegistered(diagnostics))
        } finally { diagnostics.close() }
    }

    @Test fun constructingAndClosingDiagnosticsNeverTouchesBluetooth() {
        StartupBluetoothManagerShadow.failure = NoSuchMethodError("Vendor API missing")
        val diagnostics = Phase3BDeviceDiagnostics(RuntimeEnvironment.getApplication()) {}
        diagnostics.close()
        assertEquals(0, StartupBluetoothManagerShadow.lookups)
        assertEquals(0, StartupBluetoothAdapterShadow.lookups)
        assertEquals(0, StartupApplication.bluetoothServiceLookups)
        assertFalse(receiverRegistered(diagnostics))
    }

    @Test fun explicitRefreshContainsVendorAdapterGetterFailure() {
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(adapter.isEnabled).thenThrow(SecurityException("Vendor denies adapter state"))
        StartupBluetoothManagerShadow.adapter = adapter
        val completed = CountDownLatch(1)
        val diagnostics = Phase3BDeviceDiagnostics(RuntimeEnvironment.getApplication()) {}
        try {
            diagnostics.refresh { completed.countDown() }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(diagnostics.diagnosticReport().contains("Adapter sample FAIL SecurityException"))
            assertFalse(receiverRegistered(diagnostics))
        } finally { diagnostics.close() }
    }

    @Test fun disabledAndroidAdapterIsNotMistakenForVehicleBluetoothState() {
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(adapter.isEnabled).thenReturn(false)
        `when`(adapter.bondedDevices).thenReturn(emptySet())
        StartupBluetoothManagerShadow.adapter = adapter
        val completed = CountDownLatch(1)
        val diagnostics = Phase3BDeviceDiagnostics(RuntimeEnvironment.getApplication()) {}
        try {
            diagnostics.refresh { completed.countDown() }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            val report = diagnostics.diagnosticReport()
            assertTrue(report.contains("Android Bluetooth service available: yes"))
            assertTrue(report.contains("Android Bluetooth adapter present: yes"))
            assertTrue(report.contains("Android adapter enabled: no"))
            assertTrue(report.contains("Bonded devices visible through Android APIs: 0"))
            assertTrue(report.contains("Vehicle/Android mismatch: possible"))
            assertTrue(report.contains("current vehicle/MCU state is not observable"))
            verify(adapter, never()).enable()
            verify(adapter, never()).startDiscovery()
        } finally { diagnostics.close() }
    }

    @Test fun unavailableManagerDoesNotFallBackToDefaultAdapterOrInferVehicleDisabled() {
        val context = mock(android.content.Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        StartupBluetoothManagerShadow.adapter = mock(BluetoothAdapter::class.java)
        val completed = CountDownLatch(1)
        val diagnostics = Phase3BDeviceDiagnostics(context) {}
        try {
            diagnostics.refresh { completed.countDown() }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            val report = diagnostics.diagnosticReport()
            assertTrue(report.contains("Android Bluetooth service available: no"))
            assertTrue(report.contains("Android Bluetooth adapter present: no"))
            assertTrue(report.contains("Vehicle/Android mismatch: possible"))
            assertEquals(0, StartupBluetoothAdapterShadow.lookups)
            assertFalse(receiverRegistered(diagnostics))
        } finally { diagnostics.close() }
    }

    @Test fun explicitRefreshContainsBondedDeviceFailureAndPreservesAdapterObservations() {
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(adapter.isEnabled).thenReturn(true)
        `when`(adapter.name).thenReturn("Android-only adapter")
        `when`(adapter.bondedDevices).thenThrow(IllegalStateException("Vendor bonded lookup failed"))
        StartupBluetoothManagerShadow.adapter = adapter
        val completed = CountDownLatch(1)
        val diagnostics = Phase3BDeviceDiagnostics(RuntimeEnvironment.getApplication()) {}
        try {
            diagnostics.refresh { completed.countDown() }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            val report = diagnostics.diagnosticReport()
            assertTrue(report.contains("Android Bluetooth service available: yes"))
            assertTrue(report.contains("Android adapter enabled: yes"))
            assertTrue(report.contains("Android adapter local name: Android-only adapter"))
            assertTrue(report.contains("Bonded devices: unavailable"))
            assertTrue(report.contains("Vendor bonded lookup failed"))
            assertTrue(report.contains("Scan supported: unknown"))
            assertFalse(receiverRegistered(diagnostics))
            verify(adapter, never()).enable()
            verify(adapter, never()).startDiscovery()
        } finally { diagnostics.close() }
    }

    private fun verifyLazyLaunch() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            controller.create().start().resume()
            assertNull(diagnostics(activity))
            assertNull(vendorDiagnostics(activity))
            assertNull(nforetekDiagnostics(activity))
            assertNull(cacheDiagnostics(activity))
            controller.newIntent(Intent(activity, DiPlayActivity::class.java).putExtra("page", "settings"))
            assertNull(diagnostics(activity))
            assertNull(vendorDiagnostics(activity))
            assertNull(nforetekDiagnostics(activity))
            assertNull(cacheDiagnostics(activity))
            controller.pause().resume()
            assertNull(diagnostics(activity))
            assertNull(cacheDiagnostics(activity))
            assertEquals(0, StartupApplication.vendorServiceBinds)
            assertEquals(0, StartupBluetoothManagerShadow.lookups)
            assertEquals(0, StartupBluetoothAdapterShadow.lookups)
            assertEquals(0, StartupApplication.bluetoothServiceLookups)
            DiPlayActivity::class.java.getDeclaredMethod("requestPhase3BAction", String::class.java)
                .apply { isAccessible = true }.invoke(activity, "test")
            assertNull(diagnostics(activity))
            assertEquals(0, StartupBluetoothManagerShadow.lookups)
        } finally { controller.pause().stop().destroy() }
    }

    private fun diagnostics(activity: DiPlayActivity): Any? =
        DiPlayActivity::class.java.getDeclaredField("phase3BDiagnostics").apply { isAccessible = true }.get(activity)

    private fun vendorDiagnostics(activity: DiPlayActivity): Any? =
        DiPlayActivity::class.java.getDeclaredField("vendorInvestigation").apply { isAccessible = true }.get(activity)

    private fun nforetekDiagnostics(activity: DiPlayActivity): Any? =
        DiPlayActivity::class.java.getDeclaredField("nforetekDiagnostics").apply { isAccessible = true }.get(activity)

    private fun cacheDiagnostics(activity: DiPlayActivity): Any? =
        DiPlayActivity::class.java.getDeclaredField("nforetekCacheDiagnostics").apply { isAccessible = true }.get(activity)

    private fun receiverRegistered(diagnostics: Phase3BDeviceDiagnostics): Boolean =
        Phase3BDeviceDiagnostics::class.java.getDeclaredField("receiverRegistered")
            .apply { isAccessible = true }.getBoolean(diagnostics)
}
