package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import com.shilapi.xcertplay.transport.NForetekApkInterfaces
import com.shilapi.xcertplay.transport.NForetekServiceDiagnostics
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

object NForetekInitializationWitness { var initialized = false }
class NForetekInterfaceMetadataFixture {
    companion object { init { NForetekInitializationWitness.initialized = true } }
    fun sppWrite(bytes: ByteArray): Boolean = throw AssertionError("Metadata must never invoke sppWrite")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], manifest = Config.NONE)
class Phase3B2NForetekTest {
    private val component = ComponentName("com.nforetek.bt", "com.nforetek.bt.service.NfServiceBluetooth")

    private class Fixture(
        val context: Context,
        val pm: PackageManager,
        val info: ServiceInfo,
        val diagnostics: NForetekServiceDiagnostics,
        val inspected: CountDownLatch,
    ) {
        var connection: ServiceConnection? = null
    }

    private fun fixture(exported: Boolean = true, permission: String? = null, granted: Boolean = true): Fixture {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        val app = ApplicationInfo().apply { packageName = component.packageName; enabled = true }
        val info = ServiceInfo().apply {
            packageName = component.packageName; name = component.className; applicationInfo = app
            this.exported = exported; enabled = true; this.permission = permission
        }
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.packageName).thenReturn("com.shihab.diplay.legacytest")
        `when`(context.packageManager).thenReturn(pm)
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(PackageInfo().apply {
            packageName = component.packageName; applicationInfo = app; services = arrayOf(info)
        }))
        `when`(pm.getServiceInfo(eq(component), anyInt())).thenReturn(info)
        if (permission != null) {
            `when`(pm.checkPermission(eq(permission), anyString())).thenReturn(
                if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED)
            `when`(pm.getPermissionInfo(eq(permission), anyInt())).thenReturn(PermissionInfo().apply {
                protectionLevel = PermissionInfo.PROTECTION_SIGNATURE
            })
        }
        val inspected = CountDownLatch(1)
        val diagnostics = NForetekServiceDiagnostics(context) {
            if (it.contains("Inspection complete")) inspected.countDown()
        }
        val result = Fixture(context, pm, info, diagnostics, inspected)
        `when`(context.bindService(any(), any(), eq(0))).thenAnswer {
            result.connection = it.getArgument(1)
            true
        }
        return result
    }

    private fun inspect(f: Fixture) {
        f.diagnostics.inspect()
        assertTrue(f.inspected.await(5, TimeUnit.SECONDS))
    }

    @Test fun inertConstructorAndUiNeverBindOrLookupBluetooth() {
        val f = fixture()
        try {
            assertTrue(f.diagnostics.diagnosticReport().contains("not inspected"))
            verify(f.context, never()).bindService(any(), any(), anyInt())
            verify(f.context, never()).getSystemService(anyString())
            verify(f.pm, never()).getInstalledPackages(anyInt())
            f.diagnostics.testBind(component)
            verify(f.context, never()).bindService(any(), any(), anyInt())
            assertTrue(f.diagnostics.diagnosticReport().contains("not in manually inspected eligible list"))
        } finally { f.diagnostics.close() }
    }

    @Test fun inspectionListsPermissionAndUnreadableApkWithoutBinding() {
        val f = fixture(permission = "com.nforetek.permission.BT", granted = false)
        try {
            inspect(f)
            val report = f.diagnostics.diagnosticReport()
            assertTrue(report.contains(component.flattenToString()))
            assertTrue(report.contains("permission=com.nforetek.permission.BT"))
            assertTrue(report.contains("base=signature"))
            assertTrue(report.contains("grantedToDiPlay=false"))
            assertTrue(report.contains("APK interfaces com.nforetek.bt FAIL"))
            assertFalse(f.diagnostics.candidates().single().bindEligible)
            f.diagnostics.testBind(component)
            verify(f.context, never()).bindService(any(), any(), anyInt())
        } finally { f.diagnostics.close() }
    }

    @Test fun nonexportedServiceIsNeverBound() {
        val f = fixture(exported = false)
        try {
            inspect(f)
            assertFalse(f.diagnostics.candidates().single().bindEligible)
            f.diagnostics.testBind(component)
            verify(f.context, never()).bindService(any(), any(), anyInt())
        } finally { f.diagnostics.close() }
    }

    @Test fun sppMetadataRemainsVisibleButOldBindSelectorCannotBindIt() {
        val f = fixture()
        val spp = ComponentName(component.packageName, "com.nforetek.bt.service.NfServiceSpp")
        f.info.name = spp.className
        try {
            inspect(f)
            assertEquals(spp, f.diagnostics.candidates().single().component)
            assertFalse(f.diagnostics.candidates().single().bindEligible)
            assertTrue(f.diagnostics.diagnosticReport().contains("SPP binding disabled"))
            f.diagnostics.testBind(spp)
            verify(f.context, never()).bindService(any(), any(), anyInt())
        } finally { f.diagnostics.close() }
    }

    @Test fun explicitBindCapturesDescriptorAndImmediatelyUnbindsWithoutTransactions() {
        val f = fixture()
        val binder = mock(IBinder::class.java)
        `when`(binder.interfaceDescriptor).thenReturn("com.nforetek.bt.aidl.INfSpp")
        `when`(binder.isBinderAlive).thenReturn(true)
        try {
            inspect(f)
            f.diagnostics.testBind(component)
            requireNotNull(f.connection).onServiceConnected(component, binder)
            awaitReport(f.diagnostics, "Unbound ")
            val report = f.diagnostics.diagnosticReport()
            assertTrue(report.contains("Binder descriptor=com.nforetek.bt.aidl.INfSpp"))
            assertTrue(report.contains("Binder class="))
            assertTrue(report.contains("no asInterface/vendor method invoked"))
            verify(f.context).bindService(argThat<Intent> { this.component == component }, any(), eq(0))
            verify(f.context, times(1)).unbindService(requireNotNull(f.connection))
            verify(binder, never()).transact(anyInt(), any(), any(), anyInt())
            verify(f.context, never()).startService(any())
            verify(f.context, never()).sendBroadcast(any())
        } finally { f.diagnostics.close() }
    }

    @Test fun bindTimeoutUnbindsAndIgnoresLateCallback() {
        val f = fixture()
        try {
            inspect(f)
            f.diagnostics.testBind(component)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertTrue(f.diagnostics.diagnosticReport().contains("TIMEOUT"))
            verify(f.context, times(1)).unbindService(requireNotNull(f.connection))
            val report = f.diagnostics.diagnosticReport()
            val binder = mock(IBinder::class.java)
            requireNotNull(f.connection).onServiceConnected(component, binder)
            verifyNoInteractions(binder)
            assertEquals(report, f.diagnostics.diagnosticReport())
        } finally { f.diagnostics.close() }
    }

    @Test fun bindFalseAndSecurityFailuresAreVisible() {
        val f = fixture()
        try {
            inspect(f)
            `when`(f.context.bindService(any(), any(), eq(0))).thenReturn(false)
            f.diagnostics.testBind(component)
            assertTrue(f.diagnostics.diagnosticReport().contains("bindService returned false"))
            verify(f.context).unbindService(any())
            `when`(f.context.bindService(any(), any(), eq(0))).thenThrow(SecurityException("Vendor signature required"))
            f.diagnostics.testBind(component)
            assertTrue(f.diagnostics.diagnosticReport().contains("Bind FAIL SecurityException"))
        } finally { f.diagnostics.close() }
    }

    @Test fun nullBinderAndDisconnectAreContained() {
        val f = fixture()
        try {
            inspect(f)
            f.diagnostics.testBind(component)
            requireNotNull(f.connection).onServiceConnected(component, null)
            assertTrue(f.diagnostics.diagnosticReport().contains("FAIL null Binder"))
            f.diagnostics.testBind(component)
            requireNotNull(f.connection).onServiceDisconnected(component)
            assertTrue(f.diagnostics.diagnosticReport().contains("service disconnected/crashed"))
            verify(f.context, times(2)).unbindService(any())
        } finally { f.diagnostics.close() }
    }

    @Test fun metadataExceptionUnbindsAndCloseCancelsActiveTest() {
        val f = fixture()
        val binder = mock(IBinder::class.java)
        `when`(binder.interfaceDescriptor).thenThrow(IllegalStateException("Vendor descriptor failed"))
        try {
            inspect(f)
            f.diagnostics.testBind(component)
            requireNotNull(f.connection).onServiceConnected(component, binder)
            awaitReport(f.diagnostics, "Unbound ")
            assertTrue(f.diagnostics.diagnosticReport().contains("Vendor descriptor failed"))
            assertTrue(f.diagnostics.diagnosticReport().contains("FAIL Binder metadata unavailable"))
            f.diagnostics.testBind(component)
            f.diagnostics.close()
            assertTrue(f.diagnostics.diagnosticReport().contains("Cancelled"))
            verify(f.context, times(2)).unbindService(any())
        } finally { f.diagnostics.close() }
    }

    @Test fun stalledMetadataTimesOutWithoutBlockingInventoryOrAddingAnotherBind() {
        val f = fixture()
        val binder = mock(IBinder::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        `when`(binder.interfaceDescriptor).thenAnswer {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            "vendor.delayed.Interface"
        }
        try {
            inspect(f)
            f.diagnostics.testBind(component)
            requireNotNull(f.connection).onServiceConnected(component, binder)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertTrue(f.diagnostics.diagnosticReport().contains("TIMEOUT"))
            f.diagnostics.testBind(component)
            assertTrue(f.diagnostics.diagnosticReport().contains("IPC remains outstanding"))
            verify(f.context, times(1)).bindService(any(), any(), eq(0))
            verify(f.context, times(1)).unbindService(any())
            f.diagnostics.inspect()
            awaitReport(f.diagnostics, "Inspection complete")
            assertTrue(f.diagnostics.candidates().isNotEmpty())
        } finally {
            release.countDown()
            f.diagnostics.close()
        }
    }

    @Test fun unknownServicesAndNoninterfaceClassesAreNotInvokedOrLoaded() {
        assertTrue(NForetekServiceDiagnostics.knownService(component.packageName, component.className))
        assertFalse(NForetekServiceDiagnostics.knownService(component.packageName, "com.nforetek.bt.service.Unknown"))
        assertFalse(NForetekServiceDiagnostics.knownService("malicious.package", component.className))
        assertTrue(NForetekApkInterfaces.interfaceCandidate("com.nforetek.bt.aidl.INfSpp\$Stub\$Proxy"))
        assertFalse(NForetekApkInterfaces.interfaceCandidate(component.className))
        assertFalse(NForetekApkInterfaces.interfaceCandidate("unrelated.IFoo\$Stub"))
    }

    @Test fun classMetadataDoesNotInitializeOrInvokeAndLoadingFailuresAreReported() {
        NForetekInitializationWitness.initialized = false
        val output = mutableListOf<String>()
        val name = "com.shilapi.xcertplay.NForetekInterfaceMetadataFixture"
        val loader = requireNotNull(javaClass.classLoader)
        NForetekApkInterfaces.inspectClass(name, loader, output::add)
        assertTrue(output.any { it.contains("sppWrite(") })
        assertFalse(NForetekInitializationWitness.initialized)
        NForetekApkInterfaces.inspectClass("missing.vendor.Aidl", loader, output::add)
        assertTrue(output.any { it.contains("FAIL ClassNotFoundException") })
    }

    private fun awaitReport(diagnostics: NForetekServiceDiagnostics, text: String) {
        for (i in 0 until 100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (text in diagnostics.diagnosticReport()) return
            Thread.sleep(10)
        }
        fail("Missing $text: ${diagnostics.diagnosticReport()}")
    }
}
