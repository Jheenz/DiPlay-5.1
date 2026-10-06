package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import java.io.File
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], manifest = Config.NONE)
class AppleImplementationDiscoveryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun broadPackageCandidatesCopyBaseSplitsAndRecordHashesWithoutKnownAppleNames() {
        val (context, pm) = environment()
        val base = source("device.apk")
        val split = source("feature.apk")
        val info = pkg("com.ecarx.mirror", base).apply {
            applicationInfo!!.splitSourceDirs = arrayOf(split.path)
            services = arrayOf(ServiceInfo().apply { name = "vendor.UsbProjection"; exported = true })
        }
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(info))
        val result = AppleImplementationDiscovery.collect(context, emptyList())
        assertTrue(result.contains("CANDIDATE package=com.ecarx.mirror"))
        assertTrue(result.contains("service=vendor.UsbProjection exported=true"))
        assertTrue(result.contains("versionName=firmware versionCode=7"))
        val files = output(context).listFiles()!!
        assertEquals(2, files.size)
        assertArrayEquals(base.readBytes(), files.single { it.name.endsWith("-base.apk") }.readBytes())
        files.forEach { file ->
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertTrue(result.contains("path=${file.path} size=${file.length()} SHA-256=$hash"))
        }
        assertSafe(context, pm)
    }

    @Test fun nonmatchingPackageCanMatchApkFilenameOrDisabledComponent() {
        val (context, pm) = environment()
        val filename = pkg("vendor.a", source("iPhoneAdapter.apk"))
        val component = pkg("vendor.b", source("neutral.apk")).apply {
            services = arrayOf(ServiceInfo().apply { name = ".AccessoryPhone"; enabled = false })
        }
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(filename, component))
        val result = AppleImplementationDiscovery.collect(context, emptyList())
        assertTrue(result.contains("Candidate installed packages=2"))
        assertTrue(result.contains("APK filename=iPhoneAdapter.apk"))
        assertTrue(result.contains("enabled=false"))
        assertSafe(context, pm)
    }

    @Test fun actionOnlySystemPackageIsFoundThroughReadOnlyManifestParser() {
        val (context, pm) = environment()
        val info = pkg("vendor.hidden", source("system.apk")).apply {
            applicationInfo!!.flags = ApplicationInfo.FLAG_SYSTEM
        }
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(info))
        val resources = mock(android.content.res.Resources::class.java)
        val assets = mock(android.content.res.AssetManager::class.java)
        val parser = mock(android.content.res.XmlResourceParser::class.java)
        val delegate = xml("""<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><service android:name=".Controller"><intent-filter><action android:name="vendor.IAP_LINK"/></intent-filter></service></application></manifest>""")
        `when`(pm.getResourcesForApplication(info.applicationInfo!!)).thenReturn(resources)
        `when`(resources.assets).thenReturn(assets)
        `when`(assets.openXmlResourceParser("AndroidManifest.xml")).thenReturn(parser)
        `when`(parser.eventType).thenAnswer { delegate.eventType }
        `when`(parser.name).thenAnswer { delegate.name }
        `when`(parser.next()).thenAnswer { delegate.next() }
        `when`(parser.getAttributeValue(anyString(), anyString())).thenAnswer {
            delegate.getAttributeValue(it.getArgument(0), it.getArgument(1))
        }
        val result = AppleImplementationDiscovery.collect(context, emptyList())
        assertTrue(result.contains("CANDIDATE package=vendor.hidden"))
        assertTrue(result.contains("system=true"))
        assertTrue(result.contains("manifest service=.Controller action=vendor.IAP_LINK"))
        verify(parser).close()
        assertSafe(context, pm)
    }

    @Test fun readableArchiveComponentCanIdentifyDifferentlyNamedImplementation() {
        val (context, pm) = environment()
        val base = source("ordinary.apk")
        val installed = pkg("vendor.device", base)
        val archive = pkg("vendor.device", base).apply {
            services = arrayOf(ServiceInfo().apply { name = "internal.CarPlayProvider" })
        }
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(installed))
        `when`(pm.getPackageArchiveInfo(eq(base.canonicalPath), anyInt())).thenReturn(archive)
        val result = AppleImplementationDiscovery.collect(context, emptyList())
        assertTrue(result.contains("APK source=${base.canonicalPath} service=internal.CarPlayProvider"))
        verify(pm).getPackageArchiveInfo(eq(base.canonicalPath), anyInt())
        assertEquals(1, output(context).list()!!.size)
    }

    @Test fun libraryFilenameInventoryCopiesOnlyReadableMatchingFilesWithoutRecursionOrParsing() {
        val (context, pm) = environment()
        val libs = folder.newFolder("native32")
        val match = File(libs, "libUsbIPhone.so").apply { writeText("ordinary bytes not ELF") }
        File(libs, "libunrelated.so").writeText("other")
        File(libs, "AppleSubdirectory").mkdir()
        File(libs, "AppleSubdirectory/nested.so").writeText("never read")
        val info = pkg("vendor.neutral", source("base.apk")).apply {
            applicationInfo!!.nativeLibraryDir = libs.path
        }
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(info))
        val result = AppleImplementationDiscovery.collect(context, listOf(libs.path, libs.path))
        assertTrue(result.contains("filenames=3"))
        assertTrue(result.contains("filename=libUsbIPhone.so"))
        assertTrue(result.contains("AppleSubdirectory") && result.contains("Not an ordinary readable file"))
        assertFalse(result.contains("nested.so"))
        val files = output(context).listFiles()!!
        assertEquals(1, files.size)
        assertArrayEquals(match.readBytes(), files.single().readBytes())
        assertTrue(result.contains("package=vendor.neutral nativeLibraryDir"))
        assertSafe(context, pm)
    }

    @Test fun allRequestedNativeDirectoriesInclude32And64BitLocations() {
        assertEquals(listOf("/system/lib", "/system/lib64", "/vendor/lib", "/vendor/lib64",
            "/system/vendor/lib", "/system/vendor/lib64"), AppleImplementationDiscovery.systemLibraryDirectories)
    }

    @Test fun everyRequestedPackageAndNativeFilenameTermIsCoveredCaseInsensitively() {
        val (context, pm) = environment()
        val base = source("ordinary.apk")
        val packageTerms = listOf("apple", "carplay", "iap", "iphone", "ipod", "usb", "ncm",
            "projection", "phone", "link", "mirror", "ecarx", "neusoft")
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(packageTerms.map {
            pkg("vendor.${it.uppercase(java.util.Locale.ROOT)}", base)
        })
        val libs = folder.newFolder("terms")
        val nativeTerms = listOf("apple", "carplay", "iap", "mfi", "usb", "ncm", "accessory", "projection")
        nativeTerms.forEach { File(libs, "lib${it.uppercase(java.util.Locale.ROOT)}.so").writeText("bytes") }
        val report = AppleImplementationDiscovery.collect(context, listOf(libs.path))
        packageTerms.forEach { assertTrue(report.contains("CANDIDATE package=vendor.${it.uppercase(java.util.Locale.ROOT)}")) }
        nativeTerms.forEach { assertTrue(report.contains("filename=lib${it.uppercase(java.util.Locale.ROOT)}.so")) }
        assertTrue(report.contains("Candidate installed packages=13"))
        assertTrue(report.contains("Exported ordinary source files=9"))
        assertSafe(context, pm)
    }

    @Test fun missingAndDeviceDirectoriesFailButOtherDirectoriesStillExport() {
        val (context, pm) = environment()
        val libs = folder.newFolder("available")
        File(libs, "renamed_mfi.bin").writeText("bytes")
        val result = AppleImplementationDiscovery.collect(context,
            listOf(File(folder.root, "missing").path, "/dev", "/proc", libs.path))
        assertTrue(result.contains("Directory unavailable/unreadable"))
        assertTrue(result.contains("FAIL"))
        assertEquals(1, output(context).list()!!.size)
        assertSafe(context, pm)
    }

    @Test fun packageEnumerationFailuresDoNotPreventNativeInventory() {
        for (error in listOf(SecurityException("denied"), RuntimeException("vendor"), NoClassDefFoundError("vendor"))) {
            val (context, pm) = environment()
            val libs = folder.newFolder()
            File(libs, "libProjection.so").writeText("bytes")
            `when`(pm.getInstalledPackages(anyInt())).thenThrow(error)
            val result = AppleImplementationDiscovery.collect(context, listOf(libs.path))
            assertTrue(result.contains("Installed package enumeration FAIL ${error.javaClass.simpleName}"))
            assertTrue(result.contains("Exported ordinary source files=1"))
            assertSafe(context, pm)
        }
    }

    @Test fun partialPackageCopiesAndMetadataFailuresAreReportedNotHidden() {
        val (context, pm) = environment()
        val info = pkg("vendor.neusoft", source("base.apk")).apply {
            applicationInfo!!.splitSourceDirs = arrayOf(File(folder.root, "missing.apk").path)
        }
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(info))
        `when`(pm.getResourcesForApplication(any(ApplicationInfo::class.java))).thenThrow(SecurityException("manifest"))
        `when`(pm.getPackageArchiveInfo(anyString(), anyInt())).thenThrow(RuntimeException("archive"))
        val result = AppleImplementationDiscovery.collect(context, emptyList())
        assertTrue(result.contains("Manifest metadata vendor.neusoft FAIL SecurityException"))
        assertTrue(result.contains("Readable APK metadata vendor.neusoft") && result.contains("FAIL RuntimeException"))
        assertTrue(result.contains("base SUCCESS"))
        assertTrue(result.contains("split-1 FAIL"))
        assertTrue(result.contains("STOP after inventory/export"))
        assertSafe(context, pm)
    }

    @Test fun noCandidatesDoNotCreateExportDirectoryOrClaimAbsenceOfStack() {
        val (context, pm) = environment()
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(pkg("vendor.neutral", source("base.apk"))))
        val result = AppleImplementationDiscovery.collect(context, emptyList())
        assertTrue(result.contains("Candidate installed packages=0"))
        assertTrue(result.contains("Exported ordinary source files=0"))
        assertTrue(result.contains("FAIL no PackageManager archive metadata"))
        assertFalse(output(context).exists())
        assertSafe(context, pm)
    }

    @Test fun forbiddenApkSourceIsRejectedBeforeResourcesOrArchiveAccess() {
        val (context, pm) = environment()
        val info = pkg("vendor.apple", File("/dev/goc_serial"))
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(listOf(info))
        val report = AppleImplementationDiscovery.collect(context, emptyList())
        assertTrue(report.contains("Device/kernel path forbidden"))
        assertTrue(report.contains("Exported ordinary source files=0"))
        verify(pm, never()).getResourcesForApplication(any(ApplicationInfo::class.java))
        verify(pm, never()).getPackageArchiveInfo(anyString(), anyInt())
        assertSafe(context, pm)
    }

    @Test fun manifestParserTracksAliasesProvidersAndActionOnlyMatches() {
        val matches = AppleImplementationDiscovery.manifestMatches(xml("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>
            <activity-alias android:name=".Mirror"><intent-filter><action android:name="neutral.ACTION"/></intent-filter></activity-alias>
            <provider android:name="internal.NcmProvider"/>
            <receiver android:name=".R"><intent-filter><action android:name="vendor.IPOD"/></intent-filter></receiver>
            <service android:name=".Ordinary"><intent-filter><action android:name="neutral.ACTION"/></intent-filter></service>
            </application></manifest>
        """.trimIndent()))
        assertTrue(matches.any { it.contains("activity-alias=.Mirror action=neutral.ACTION") })
        assertTrue(matches.any { it.contains("provider=internal.NcmProvider") })
        assertTrue(matches.any { it.contains("receiver=.R action=vendor.IPOD") })
        assertFalse(matches.any { it.contains(".Ordinary") })
    }

    @Test fun nonTerminatingVendorManifestParserFailsWithExplicitLimit() {
        val parser = mock(XmlPullParser::class.java)
        `when`(parser.eventType).thenReturn(XmlPullParser.START_TAG)
        `when`(parser.name).thenReturn("neutral")
        try {
            AppleImplementationDiscovery.manifestMatches(parser)
            fail("Parser limit must fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("100000 events"))
        }
    }

    private fun xml(value: String): XmlPullParser = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = true
    }.newPullParser().apply { setInput(StringReader(value)) }

    private fun environment(): Pair<Context, PackageManager> {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(pm)
        `when`(context.packageName).thenReturn("test.app")
        `when`(context.getExternalFilesDir(null)).thenReturn(folder.newFolder())
        `when`(pm.checkPermission(anyString(), anyString())).thenReturn(PackageManager.PERMISSION_DENIED)
        `when`(pm.getInstalledPackages(anyInt())).thenReturn(emptyList())
        return context to pm
    }

    private fun source(name: String): File = folder.newFile(name).apply { writeText("fixture APK bytes") }

    private fun pkg(name: String, source: File): PackageInfo = PackageInfo().apply {
        packageName = name
        versionName = "firmware"
        @Suppress("DEPRECATION")
        versionCode = 7
        applicationInfo = ApplicationInfo().apply { packageName = name; sourceDir = source.path }
    }

    private fun output(context: Context) = File(context.getExternalFilesDir(null), "DiPlayVendorDump")

    private fun assertSafe(context: Context, pm: PackageManager) {
        verify(context, never()).bindService(any(), any(), anyInt())
        verify(context, never()).startService(any())
        verify(context, never()).stopService(any())
        verify(context, never()).sendBroadcast(any())
        verify(context, never()).registerReceiver(any(), any())
        verify(context, never()).getSystemService(anyString())
        verify(pm, never()).queryIntentServices(any(), anyInt())
    }
}
