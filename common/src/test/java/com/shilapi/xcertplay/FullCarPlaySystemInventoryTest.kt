package com.shilapi.xcertplay

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FullCarPlaySystemInventoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun matchesFilenamesAndContentCaseInsensitively() {
        assertEquals(
            listOf("AppleCore_jni", "CarPlay"),
            FullCarPlaySystemInventory.matchingTerms("libAPPLECORE_JNI.so CARPLAY"),
        )
        assertEquals(
            FullCarPlaySystemInventory.STRONG,
            FullCarPlaySystemInventory.classify(
                "libAppleCore_jni.so", "so", emptySet(), emptySet(),
            ),
        )
    }

    @Test fun classifiesNativeEvidenceReferencesDependenciesAndLowValueMatches() {
        assertEquals(
            FullCarPlaySystemInventory.STRONG,
            FullCarPlaySystemInventory.classify(
                "libprojection.so", "so", setOf("CarPlay", "iap2", "AppleInterface"), emptySet(),
            ),
        )
        assertEquals(
            FullCarPlaySystemInventory.STRONG,
            FullCarPlaySystemInventory.classify(
                "unrelated.so", "so", emptySet(),
                setOf("Java_com_neusoft_applecore_AppleInterface_"),
            ),
        )
        assertEquals(
            FullCarPlaySystemInventory.SUPPORTING,
            FullCarPlaySystemInventory.classify(
                "libclient.so", "so", emptySet(), emptySet(), setOf("libApplePrivate_jni.so"),
            ),
        )
        assertEquals(
            FullCarPlaySystemInventory.REFERENCE,
            FullCarPlaySystemInventory.classify(
                "settings.apk", "apk", setOf("CarPlaySwitch"), emptySet(),
            ),
        )
        assertEquals(
            FullCarPlaySystemInventory.FALSE_POSITIVE,
            FullCarPlaySystemInventory.classify(
                "ordinary.conf", "conf", setOf("authentication"), emptySet(),
            ),
        )
    }

    @Test fun scanHonorsMaximumDepthPerRoot() {
        val root = folder.newFolder("depth")
        File(root, "one").mkdir()
        File(root, "one/two").mkdir()
        File(root, "one/two/deep.conf").writeText("CarPlay")
        File(root, "root.conf").writeText("none")
        val result = inventory(root, FullCarPlaySystemInventory.Limits(maxDepth = 1)).scan(
            emptyList(), folder.newFolder("export"),
        )
        assertEquals(1, result.filesScanned)
        assertTrue(result.filesSkipped > 0)
        assertFalse(result.report.contains("deep.conf"))
    }

    @Test fun scanHonorsMaximumFileCount() {
        val root = folder.newFolder("count")
        listOf("a.conf", "b.conf", "c.conf").forEach { File(root, it).writeText("plain") }
        val result = inventory(root, FullCarPlaySystemInventory.Limits(maxFilesPerRoot = 1)).scan(
            emptyList(), folder.newFolder("export"),
        )
        assertEquals(1, result.filesScanned)
        assertTrue(result.filesSkipped >= 2)
        assertTrue(result.report.contains("Per-root file limit reached"))
    }

    @Test fun sampledReadSizeIsBounded() {
        val root = folder.newFolder("bytes")
        File(root, "large.conf").writeBytes(ByteArray(4096) { 'x'.code.toByte() })
        val result = inventory(
            root,
            FullCarPlaySystemInventory.Limits(maxBytesPerFile = 32),
        ).scan(emptyList(), folder.newFolder("export"))
        assertTrue(result.report.contains("sampled bytes=32"))
    }

    @Test fun totalReadBudgetBoundsCumulativeFileSampling() {
        val root = folder.newFolder("total-bytes")
        File(root, "one.conf").writeBytes(ByteArray(64) { 'a'.code.toByte() })
        File(root, "two.conf").writeBytes(ByteArray(64) { 'b'.code.toByte() })
        val result = inventory(
            root,
            FullCarPlaySystemInventory.Limits(maxBytesPerFile = 32, maxTotalReadBytes = 32),
        ).scan(emptyList(), folder.newFolder("total-export"))
        assertTrue(result.report.contains("sampled bytes=32"))
        assertTrue(result.report.contains("Total scan byte limit reached (32 bytes)"))
    }

    @Test fun apkAndJarDexStringsAreInspectedWithoutLoadingClasses() {
        val root = folder.newFolder("archives")
        writeArchive(File(root, "settings.apk"), "classes.dex", "com.example.CarPlaySwitch")
        writeArchive(File(root, "framework.jar"), "classes.dex", "AppleInterface CarPlay")
        val result = inventory(root).scan(emptyList(), folder.newFolder("export"))
        assertTrue(result.report.contains("REFERENCE ONLY: ${File(root, "settings.apk").path}"))
        assertTrue(result.report.contains("REFERENCE ONLY: ${File(root, "framework.jar").path}"))
    }

    @Test fun elfPrintableJniSymbolsCreateStrongCandidate() {
        val root = folder.newFolder("elf")
        File(root, "libprojection.so").writeBytes(
            "ELF-not-executed\u0000Java_com_neusoft_applecore_AppleInterface_init\u0000".toByteArray(),
        )
        val result = inventory(root).scan(emptyList(), folder.newFolder("export"))
        assertTrue(result.report.contains("STRONG CANDIDATE: ${File(root, "libprojection.so").path}"))
        assertTrue(result.report.contains("Java_com_neusoft_applecore_AppleInterface_"))
    }

    @Test fun exportIncludesOnlyStrongCandidatesAndSupportingDependencies() {
        val root = folder.newFolder("export-allowlist")
        File(root, "AppleCore_jni.so").writeText("candidate bytes")
        writeArchive(File(root, "settings.apk"), "classes.dex", "CarPlaySwitch")
        val destination = folder.newFolder("out")
        val result = inventory(root).scan(emptyList(), destination)
        assertEquals(1, result.exportedFiles.size)
        assertTrue(result.exportedFiles.single().contains("AppleCore_jni.so"))
        assertEquals(listOf("001-AppleCore_jni.so"), destination.list()!!.toList())
        assertTrue(result.exportedFiles.single().contains("SHA-256="))
        assertTrue(result.exportedFiles.single().contains("classification=STRONG CANDIDATE"))
    }

    @Test fun missingRootIsReportedAndDoesNotPreventScanningOtherRoots() {
        val missing = File(folder.root, "missing").path
        val readable = folder.newFolder("readable")
        File(readable, "sample.conf").writeText("CarPlay")
        val result = FullCarPlaySystemInventory(
            roots = listOf(missing, readable.path),
        ).scan(emptyList(), folder.newFolder("exports"))
        assertTrue(result.hasErrors)
        assertTrue(result.report.contains("Root missing: $missing"))
        assertTrue(result.report.contains("${readable.path} files=1"))
    }

    @Test fun unreadableRootIsReportedWithoutStoppingTheNextRoot() {
        val notDirectory = File(folder.root, "not-a-directory").apply { writeText("x") }
        val readable = folder.newFolder("next-root")
        File(readable, "visible.conf").writeText("CarPlay")
        val result = FullCarPlaySystemInventory(
            roots = listOf(notDirectory.path, readable.path),
        ).scan(emptyList(), folder.newFolder("next-export"))
        assertTrue(result.hasErrors)
        assertTrue(result.report.contains("Root unreadable or not a directory: ${notDirectory.path}"))
        assertTrue(result.report.contains("${readable.path} files=1"))
    }

    @Test fun blockedDeviceProcAndSysRootsAreNotConfiguredForTraversal() {
        assertFalse(FullCarPlaySystemInventory.SYSTEM_ROOTS.any {
            it == "/dev" || it.startsWith("/dev/") ||
                it == "/proc" || it.startsWith("/proc/") ||
                it == "/sys" || it.startsWith("/sys/")
        })
        val result = inventory(folder.newFolder("safe")).scan(
            listOf(FullCarPlaySystemInventory.PackagePaths(
                "blocked", "/proc/version", emptyList(), "/sys/kernel",
            )),
            folder.newFolder("blocked-export"),
        )
        assertFalse(result.report.contains("source=/proc/version"))
        assertTrue(result.report.contains("outside permitted ordinary-file locations:"))
    }

    @Test fun broadDataDirectoryIsNeverListedAsPackageNativeLibraryPath() {
        val result = inventory(folder.newFolder("no-data")).scan(
            listOf(FullCarPlaySystemInventory.PackagePaths(
                "example.app", null, emptyList(), "/data",
            )),
            folder.newFolder("no-data-export"),
        )
        assertTrue(result.report.contains("Skipped non-package-specific nativeLibraryDir:"))
        assertEquals(0, result.filesScanned)
    }

    @Test fun exportClassificationAllowsOnlyStrongAndSupporting() {
        val temp = folder.newFolder("classification")
        val allowed = FullCarPlaySystemInventory.classify(
            "libclient.so", "so", emptySet(), emptySet(), setOf("libAppleCore_jni.so"),
        )
        val rejected = FullCarPlaySystemInventory.classify(
            "reference.apk", "apk", setOf("CarPlayReceiver"), emptySet(),
        )
        assertTrue(allowed == FullCarPlaySystemInventory.STRONG ||
            allowed == FullCarPlaySystemInventory.SUPPORTING)
        assertFalse(rejected == FullCarPlaySystemInventory.STRONG ||
            rejected == FullCarPlaySystemInventory.SUPPORTING)
        assertTrue(temp.isDirectory)
    }

    private fun inventory(
        root: File,
        limits: FullCarPlaySystemInventory.Limits = FullCarPlaySystemInventory.Limits(),
    ) = FullCarPlaySystemInventory(limits, listOf(root.path))

    private fun writeArchive(file: File, entryName: String, text: String) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }
}
