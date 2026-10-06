package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
class AppleStackExportTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun copiesBaseSplitsRootsDirectDependenciesAndConfigWithoutInvokingServices() {
        val (context, pm) = environment()
        val directory = folder.newFolder("native")
        val config = File(directory, "apple.conf").apply { writeText("secret=value") }
        val core = File(directory, "libAppleCore_jni.so").apply {
            writeBytes(elf(needed = "libdirect.so", config = "apple.conf"))
        }
        File(directory, "libApplePrivate_jni.so").writeBytes(elf())
        File(directory, "libdirect.so").writeBytes(elf(needed = "libtransitive.so"))
        File(directory, "libtransitive.so").writeBytes(elf())
        val base = archive("base.apk")
        val split = archive("split.apk")
        installed(pm, base, directory, split)
        val result = AppleStackExport.export(context, "")
        assertTrue(result.contains("versionName=vehicle-1 versionCode=42"))
        assertTrue(result.contains("nativeLibraryDir=$directory"))
        assertTrue(result.contains("splitSourceDirs=$split"))
        assertTrue(result.contains("service=com.neusoft.appleservice.AppleService exported=true"))
        assertTrue(result.contains("provider=com.neusoft.appleservice.AppleProvider"))
        assertTrue(result.contains("base SUCCESS"))
        assertTrue(result.contains("split-1 SUCCESS"))
        assertTrue(result.contains("architecture=ARM32 DT_NEEDED=libdirect.so"))
        val outputs = output(context).listFiles()!!.toList()
        assertEquals(6, outputs.size)
        assertFalse(outputs.any { it.name.contains("transitive") })
        assertArrayEquals(core.readBytes(), outputs.single { it.name.endsWith("libAppleCore_jni.so") }.readBytes())
        assertArrayEquals(config.readBytes(), outputs.single { it.name.endsWith("apple.conf") }.readBytes())
        assertFalse(result.contains("secret=value"))
        for (file in outputs) {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertTrue(result.contains("path=${file.absolutePath} size=${file.length()} SHA-256=$hash"))
        }
        assertSafe(context)
    }

    @Test fun missingPackageReportsFailureAndCanStillCollectMetadataLocatedLibraries() {
        val (context, pm) = environment()
        `when`(pm.getPackageInfo(eq(AppleStackExport.PACKAGE), anyInt()))
            .thenThrow(PackageManager.NameNotFoundException("not installed"))
        val directory = folder.newFolder("public-library-path")
        File(directory, "libAppleCore_jni.so").writeBytes(elf())
        val result = AppleStackExport.export(context, directory.path)
        assertTrue(result.contains("Installed package com.neusoft.appleservice FAIL NameNotFoundException"))
        assertTrue(result.contains("origin=java.library.path"))
        assertTrue(result.contains("Root library libAppleCore_jni.so SUCCESS"))
        assertTrue(result.contains("Root library libApplePrivate_jni.so FAIL not found"))
        assertSafe(context)
    }

    @Test fun publicManifestParserReportsActionsWithoutResolvingOrSendingAnIntent() {
        val (context, pm) = environment()
        installed(pm, archive("base.apk"), null)
        val resources = mock(android.content.res.Resources::class.java)
        val assets = mock(android.content.res.AssetManager::class.java)
        val parser = mock(android.content.res.XmlResourceParser::class.java)
        `when`(pm.getResourcesForApplication(any(ApplicationInfo::class.java))).thenReturn(resources)
        `when`(resources.assets).thenReturn(assets)
        `when`(assets.openXmlResourceParser("AndroidManifest.xml")).thenReturn(parser)
        val start = org.xmlpull.v1.XmlPullParser.START_TAG
        val end = org.xmlpull.v1.XmlPullParser.END_TAG
        val events = listOf(start, start, start, end, end, end, org.xmlpull.v1.XmlPullParser.END_DOCUMENT)
        val names = listOf("service", "intent-filter", "action", "action", "intent-filter", "service", "")
        var index = 0
        `when`(parser.eventType).thenAnswer { events[index] }
        `when`(parser.name).thenAnswer { names[index] }
        `when`(parser.next()).thenAnswer { index++; events[index] }
        `when`(parser.getAttributeValue(anyString(), eq("name"))).thenAnswer {
            if (names[index] == "service") ".AppleService" else "com.neusoft.apple.USB_AUTHENTICATION"
        }
        val result = AppleStackExport.export(context, "")
        assertTrue(result.contains("Manifest component=.AppleService action=com.neusoft.apple.USB_AUTHENTICATION"))
        verify(parser).close()
        verify(pm, never()).queryIntentServices(any(), anyInt())
        assertSafe(context)
    }

    @Test fun packageApiExceptionsNeverEscape() {
        for (error in listOf(SecurityException("denied"), RuntimeException("vendor"), NoClassDefFoundError("vendor"))) {
            val (context, pm) = environment()
            `when`(pm.getPackageInfo(eq(AppleStackExport.PACKAGE), anyInt())).thenThrow(error)
            val report = AppleStackExport.export(context, "")
            assertTrue(report.contains("FAIL ${error.javaClass.simpleName}"))
            assertTrue(report.contains("STOP after collection"))
            assertSafe(context)
        }
    }

    @Test fun incompleteSplitAndInvalidElfRemainExplicitFailures() {
        val (context, pm) = environment()
        val directory = folder.newFolder("invalid-native")
        File(directory, "libAppleCore_jni.so").writeText("not ELF")
        val base = archive("base.apk")
        installed(pm, base, directory, File(folder.root, "missing-split.apk"))
        val result = AppleStackExport.export(context, "")
        assertTrue(result.contains("base SUCCESS"))
        assertTrue(result.contains("Copy com.neusoft.appleservice split-1 FAIL"))
        assertTrue(result.contains("Static ELF metadata"))
        assertTrue(result.contains("FAIL"))
        assertTrue(result.contains("not proof of a complete native stack"))
        assertSafe(context)
    }

    @Test fun packagedNativeFilesAreExtractedVerifiedAndTemporaryCopiesRemoved() {
        val (context, pm) = environment()
        val base = archive("packed.apk", mapOf(
            "lib/armeabi-v7a/libAppleCore_jni.so" to elf(needed = "libdirect.so"),
            "lib/armeabi-v7a/libApplePrivate_jni.so" to elf(),
            "lib/armeabi-v7a/libdirect.so" to elf(),
            "lib/arm64-v8a/libdirect.so" to elf(wide = true),
            "lib/../../libAppleCore_jni.so" to elf(),
        ))
        installed(pm, base, null)
        val result = AppleStackExport.export(context, "")
        val files = output(context).listFiles()!!.toList()
        assertEquals(4, files.size)
        assertTrue(result.contains("$base!/lib/armeabi-v7a/libAppleCore_jni.so SUCCESS"))
        assertFalse(files.any { it.name.contains("arm64") })
        assertTrue(context.cacheDir.list()!!.isEmpty())
        assertSafe(context)
    }

    @Test fun dependencyAbiMismatchIsNotCollected() {
        val (context, pm) = environment()
        val directory = folder.newFolder("wrong-abi")
        File(directory, "libAppleCore_jni.so").writeBytes(elf(needed = "libwrong.so"))
        File(directory, "libwrong.so").writeBytes(elf(wide = true))
        installed(pm, archive("base.apk"), directory)
        val result = AppleStackExport.export(context, "")
        assertTrue(result.contains("FAIL architecture mismatch"))
        assertFalse(output(context).list()!!.any { it.endsWith("libwrong.so") })
    }

    @Test fun exactDependencyPathInMetadataDirectoryIsCollectedAndRelativeSearchPathsAreRejected() {
        val (context, pm) = environment()
        val directory = folder.newFolder("exact-native")
        val dependency = File(directory, "libexact.so").apply { writeBytes(elf()) }
        File(directory, "libAppleCore_jni.so").writeBytes(elf(needed = dependency.path))
        installed(pm, archive("base.apk"), directory)
        val result = AppleStackExport.export(context, ".")
        assertTrue(result.contains("libexact.so SUCCESS"))
        assertTrue(result.contains("Relative library directory"))
        assertArrayEquals(dependency.readBytes(), output(context).listFiles()!!.single {
            it.name.endsWith("libexact.so")
        }.readBytes())
        assertSafe(context)
    }

    @Test fun unsafeDependencyAndUnresolvedConfigAreReportedWithoutCopying() {
        val (context, pm) = environment()
        val directory = folder.newFolder("references")
        File(directory, "libAppleCore_jni.so").writeBytes(elf(needed = "../escape.so", config = "missing.conf"))
        installed(pm, archive("base.apk"), directory)
        val result = AppleStackExport.export(context, "")
        assertTrue(result.contains("Unsafe/non-library DT_NEEDED"))
        assertTrue(result.contains("Config candidate missing.conf FAIL unresolved/unreadable"))
        assertEquals(2, output(context).list()!!.size)
        assertSafe(context)
    }

    @Test fun deviceAndKernelDirectoriesAreRejectedWithoutEnumeration() {
        for (path in listOf("/dev/goc_serial", "/proc/self/mem", "/sys/usb")) {
            try {
                AppleStackExport.safeFile(File(path))
                fail("Forbidden path accepted: $path")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("forbidden"))
            }
        }
        val (context, pm) = environment()
        installed(pm, archive("base.apk"), File("/dev"))
        val report = AppleStackExport.export(context, "/proc")
        assertTrue(report.contains("Device/kernel path forbidden"))
        assertSafe(context)
    }

    @Test fun elf32And64BothByteOrdersResolveGenuineNeededMapping() {
        for (wide in listOf(false, true)) for (little in listOf(false, true)) {
            val file = folder.newFile("elf-$wide-$little.so").apply {
                writeBytes(elf(wide, little, "libneeded.so", "settings/apple.cfg"))
            }
            val result = NativeCollectionMetadata.inspect(file)
            assertEquals(if (wide) "AArch64" else "ARM32", result.architecture)
            assertEquals(listOf("libneeded.so"), result.needed)
            assertEquals(listOf("settings/apple.cfg"), result.configCandidates)
        }
    }

    @Test fun malformedElfOffsetsAndUnterminatedDependenciesAreRejected() {
        val invalid = elf(needed = "libx.so")
        ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(28, Int.MAX_VALUE)
        val malformed = folder.newFile("malformed.so").apply { writeBytes(invalid) }
        try { NativeCollectionMetadata.inspect(malformed); fail("Bad program header accepted") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("outside")) }
        val unterminated = elf(needed = "libx.so")
        unterminated[0x180 + 1 + "libx.so".length] = 65
        malformed.writeBytes(unterminated)
        try { NativeCollectionMetadata.inspect(malformed); fail("Unterminated name accepted") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("Unterminated")) }
    }

    @Test fun configReferencesRejectTraversalUrlsAndDevicePathsAreNeverRead() {
        assertFalse(NativeCollectionMetadata.isConfigReference("../private.cfg"))
        assertFalse(NativeCollectionMetadata.isConfigReference("https://host/settings.json"))
        assertFalse(NativeCollectionMetadata.isConfigReference("secret=value"))
        val (context, pm) = environment()
        val directory = folder.newFolder("device-reference")
        File(directory, "libAppleCore_jni.so").writeBytes(elf(config = "/dev/secret.conf"))
        installed(pm, archive("base.apk"), directory)
        val result = AppleStackExport.export(context, "")
        assertTrue(result.contains("Config candidate /dev/secret.conf FAIL"))
        assertEquals(2, output(context).list()!!.size)
    }

    private fun environment(): Pair<Context, PackageManager> {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(pm)
        `when`(context.packageName).thenReturn("test.app")
        `when`(context.cacheDir).thenReturn(folder.newFolder())
        `when`(context.getExternalFilesDir(null)).thenReturn(folder.newFolder())
        `when`(pm.checkPermission(anyString(), anyString())).thenReturn(PackageManager.PERMISSION_DENIED)
        return context to pm
    }

    private fun installed(pm: PackageManager, base: File, directory: File?, split: File? = null) {
        val info = PackageInfo().apply {
            packageName = AppleStackExport.PACKAGE
            versionName = "vehicle-1"
            @Suppress("DEPRECATION")
            versionCode = 42
            applicationInfo = ApplicationInfo().apply {
                packageName = AppleStackExport.PACKAGE
                sourceDir = base.path
                splitSourceDirs = split?.let { arrayOf(it.path) }
                nativeLibraryDir = directory?.path
            }
            services = arrayOf(ServiceInfo().apply {
                name = "com.neusoft.appleservice.AppleService"
                exported = true
            })
            providers = arrayOf(ProviderInfo().apply {
                name = "com.neusoft.appleservice.AppleProvider"
                exported = true
                authority = "apple.metadata"
            })
        }
        `when`(pm.getPackageInfo(eq(AppleStackExport.PACKAGE), anyInt())).thenReturn(info)
    }

    private fun archive(name: String, entries: Map<String, ByteArray> = emptyMap()): File =
        folder.newFile(name).apply {
            ZipOutputStream(outputStream()).use { output ->
                output.putNextEntry(ZipEntry("metadata.txt"))
                output.write("fixture".toByteArray())
                output.closeEntry()
                for ((path, bytes) in entries) {
                    output.putNextEntry(ZipEntry(path))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }

    private fun output(context: Context) = File(context.getExternalFilesDir(null), "DiPlayVendorDump")

    private fun assertSafe(context: Context) {
        verify(context, never()).bindService(any(), any(), anyInt())
        verify(context, never()).startService(any())
        verify(context, never()).stopService(any())
        verify(context, never()).sendBroadcast(any())
        verify(context, never()).registerReceiver(any(), any())
        verify(context, never()).getSystemService(anyString())
    }

    private fun elf(wide: Boolean = false, little: Boolean = true, needed: String? = null, config: String? = null): ByteArray {
        val buffer = ByteBuffer.allocate(1024).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        buffer.put(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, if (wide) 2 else 1, if (little) 1 else 2, 1))
        buffer.putShort(18, if (wide) 183 else 40)
        val phOffset = if (wide) 64 else 52
        val phSize = if (wide) 56 else 32
        if (wide) {
            buffer.putLong(32, phOffset.toLong())
            buffer.putShort(54, phSize.toShort())
            buffer.putShort(56, 2)
        } else {
            buffer.putInt(28, phOffset)
            buffer.putShort(42, phSize.toShort())
            buffer.putShort(44, 2)
        }
        fun segment(offset: Int, type: Int, start: Int, address: Int, size: Int) {
            buffer.putInt(offset, type)
            if (wide) {
                buffer.putLong(offset + 8, start.toLong())
                buffer.putLong(offset + 16, address.toLong())
                buffer.putLong(offset + 32, size.toLong())
            } else {
                buffer.putInt(offset + 4, start)
                buffer.putInt(offset + 8, address)
                buffer.putInt(offset + 16, size)
            }
        }
        segment(phOffset, 1, 0, 0x1000, 1024)
        segment(phOffset + phSize, 2, 0x100, 0x1100, if (wide) 64 else 32)
        val strings = byteArrayOf(0) + (needed.orEmpty() + "\u0000").toByteArray()
        fun dynamic(index: Int, tag: Int, value: Int) {
            val offset = 0x100 + index * if (wide) 16 else 8
            if (wide) { buffer.putLong(offset, tag.toLong()); buffer.putLong(offset + 8, value.toLong()) }
            else { buffer.putInt(offset, tag); buffer.putInt(offset + 4, value) }
        }
        dynamic(0, 5, 0x1180)
        dynamic(1, 10, strings.size)
        if (needed != null) dynamic(2, 1, 1)
        buffer.position(0x180)
        buffer.put(strings)
        buffer.position(0x300)
        buffer.put((config.orEmpty() + "\u0000").toByteArray())
        return buffer.array()
    }
}
