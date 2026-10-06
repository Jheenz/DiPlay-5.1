package com.shilapi.xcertplay

import android.content.Context
import android.content.ServiceConnection
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import com.shilapi.xcertplay.transport.NForetekBluetoothCacheStatus
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

interface CacheStatusFixtureInterface {
    fun getBtLocalName(): String?
    fun getBtLocalAddress(): String?
    fun isBtEnabled(): Boolean
    fun getBtState(): Int
    fun getNfServiceVersionName(): String?
    fun reqBtPairedDevices(): Boolean
    fun setBtEnable(value: Boolean): Boolean
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], manifest = Config.NONE)
class Phase3B3CacheStatusTest {
    @get:Rule val folder = TemporaryFolder()
    private val component = NForetekBluetoothCacheStatus.COMPONENT

    private inner class Fixture(useInstalledResolver: Boolean = false) {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        val api = mock(CacheStatusFixtureInterface::class.java)
        val binder = mock(IBinder::class.java)
        var connection: ServiceConnection? = null
        val diagnostics: NForetekBluetoothCacheStatus
        var resolutions = 0

        init {
            val app = ApplicationInfo().apply { enabled = true; packageName = component.packageName }
            val info = ServiceInfo().apply {
                packageName = component.packageName; name = component.className
                enabled = true; exported = true; applicationInfo = app
            }
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.packageManager).thenReturn(pm)
            `when`(context.packageName).thenReturn("test.app")
            `when`(pm.getServiceInfo(eq(component), anyInt())).thenReturn(info)
            `when`(pm.getApplicationInfo(eq(component.packageName), anyInt())).thenReturn(app)
            `when`(context.bindService(any(), any(), eq(0))).thenAnswer {
                connection = it.getArgument(1)
                true
            }
            `when`(binder.interfaceDescriptor).thenReturn(NForetekBluetoothCacheStatus.DESCRIPTOR)
            `when`(api.getBtLocalName()).thenReturn("GEELY_BT")
            `when`(api.getBtLocalAddress()).thenReturn("11:22:33:44:55:66")
            `when`(api.isBtEnabled()).thenReturn(true)
            `when`(api.getBtState()).thenReturn(302)
            `when`(api.getNfServiceVersionName()).thenReturn("cached-v1")
            clearInvocations(api)
            diagnostics = if (useInstalledResolver) NForetekBluetoothCacheStatus(context, {})
            else NForetekBluetoothCacheStatus(context, {}, { _, _ ->
                resolutions++
                NForetekBluetoothCacheStatus.CacheInterface(CacheStatusFixtureInterface::class.java, api)
            })
        }

        fun connect() {
            diagnostics.start()
            requireNotNull(connection).onServiceConnected(component, binder)
        }

        fun await(text: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                if (diagnostics.diagnosticReport().contains(text)) return
                Thread.sleep(10)
            }
            fail("Missing $text in ${diagnostics.diagnosticReport()}")
        }
    }

    @Test fun constructorReportAndCloseAreCompletelyInert() {
        val f = Fixture()
        assertTrue(f.diagnostics.diagnosticReport().contains("not sampled"))
        f.diagnostics.close()
        verifyNoInteractions(f.pm, f.api, f.binder)
        verify(f.context, never()).bindService(any(), any(), anyInt())
        verify(f.context, never()).getSystemService(anyString())
        assertEquals(0, f.resolutions)
    }

    @Test fun explicitZeroFlagBindReadsExactlyFiveGettersAndUnbinds() {
        val f = Fixture()
        try {
            f.connect()
            f.await("Unbind SUCCESS")
            val report = f.diagnostics.diagnosticReport()
            assertTrue(report.contains("Binder descriptor=${NForetekBluetoothCacheStatus.DESCRIPTOR}"))
            assertTrue(report.contains("Interface resolved="))
            assertTrue(report.contains("getBtLocalName raw=GEELY_BT"))
            assertTrue(report.contains("getBtLocalAddress raw=11:22:33:44:55:66"))
            assertTrue(report.contains("isBtEnabled raw=true; cached enabled=YES"))
            assertTrue(report.contains("getBtState raw=302; interpreted state=ON"))
            assertTrue(report.contains("getNfServiceVersionName raw=cached-v1"))
            verify(f.api).getBtLocalName()
            verify(f.api).getBtLocalAddress()
            verify(f.api).isBtEnabled()
            verify(f.api).getBtState()
            verify(f.api).getNfServiceVersionName()
            verifyNoMoreInteractions(f.api)
            verify(f.context).bindService(argThat<Intent> { it.component == component && it.action == null }, any(), eq(0))
            verify(f.context).unbindService(requireNotNull(f.connection))
            verify(f.context, never()).startService(any())
            verify(f.context, never()).stopService(any())
            verify(f.context, never()).getSystemService(anyString())
            verify(f.binder, never()).transact(anyInt(), any(), any(), anyInt())
        } finally { f.diagnostics.close() }
    }

    @Test fun throwingGetterIsReportedAndRemainingCacheValuesAreRead() {
        val f = Fixture()
        `when`(f.api.getBtLocalName()).thenThrow(SecurityException("vendor denies name"))
        `when`(f.api.getBtState()).thenReturn(300)
        try {
            f.connect()
            f.await("Unbind SUCCESS")
            assertTrue(f.diagnostics.diagnosticReport().contains("getBtLocalName FAIL SecurityException: vendor denies name"))
            assertTrue(f.diagnostics.diagnosticReport().contains("getBtState raw=300; interpreted state=OFF"))
            verify(f.api, never()).reqBtPairedDevices()
        } finally { f.diagnostics.close() }
    }

    @Test fun nullAndUnknownValuesAreNotSuccessShapedDefaults() {
        assertTrue(NForetekBluetoothCacheStatus.interpret("getBtState", 999).contains("UNKNOWN"))
        assertTrue(NForetekBluetoothCacheStatus.interpret("getBtLocalName", null).contains("unavailable"))
        assertTrue(NForetekBluetoothCacheStatus.interpret("getBtLocalAddress", "00:00:00:00:00:00").contains("zero"))
    }

    @Test fun unknownDescriptorNeverResolvesOrCallsInterface() {
        val f = Fixture()
        `when`(f.binder.interfaceDescriptor).thenReturn("com.nforetek.bt.aidl.INfCommandSpp")
        try {
            f.connect()
            f.await("Unbind SUCCESS")
            assertTrue(f.diagnostics.diagnosticReport().contains("Unexpected Binder descriptor"))
            assertEquals(0, f.resolutions)
            verifyNoInteractions(f.api)
        } finally { f.diagnostics.close() }
    }

    @Test fun timeoutUnbindsAndIgnoresLateConnection() {
        val f = Fixture()
        try {
            f.diagnostics.start()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertTrue(f.diagnostics.diagnosticReport().contains("TIMEOUT"))
            requireNotNull(f.connection).onServiceConnected(component, f.binder)
            verifyNoInteractions(f.binder, f.api)
            verify(f.context, times(1)).unbindService(any())
        } finally { f.diagnostics.close() }
    }

    @Test fun blockedGetterDoesNotBlockCleanupOrPermitAnotherSample() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        `when`(f.api.getBtLocalName()).thenAnswer {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            returned.countDown()
            "late"
        }
        clearInvocations(f.api)
        val second = Fixture()
        try {
            f.connect()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertTrue(f.diagnostics.diagnosticReport().contains("TIMEOUT"))
            verify(f.context).unbindService(any())
            second.diagnostics.start()
            assertTrue(second.diagnostics.diagnosticReport().contains("previous vendor IPC remains outstanding"))
            verify(second.context, never()).bindService(any(), any(), anyInt())
            release.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            val worker = NForetekBluetoothCacheStatus::class.java.getDeclaredField("worker")
                .apply { isAccessible = true }.get(f.diagnostics) as java.util.concurrent.ExecutorService
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            verify(f.api, never()).getBtLocalAddress()
            assertFalse(f.diagnostics.diagnosticReport().contains("raw=late"))
        } finally { release.countDown(); f.diagnostics.close(); second.diagnostics.close() }
    }

    @Test fun bindFalseNullBinderAndSecurityExceptionAreContained() {
        val f = Fixture()
        try {
            `when`(f.context.bindService(any(), any(), eq(0))).thenReturn(false)
            f.diagnostics.start()
            assertTrue(f.diagnostics.diagnosticReport().contains("bind rejected"))
            verify(f.context).unbindService(any())
        } finally { f.diagnostics.close() }
        val nullBinder = Fixture()
        try {
            nullBinder.diagnostics.start()
            requireNotNull(nullBinder.connection).onServiceConnected(component, null)
            assertTrue(nullBinder.diagnostics.diagnosticReport().contains("FAIL null Binder"))
        } finally { nullBinder.diagnostics.close() }
        val denied = Fixture()
        try {
            `when`(denied.context.bindService(any(), any(), eq(0))).thenThrow(SecurityException("denied"))
            denied.diagnostics.start()
            assertTrue(denied.diagnostics.diagnosticReport().contains("Bind FAIL SecurityException"))
        } finally { denied.diagnostics.close() }
    }

    @Test fun unbindFailureAndServiceDisconnectAreVisible() {
        val f = Fixture()
        try {
            doThrow(IllegalArgumentException("vendor unbind failure")).`when`(f.context).unbindService(any())
            f.diagnostics.start()
            requireNotNull(f.connection).onServiceDisconnected(component)
            assertTrue(f.diagnostics.diagnosticReport().contains("disconnected/crashed"))
            assertTrue(f.diagnostics.diagnosticReport().contains("Unbind FAIL IllegalArgumentException"))
        } finally { f.diagnostics.close() }
    }

    @Test fun unvalidatedInstalledApkIsRejectedBeforeVendorClassLoading() {
        val f = Fixture(useInstalledResolver = true)
        val source = folder.newFile("changed-vendor.apk").apply { writeText("not audited") }
        `when`(f.pm.getApplicationInfo(eq(component.packageName), anyInt())).thenReturn(ApplicationInfo().apply { sourceDir = source.absolutePath })
        try {
            f.connect()
            f.await("Unbind SUCCESS")
            assertTrue(f.diagnostics.diagnosticReport().contains("differs from statically audited version"))
            verifyNoInteractions(f.api)
            verify(f.context, never()).getCodeCacheDir()
        } finally { f.diagnostics.close() }
    }

    @Test fun closeDuringManualBindUnbindsOnceWithoutReading() {
        val f = Fixture()
        f.diagnostics.start()
        f.diagnostics.close()
        f.diagnostics.close()
        requireNotNull(f.connection).onServiceConnected(component, f.binder)
        verify(f.context, times(1)).unbindService(any())
        verifyNoInteractions(f.binder, f.api)
    }

    @Test fun missingServiceAndDisabledComponentNeverBind() {
        val missing = Fixture()
        try {
            `when`(missing.pm.getServiceInfo(eq(component), anyInt())).thenThrow(PackageManager.NameNotFoundException("missing"))
            missing.diagnostics.start()
            assertTrue(missing.diagnostics.diagnosticReport().contains("Bind FAIL NameNotFoundException"))
            verify(missing.context, never()).bindService(any(), any(), anyInt())
        } finally { missing.diagnostics.close() }
        val disabled = Fixture()
        try {
            `when`(disabled.pm.getComponentEnabledSetting(component)).thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            disabled.diagnostics.start()
            assertTrue(disabled.diagnostics.diagnosticReport().contains("not exported/enabled"))
            verify(disabled.context, never()).bindService(any(), any(), anyInt())
        } finally { disabled.diagnostics.close() }
    }

    @Test fun vendorLinkageFailureIsContainedAndFalseEnabledIsDisplayed() {
        val f = Fixture()
        `when`(f.api.getBtLocalName()).thenThrow(NoSuchMethodError("vendor linkage"))
        `when`(f.api.isBtEnabled()).thenReturn(false)
        try {
            f.connect()
            f.await("Unbind SUCCESS")
            assertTrue(f.diagnostics.diagnosticReport().contains("FAIL NoSuchMethodError: vendor linkage"))
            assertTrue(f.diagnostics.diagnosticReport().contains("isBtEnabled raw=false; cached enabled=NO"))
        } finally { f.diagnostics.close() }
    }

    @Test fun cacheInterfaceRejectsEveryNonallowlistedMethod() {
        val f = Fixture()
        val api = NForetekBluetoothCacheStatus.CacheInterface(CacheStatusFixtureInterface::class.java, f.api)
        try {
            api.read("reqBtPairedDevices")
            fail("Forbidden method must be rejected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Not an approved cache getter"))
        } finally { f.diagnostics.close() }
        verifyNoInteractions(f.api)
    }
}
