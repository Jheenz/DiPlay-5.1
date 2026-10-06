package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], manifest = Config.NONE)
class VendorBluetoothApkExportTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun copiesExactBytesVerifiesHashAndPreservesSource() {
        val source = folder.newFile("installed.apk").apply { writeBytes(byteArrayOf(0, 1, 2, -1)) }
        val destination = File(folder.newFolder("export"), "btphoneNF.apk")
        val result = VendorBluetoothApkExport.copyVerified(source, destination)
        assertArrayEquals(source.readBytes(), destination.readBytes())
        assertEquals(4L, source.length())
        assertTrue(result.contains("size=4"))
        assertTrue(result.contains("path=${destination.absolutePath}"))
        assertTrue(result.contains("SHA-256="))
        assertEquals(listOf("btphoneNF.apk"), destination.parentFile!!.list()!!.toList())
    }

    @Test fun packagePathsAreResolvedAndIndependentFailuresRemainVisible() {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        val source = folder.newFile("actual-installed-location.apk").apply { writeText("test APK bytes") }
        `when`(context.packageManager).thenReturn(pm)
        `when`(context.packageName).thenReturn("test.app")
        `when`(context.getExternalFilesDir(null)).thenReturn(folder.newFolder("external"))
        `when`(pm.checkPermission(anyString(), anyString())).thenReturn(PackageManager.PERMISSION_DENIED)
        `when`(pm.getApplicationInfo("com.neusoft.geely.btphone.nf", 0)).thenReturn(ApplicationInfo().apply {
            sourceDir = source.absolutePath
        })
        `when`(pm.getApplicationInfo("com.nforetek.bt", 0)).thenThrow(PackageManager.NameNotFoundException("not installed"))
        val result = VendorBluetoothApkExport.export(context)
        assertTrue(result.contains("source=${source.absolutePath}"))
        assertTrue(result.contains("com.neusoft.geely.btphone.nf SUCCESS"))
        assertTrue(result.contains("com.nforetek.bt FAIL NameNotFoundException"))
        verify(context, never()).bindService(any(), any(), anyInt())
        verify(context, never()).startService(any())
        verify(context, never()).getSystemService(anyString())
    }

    @Test fun emptySourceAndUnavailableDestinationFailWithoutPartialFiles() {
        val source = folder.newFile("empty.apk")
        val directory = folder.newFolder("output")
        try {
            VendorBluetoothApkExport.copyVerified(source, File(directory, "empty.apk"))
            fail("Empty APK must fail")
        } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("empty")) }
        assertTrue(directory.list()!!.isEmpty())
        source.writeText("bytes")
        try {
            VendorBluetoothApkExport.copyVerified(source, source)
            fail("Source overwrite must fail")
        } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("differ")) }
        assertEquals("bytes", source.readText())
    }

    @Test fun publicDownloadFailureFallsBackAndRecordsReason() {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        val source = folder.newFile("source.apk").apply { writeText("bytes") }
        val root = folder.newFolder("public-root")
        File(root, "Download").writeText("not a directory")
        org.robolectric.shadows.ShadowEnvironment.setExternalStoragePublicDirectory(root.toPath())
        `when`(context.packageManager).thenReturn(pm)
        `when`(context.packageName).thenReturn("test.app")
        `when`(context.getExternalFilesDir(null)).thenReturn(folder.newFolder("fallback"))
        `when`(pm.checkPermission(anyString(), anyString())).thenReturn(PackageManager.PERMISSION_GRANTED)
        for (name in listOf("com.neusoft.geely.btphone.nf", "com.nforetek.bt")) {
            `when`(pm.getApplicationInfo(name, 0)).thenReturn(ApplicationInfo().apply { sourceDir = source.absolutePath })
        }
        val result = VendorBluetoothApkExport.export(context)
        assertTrue(result.contains("Public destination FAIL"))
        assertTrue(result.contains("btphone.nf SUCCESS"))
        assertTrue(result.contains("com.nforetek.bt SUCCESS"))
        assertTrue(result.contains("fallback"))
    }

    @Test fun publicDownloadSuccessUsesRequestedFileNames() {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        val source = folder.newFile("source.apk").apply { writeText("bytes") }
        val root = folder.newFolder("public-root")
        org.robolectric.shadows.ShadowEnvironment.setExternalStoragePublicDirectory(root.toPath())
        `when`(context.packageManager).thenReturn(pm)
        `when`(context.packageName).thenReturn("test.app")
        `when`(pm.checkPermission(anyString(), anyString())).thenReturn(PackageManager.PERMISSION_GRANTED)
        for (name in listOf("com.neusoft.geely.btphone.nf", "com.nforetek.bt")) {
            `when`(pm.getApplicationInfo(name, 0)).thenReturn(ApplicationInfo().apply { sourceDir = source.absolutePath })
        }
        val result = VendorBluetoothApkExport.export(context)
        assertTrue(result.contains("btphone.nf SUCCESS"))
        assertTrue(result.contains("com.nforetek.bt SUCCESS"))
        assertTrue(File(root, "Download/DiPlayVendorDump/btphoneNF.apk").isFile)
        assertTrue(File(root, "Download/DiPlayVendorDump/Bluetooth-GocBtAPI.apk").isFile)
        verify(context, never()).getExternalFilesDir(any())
    }

    @Test fun controlServiceInDifferentlyNamedPackageExportsActualSourceAndSplits() {
        val (context, pm) = controlContext()
        val base = folder.newFile("vehicle-base.apk").apply { writeText("base bytes") }
        val split = folder.newFile("control-split.apk").apply { writeText("control implementation") }
        val info = installedPackage("com.actual.vehicle.settings", base).apply {
            applicationInfo!!.splitSourceDirs = arrayOf(split.absolutePath)
            services = arrayOf(ServiceInfo().apply {
                packageName = "com.actual.vehicle.settings"
                name = VendorBluetoothApkExport.CONTROL_SERVICE
            })
        }
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(info))
        `when`(pm.getApplicationInfo(info.packageName, 0)).thenReturn(info.applicationInfo)
        val result = VendorBluetoothApkExport.exportControlImplementation(context)
        assertTrue(result.contains("MATCH package=com.actual.vehicle.settings"))
        assertTrue(result.contains("service=${VendorBluetoothApkExport.CONTROL_SERVICE}"))
        assertTrue(result.contains("base source=${base.absolutePath}"))
        assertTrue(result.contains("split-1 source=${split.absolutePath}"))
        assertTrue(result.contains("base SUCCESS"))
        assertTrue(result.contains("split-1 SUCCESS"))
        val root = File(context.getExternalFilesDir(null), "DiPlayVendorDump")
        assertArrayEquals(base.readBytes(), File(root, "${info.packageName}-base.apk").readBytes())
        assertArrayEquals(split.readBytes(), File(root, "${info.packageName}-split-1.apk").readBytes())
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(base.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertTrue(result.contains("size=${base.length()} SHA-256=$expected"))
        assertControlMetadataOnly(context)
    }

    @Test fun exactActionFindsOwnerEvenWhenServiceClassUsesAnotherNamespace() {
        val (context, pm) = controlContext()
        val source = folder.newFile("action-owner.apk").apply { writeText("action owner") }
        val info = installedPackage("com.actual.control", source)
        `when`(pm.queryIntentServices(any(), anyInt())).thenReturn(listOf(ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = info.packageName
                name = "com.actual.control.Controller"
                exported = false
                enabled = false
                permission = "vendor.permission.CONTROL"
            }
        }))
        `when`(pm.getApplicationInfo(info.packageName, 0)).thenReturn(info.applicationInfo)
        val result = VendorBluetoothApkExport.exportControlImplementation(context)
        assertTrue(result.contains("MATCH package=com.actual.control"))
        assertTrue(result.contains("service=com.actual.control.Controller action=${VendorBluetoothApkExport.CONTROL_SERVICE}"))
        assertTrue(result.contains("enabled=false exported=false permission=vendor.permission.CONTROL"))
        assertTrue(result.contains("base SUCCESS"))
        verify(pm).queryIntentServices(argThat { it.action == VendorBluetoothApkExport.CONTROL_SERVICE }, anyInt())
        assertControlMetadataOnly(context)
    }

    @Test fun exactNamespaceNormalizesRelativeComponentWithoutMatchingSimilarPrefix() {
        val (context, pm) = controlContext()
        val source = folder.newFile("namespace.apk").apply { writeText("namespace") }
        val info = installedPackage(VendorBluetoothApkExport.CONTROL_NAMESPACE, source).apply {
            services = arrayOf(ServiceInfo().apply { name = ".service.BluetoothControlService" })
        }
        val lookalike = installedPackage("${VendorBluetoothApkExport.CONTROL_NAMESPACE}Extra", source)
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(info, lookalike))
        `when`(pm.getApplicationInfo(info.packageName, 0)).thenReturn(info.applicationInfo)
        val result = VendorBluetoothApkExport.exportControlImplementation(context)
        assertTrue(result.contains("Exact owning packages=1"))
        assertTrue(result.contains("service=${VendorBluetoothApkExport.CONTROL_SERVICE}"))
        verify(pm, never()).getApplicationInfo(lookalike.packageName, 0)
        assertControlMetadataOnly(context)
    }

    @Test fun missingExactMatchReportsCandidatesAndDoesNotCopyThem() {
        val (context, pm) = controlContext()
        val source = folder.newFile("candidate.apk").apply { writeText("must not copy") }
        val packages = listOf(
            installedPackage("com.neusoft.settings", source),
            installedPackage("com.unrelated.host", source).apply {
                services = arrayOf(ServiceInfo().apply { name = "vendor.vehicle.BluetoothController" })
            },
            installedPackage("com.ordinary.app", source),
        )
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(packages)
        for (info in packages) `when`(pm.getApplicationInfo(info.packageName, 0)).thenReturn(info.applicationInfo)
        val result = VendorBluetoothApkExport.exportControlImplementation(context)
        assertTrue(result.contains("No exact service/namespace/action metadata match"))
        assertTrue(result.contains("Candidate packages=2"))
        assertTrue(result.contains("CANDIDATE package=com.unrelated.host"))
        assertTrue(result.contains("service=vendor.vehicle.BluetoothController"))
        assertTrue(result.contains("source=${source.absolutePath}"))
        assertFalse(result.contains("SUCCESS"))
        assertFalse(File(context.getExternalFilesDir(null), "DiPlayVendorDump").exists())
        assertEquals("must not copy", source.readText())
        assertControlMetadataOnly(context)
    }

    @Test fun enumerationAndActionFailuresAreVisibleAndContained() {
        val (context, pm) = controlContext()
        `when`(pm.getInstalledPackages(anyInt())).thenThrow(SecurityException("inventory restricted"))
        `when`(pm.queryIntentServices(any(), anyInt())).thenThrow(IllegalStateException("vendor PM failure"))
        val result = VendorBluetoothApkExport.exportControlImplementation(context)
        assertTrue(result.contains("Installed package enumeration FAIL SecurityException: inventory restricted"))
        assertTrue(result.contains("Exact service-action query FAIL IllegalStateException: vendor PM failure"))
        assertTrue(result.contains("(inventory failed)"))
        assertTrue(result.contains("STOP after collecting"))
        assertControlMetadataOnly(context)
    }

    @Test fun actionLinkageErrorDoesNotPreventExactManifestExport() {
        val (context, pm) = controlContext()
        val source = folder.newFile("exact.apk").apply { writeText("exact") }
        val info = installedPackage(VendorBluetoothApkExport.CONTROL_NAMESPACE, source)
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(info))
        `when`(pm.getApplicationInfo(info.packageName, 0)).thenReturn(info.applicationInfo)
        `when`(pm.queryIntentServices(any(), anyInt())).thenThrow(NoClassDefFoundError("vendor framework"))
        val result = VendorBluetoothApkExport.exportControlImplementation(context)
        assertTrue(result.contains("Exact service-action query FAIL NoClassDefFoundError"))
        assertTrue(result.contains("base SUCCESS"))
        assertControlMetadataOnly(context)
    }

    @Test fun failedSplitDoesNotHideSuccessfulBaseOrCreatePartialFiles() {
        val (context, pm) = controlContext()
        val base = folder.newFile("base.apk").apply { writeText("base") }
        val info = installedPackage(VendorBluetoothApkExport.CONTROL_NAMESPACE, base).apply {
            applicationInfo!!.splitSourceDirs = arrayOf(File(folder.root, "missing-split.apk").absolutePath)
        }
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(info))
        `when`(pm.getApplicationInfo(info.packageName, 0)).thenReturn(info.applicationInfo)
        val result = VendorBluetoothApkExport.exportControlImplementation(context)
        assertTrue(result.contains("base SUCCESS"))
        assertTrue(result.contains("split-1 export FAIL IllegalStateException: Installed APK unreadable"))
        assertEquals(listOf("${info.packageName}-base.apk"),
            File(context.getExternalFilesDir(null), "DiPlayVendorDump").list()!!.toList())
        assertControlMetadataOnly(context)
    }

    @Test fun applicationAndReceiverNamespacesAreInspectedIncludingDisabledComponents() {
        val (context, pm) = controlContext()
        val source = folder.newFile("manifest-owner.apk").apply { writeText("manifest owner") }
        val info = installedPackage("com.stock.owner", source).apply {
            applicationInfo!!.className = "${VendorBluetoothApkExport.CONTROL_NAMESPACE}.StockApplication"
            receivers = arrayOf(android.content.pm.ActivityInfo().apply {
                name = "${VendorBluetoothApkExport.CONTROL_NAMESPACE}.Receiver"
                enabled = false
            })
        }
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(info))
        `when`(pm.getApplicationInfo(info.packageName, 0)).thenReturn(info.applicationInfo)
        val result = VendorBluetoothApkExport.exportControlImplementation(context)
        assertTrue(result.contains("application=${info.applicationInfo!!.className}"))
        assertTrue(result.contains("receiver=${info.receivers!![0].name}"))
        assertTrue(result.contains("Exact owning packages=1"))
        assertTrue(result.contains("base SUCCESS"))
        verify(pm).getInstalledPackages(PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS or PackageManager.GET_DISABLED_COMPONENTS)
        assertControlMetadataOnly(context)
    }

    @Test fun exactOwnerDisappearingBeforeCopyReportsFailureWithoutFallbackGuess() {
        val (context, pm) = controlContext()
        val source = folder.newFile("disappearing.apk").apply { writeText("installed metadata") }
        val info = installedPackage(VendorBluetoothApkExport.CONTROL_NAMESPACE, source)
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(info))
        `when`(pm.getApplicationInfo(info.packageName, 0)).thenThrow(PackageManager.NameNotFoundException("removed"))
        val result = VendorBluetoothApkExport.exportControlImplementation(context)
        assertTrue(result.contains("Exact package ${info.packageName} FAIL NameNotFoundException: removed"))
        assertFalse(result.contains("SUCCESS"))
        assertFalse(File(context.getExternalFilesDir(null), "DiPlayVendorDump").exists())
        assertEquals("installed metadata", source.readText())
        assertControlMetadataOnly(context)
    }

    private fun controlContext(): Pair<Context, PackageManager> {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(pm)
        `when`(context.packageName).thenReturn("test.app")
        `when`(context.getExternalFilesDir(null)).thenReturn(folder.newFolder("control-external"))
        `when`(pm.checkPermission(anyString(), anyString())).thenReturn(PackageManager.PERMISSION_DENIED)
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(emptyList())
        `when`(pm.queryIntentServices(any(), anyInt())).thenReturn(emptyList())
        return context to pm
    }

    private fun installedPackage(packageName: String, source: File) = PackageInfo().apply {
        this.packageName = packageName
        applicationInfo = ApplicationInfo().apply { sourceDir = source.absolutePath }
    }

    private fun assertControlMetadataOnly(context: Context) {
        verify(context, never()).bindService(any(), any(), anyInt())
        verify(context, never()).startService(any())
        verify(context, never()).stopService(any())
        verify(context, never()).sendBroadcast(any())
        verify(context, never()).registerReceiver(any(), any())
        verify(context, never()).getSystemService(anyString())
    }
}
