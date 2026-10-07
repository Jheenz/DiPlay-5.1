package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

internal class FullCarPlaySystemInventory(
    private val limits: Limits = Limits(),
    private val roots: List<String> = SYSTEM_ROOTS,
) {
    data class Limits(
        val maxDepth: Int = 8,
        val maxFilesPerRoot: Int = 4000,
        val maxFilesTotal: Int = 12000,
        val maxBytesPerFile: Long = 4L * 1024 * 1024,
        val maxZipEntryBytes: Long = 1024L * 1024,
        val maxArchiveBytes: Long = 4L * 1024 * 1024,
        val maxTotalReadBytes: Long = 128L * 1024 * 1024,
        val maxZipEntries: Int = 1500,
        val maxMatchesPerFile: Int = 12,
        val maxReportCandidates: Int = 500,
        val maxExportFiles: Int = 40,
        val maxExportBytes: Long = 256L * 1024 * 1024,
    )

    data class PackagePaths(
        val packageName: String,
        val sourceDir: String?,
        val splitSourceDirs: List<String>,
        val nativeLibraryDir: String?,
    )

    data class ScanResult(
        val report: String,
        val hasErrors: Boolean,
        val filesScanned: Int,
        val filesSkipped: Int,
        val exportedFiles: List<String>,
    )

    private data class Candidate(
        val file: File,
        val classification: String,
        val matches: List<String>,
        val jniSymbols: List<String>,
        val neededLibraries: List<String>,
    )

    private val filesSeen = LinkedHashSet<String>()
    private val candidates = mutableListOf<Candidate>()
    private val errors = mutableListOf<String>()
    private val unreadableRoots = mutableListOf<String>()
    private val packageLibraryPaths = mutableListOf<String>()
    private var filesScanned = 0
    private var filesSkipped = 0
    private var totalBytesSampled = 0L
    private var reportCandidatesOmitted = 0
    private var scanStartedAt = ""

    fun scanDevice(context: Context, onProgress: (String) -> Unit = {}): ScanResult {
        scanStartedAt = timestamp()
        val packages = mutableListOf<PackagePaths>()
        val packageErrors = mutableListOf<String>()
        try {
            val installed = context.packageManager.getInstalledPackages(0)
            for (packageInfo in installed) {
                val app = packageInfo.applicationInfo ?: continue
                packages += PackagePaths(
                    packageName = packageInfo.packageName,
                    sourceDir = app.sourceDir,
                    splitSourceDirs = app.splitSourceDirs?.toList().orEmpty(),
                    nativeLibraryDir = app.nativeLibraryDir,
                )
            }
        } catch (error: Exception) {
            packageErrors += "PackageManager inventory failed: ${error.javaClass.simpleName}: ${error.message}"
        }

        val destination = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "DiPlayVendorDump/phase3b8",
        )
        return scan(packages, destination, onProgress, packageErrors)
    }

    internal fun scan(
        packages: List<PackagePaths>,
        exportDirectory: File,
        onProgress: (String) -> Unit = {},
        initialErrors: List<String> = emptyList(),
    ): ScanResult {
        if (scanStartedAt.isEmpty()) scanStartedAt = timestamp()
        errors += initialErrors
        val rootCounts = linkedMapOf<String, Int>()
        for (rootPath in roots) {
            if (filesScanned >= limits.maxFilesTotal) {
                filesSkipped++
                errors += "Global file limit reached before root $rootPath"
                break
            }
            val root = File(rootPath)
            try {
                if (!root.exists()) {
                    unreadableRoots += "$rootPath (missing)"
                    errors += "Root missing: $rootPath"
                    continue
                }
                if (!root.isDirectory || !root.canRead()) {
                    unreadableRoots += "$rootPath (unreadable or not a directory)"
                    errors += "Root unreadable or not a directory: $rootPath"
                    continue
                }
            } catch (error: SecurityException) {
                unreadableRoots += "$rootPath (permission denied)"
                errors += "Root access failed: $rootPath: ${error.message}"
                continue
            }
            val startCount = filesScanned
            rootFileCountStart = startCount
            walk(root, root, 0, onProgress)
            rootCounts[rootPath] = filesScanned - startCount
        }

        val packageFiles = LinkedHashSet<String>()
        for (pkg in packages) {
            packageLibraryPaths +=
                "package=${pkg.packageName} sourceDir=${pkg.sourceDir ?: "none"} " +
                    "splitSourceDirs=${pkg.splitSourceDirs.joinToString(",").ifEmpty { "none" }} " +
                    "nativeLibraryDir=${pkg.nativeLibraryDir ?: "none"}"
            (listOfNotNull(pkg.sourceDir) + pkg.splitSourceDirs).forEach { packageFiles += it }
            val nativeDir = pkg.nativeLibraryDir?.let(::File)
            if (nativeDir != null && isPackageScopedNativeDir(nativeDir, pkg.packageName)) {
                val usable = try { nativeDir.isDirectory && nativeDir.canRead() } catch (error: SecurityException) {
                    errors += "Package nativeLibraryDir access failed: ${pkg.packageName} ${nativeDir.path}: ${error.message}"
                    false
                }
                if (!usable) {
                    errors += "Package nativeLibraryDir unreadable: ${pkg.packageName} ${nativeDir.path}"
                    continue
                }
                val children = try {
                    nativeDir.listFiles()
                } catch (error: SecurityException) {
                    errors += "Package nativeLibraryDir unreadable: ${pkg.packageName} ${nativeDir.path}: ${error.message}"
                    null
                }
                if (children == null) {
                    errors += "Package nativeLibraryDir listing failed: ${pkg.packageName} ${nativeDir.path}"
                } else {
                    children.filter { it.isFile }.forEach { packageFiles += it.path }
                }
            } else if (nativeDir != null) {
                errors += "Skipped non-package-specific nativeLibraryDir: ${pkg.packageName} ${nativeDir.path}"
            }
        }
        for (path in packageFiles) {
            if (filesScanned >= limits.maxFilesTotal) {
                filesSkipped++
                errors += "Global file limit reached while inspecting PackageManager paths"
                break
            }
            inspectExplicitPackageFile(File(path), onProgress)
        }

        val strongLibraryNames = candidates.filter { it.classification == STRONG }
            .map { it.file.name.lowercase(Locale.ROOT) }.toSet()
        val finalized = candidates.map { candidate ->
            if (candidate.classification != STRONG &&
                candidate.neededLibraries.any { it.lowercase(Locale.ROOT) in strongLibraryNames }
            ) candidate.copy(classification = SUPPORTING) else candidate
        }
        val exported = exportCandidates(finalized, exportDirectory)
        val report = buildReport(rootCounts, packages.size, finalized, exported)
        return ScanResult(report, errors.isNotEmpty(), filesScanned, filesSkipped, exported)
    }

    private fun walk(root: File, current: File, depth: Int, onProgress: (String) -> Unit) {
        if (depth > limits.maxDepth || filesScanned >= limits.maxFilesTotal) {
            filesSkipped++
            return
        }
        val canonicalRoot = try {
            root.canonicalFile
        } catch (error: Exception) {
            errors += "Cannot resolve root ${root.path}: ${error.message}"
            return
        }
        if (isForbiddenPath(canonicalRoot.path) || isDataFilesystemPath(canonicalRoot.path)) {
            errors += "Skipped prohibited scan root target: ${canonicalRoot.path}"
            unreadableRoots += "${root.path} (prohibited target)"
            filesSkipped++
            return
        }
        val canonical = try {
            current.canonicalFile
        } catch (error: Exception) {
            errors += "Cannot resolve path ${current.path}: ${error.message}"
            filesSkipped++
            return
        }
        if (canonical != canonicalRoot && !canonical.path.startsWith(canonicalRoot.path + File.separator)) {
            errors += "Skipped path escaping scan root: ${current.path}"
            filesSkipped++
            return
        }
        val readable = try {
            current.canRead()
        } catch (error: SecurityException) {
            errors += "Path access failed: ${current.path}: ${error.message}"
            filesSkipped++
            return
        }
        if (!readable) {
            errors += "Unreadable path: ${current.path}"
            filesSkipped++
            return
        }
        val isFile = try { current.isFile } catch (error: SecurityException) {
            errors += "File metadata access failed: ${current.path}: ${error.message}"
            filesSkipped++
            return
        }
        if (isFile) {
            if (filesScanned >= limits.maxFilesTotal) {
                filesSkipped++
                return
            }
            inspectFile(current, onProgress)
            return
        }
        val isDirectory = try { current.isDirectory } catch (error: SecurityException) {
            errors += "Directory metadata access failed: ${current.path}: ${error.message}"
            filesSkipped++
            return
        }
        if (!isDirectory) return
        if (depth == limits.maxDepth) {
            val children = try { current.listFiles() } catch (_: SecurityException) { null }
            if (!children.isNullOrEmpty()) filesSkipped += children.size
            return
        }
        val children = try {
            current.listFiles()
        } catch (error: SecurityException) {
            errors += "Directory listing failed: ${current.path}: ${error.message}"
            filesSkipped++
            null
        }
        if (children == null) {
            errors += "Directory listing unavailable: ${current.path}"
            filesSkipped++
            return
        }
        for (child in children.sortedBy { it.name.lowercase(Locale.ROOT) }) {
            if (filesScanned >= limits.maxFilesTotal) {
                filesSkipped += children.size
                errors += "Global file limit reached under ${current.path}"
                return
            }
            val withinRootCount = filesScanned - rootFileCountStart
            if (withinRootCount >= limits.maxFilesPerRoot) {
                filesSkipped += children.size
                errors += "Per-root file limit reached: ${root.path}"
                return
            }
            walk(root, child, depth + 1, onProgress)
        }
    }

    private var rootFileCountStart = 0

    private fun inspectExplicitPackageFile(file: File, onProgress: (String) -> Unit) {
        try {
            if (isForbiddenPath(file.path) || isForbiddenPath(file.absolutePath)) {
                errors += "PackageManager path is outside permitted ordinary-file locations: ${file.path}"
                filesSkipped++
                return
            }
            val canonical = file.canonicalFile
            if (isForbiddenPath(canonical.path) || !canonical.isFile || !canonical.canRead()) {
                errors += "PackageManager file unreadable: ${file.path}"
                filesSkipped++
                return
            }
            inspectFile(canonical, onProgress)
        } catch (error: SecurityException) {
            errors += "PackageManager path failed: ${file.path}: ${error.message}"
            filesSkipped++
        }
    }

    private fun inspectFile(file: File, onProgress: (String) -> Unit) {
        val canonical = try { file.canonicalPath } catch (error: Exception) {
            errors += "Canonical path failed: ${file.path}: ${error.message}"
            filesSkipped++
            return
        }
        if (!filesSeen.add(canonical)) return
        if (filesScanned >= limits.maxFilesTotal) {
            filesSkipped++
            return
        }
        filesScanned++
        if (filesScanned % 50 == 0) onProgress("Scanning... $filesScanned files")
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext !in INSPECTABLE_EXTENSIONS) return
        val matches = linkedSetOf<String>()
        matches += matchingTerms(file.name)
        val jniSymbols = linkedSetOf<String>()
        val needed = linkedSetOf<String>()
        var embeddedStrongLibrary = false
        try {
            val remainingBudget = (limits.maxTotalReadBytes - totalBytesSampled).coerceAtLeast(0)
            if (remainingBudget == 0L) {
                filesSkipped++
                if (errors.none { it.startsWith("Total scan byte limit reached") }) {
                    errors += "Total scan byte limit reached (${limits.maxTotalReadBytes} bytes); remaining files were filename-only"
                }
            } else when (ext) {
                "apk", "jar" -> {
                    val archiveResult = inspectArchive(
                        file, matches, jniSymbols, onProgress,
                        minOf(limits.maxArchiveBytes, remainingBudget),
                    )
                    embeddedStrongLibrary = archiveResult.first
                    totalBytesSampled += archiveResult.second
                }
                else -> {
                    val sampled = sampleFile(file, minOf(limits.maxBytesPerFile, remainingBudget))
                    totalBytesSampled += sampled.bytesRead
                    matches += matchingTerms(sampled.text)
                    jniSymbols += sampled.symbols
                    if (ext == "so") needed += elfNeededLibraries(file)
                }
            }
        } catch (error: Exception) {
            errors += "Read failed: $canonical: ${error.javaClass.simpleName}: ${error.message}"
            filesSkipped++
            return
        } catch (error: LinkageError) {
            errors += "Read failed: $canonical: ${error.javaClass.simpleName}: ${error.message}"
            filesSkipped++
            return
        }
        val classification = classify(file.name, ext, matches, jniSymbols, needed, embeddedStrongLibrary)
        if (classification != FALSE_POSITIVE || matches.isNotEmpty()) {
            if (candidates.size < limits.maxReportCandidates) {
                candidates += Candidate(
                    file,
                    classification,
                    matches.take(limits.maxMatchesPerFile),
                    jniSymbols.take(limits.maxMatchesPerFile),
                    needed.take(limits.maxMatchesPerFile),
                )
            } else {
                reportCandidatesOmitted++
                filesSkipped++
            }
        }
    }

    private fun inspectArchive(
        file: File,
        matches: MutableSet<String>,
        symbols: MutableSet<String>,
        onProgress: (String) -> Unit,
        byteBudget: Long,
    ): Pair<Boolean, Long> {
        var totalRead = 0L
        val archiveReadLimit = minOf(byteBudget, limits.maxBytesPerFile)
        var entryCount = 0
        var strongEmbedded = false
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (++entryCount > limits.maxZipEntries) {
                    filesSkipped++
                    errors += "ZIP entry limit reached: ${file.path}"
                    break
                }
                val entryName = entry.name
                matches += matchingTerms(entryName)
                val entryExt = entryName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val relevant = entryExt == "dex" || entryExt == "so" ||
                    entryName == "AndroidManifest.xml" || entryName == "resources.arsc" ||
                    (entryExt == "xml" && (entryName.startsWith("res/") || entryName.contains("manifest")))
                if (!relevant || entry.isDirectory || totalRead >= archiveReadLimit) continue
                val byteLimit = minOf(limits.maxZipEntryBytes, archiveReadLimit - totalRead)
                val sample = zip.getInputStream(entry).use { input -> sampleStream(input, byteLimit) }
                totalRead += sample.bytesRead
                matches += matchingTerms(sample.text)
                symbols += sample.symbols
                if (entryExt == "so" && isStrongNativeEvidence(entryName, sample.text.lines().toSet(), sample.symbols.toSet())) {
                    strongEmbedded = true
                }
            }
        }
        onProgress("Scanning... ${file.name}")
        return strongEmbedded to totalRead
    }

    private data class Sample(
        val text: String,
        val symbols: List<String>,
        val bytesRead: Long,
    )

    private fun sampleFile(file: File, maxBytes: Long): Sample =
        FileInputStream(file).use { sampleStream(BufferedInputStream(it), maxBytes) }

    private fun sampleStream(input: InputStream, maxBytes: Long): Sample {
        val matchedSymbols = linkedSetOf<String>()
        val matches = linkedSetOf<String>()
        var totalRead = 0L
        var printable = StringBuilder()
        val buffer = ByteArray(16 * 1024)
        while (totalRead < maxBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - totalRead).toInt())
            if (count < 0) break
            totalRead += count
            for (index in 0 until count) {
                val value = buffer[index].toInt() and 0xff
                if (value in 32..126 || value >= 128) {
                    if (printable.length < 512) printable.append(value.toChar())
                } else {
                    collectString(printable, matches, matchedSymbols)
                }
            }
        }
        collectString(printable, matches, matchedSymbols)
        return Sample(matches.joinToString("\n"), matchedSymbols.toList(), totalRead)
    }

    private fun collectString(
        value: StringBuilder,
        matches: MutableSet<String>,
        symbols: MutableSet<String>,
    ) {
        if (value.isNotEmpty()) {
            val text = value.toString()
            matches += matchingTerms(text)
            JNI_SYMBOL_PATTERNS.forEach { pattern ->
                if (text.contains(pattern, ignoreCase = true)) symbols += pattern
            }
            if (text.contains("appleCore_native_", true)) symbols += "appleCore_native_"
            if (text.contains("applePrivate_native_", true)) symbols += "applePrivate_native_"
            value.setLength(0)
        }
    }

    private fun elfNeededLibraries(file: File): List<String> {
        val names = linkedSetOf<String>()
        try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 64) return emptyList()
                val ident = ByteArray(16)
                raf.seek(0)
                raf.readFully(ident)
                if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() ||
                    ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()
                ) return emptyList()
                val is64 = ident[4].toInt() == 2
                val little = ident[5].toInt() == 1
                if (!little) return emptyList()
                val phoff = readUnsigned(raf, if (is64) 32 else 28, if (is64) 8 else 4)
                val phentsize = readUnsigned(raf, if (is64) 54 else 42, 2).toInt()
                val phnum = readUnsigned(raf, if (is64) 56 else 44, 2).toInt().coerceAtMost(256)
                var stringTableAddress: Long? = null
                var stringTableSize = 0L
                var dynamicOffset: Long? = null
                var dynamicSize = 0L
                val loads = mutableListOf<Triple<Long, Long, Long>>()
                for (index in 0 until phnum) {
                    val offset = phoff + index.toLong() * phentsize
                    if (offset < 0 || offset + phentsize > raf.length()) break
                    val type = readUnsigned(raf, offset, 4).toInt()
                    val pOffset = readUnsigned(raf, offset + if (is64) 8 else 4, if (is64) 8 else 4)
                    val vAddr = readUnsigned(raf, offset + if (is64) 16 else 8, if (is64) 8 else 4)
                    val fileSize = readUnsigned(raf, offset + if (is64) 32 else 16, if (is64) 8 else 4)
                    if (type == 1) loads += Triple(vAddr, pOffset, fileSize)
                    if (type == 2) {
                        dynamicOffset = pOffset
                        dynamicSize = fileSize.coerceAtMost(64 * 1024)
                    }
                }
                if (dynamicOffset == null) return emptyList()
                val itemSize = if (is64) 16 else 8
                var neededOffsets = mutableListOf<Long>()
                var cursor = dynamicOffset
                val end = minOf(dynamicOffset + dynamicSize, raf.length())
                while (cursor + itemSize <= end) {
                    val tag = readUnsigned(raf, cursor, if (is64) 8 else 4)
                    val value = readUnsigned(raf, cursor + if (is64) 8 else 4, if (is64) 8 else 4)
                    if (tag == 0L) break
                    if (tag == 1L) neededOffsets += value
                    if (tag == 5L) stringTableAddress = value
                    if (tag == 10L) stringTableSize = value.coerceAtMost(1024 * 1024)
                    cursor += itemSize
                }
                val strAddress = stringTableAddress ?: return emptyList()
                val strOffset = loads.firstNotNullOfOrNull { (address, offset, size) ->
                    if (strAddress >= address && strAddress < address + size) offset + (strAddress - address) else null
                } ?: return emptyList()
                for (stringIndex in neededOffsets) {
                    if (stringIndex < 0 || stringIndex >= stringTableSize) continue
                    val start = strOffset + stringIndex
                    if (start < 0 || start >= raf.length()) continue
                    raf.seek(start)
                    val name = StringBuilder()
                    for (index in 0 until 512) {
                        val byte = raf.read()
                        if (byte < 0 || byte == 0) break
                        name.append(byte.toChar())
                    }
                    if (name.toString().matches(Regex("lib[A-Za-z0-9_.+-]+\\.so"))) names += name.toString()
                }
            }
        } catch (error: Exception) {
            errors += "ELF DT_NEEDED parse failed: ${file.path}: ${error.javaClass.simpleName}: ${error.message}"
        }
        return names.toList()
    }

    private fun readUnsigned(raf: java.io.RandomAccessFile, offset: Long, size: Int): Long {
        if (offset < 0 || offset + size > raf.length()) return 0
        raf.seek(offset)
        var result = 0L
        repeat(size) { result = (result shl 8) or raf.read().toLong() }
        return if (size == 8) java.lang.Long.reverseBytes(result) else {
            var little = 0L
            repeat(size) { little = (little shl 8) or ((result ushr (it * 8)) and 0xff) }
            little
        }
    }

    private fun exportCandidates(candidates: List<Candidate>, directory: File): List<String> {
        val exportable = candidates.filter { it.classification == STRONG || it.classification == SUPPORTING }
        val results = mutableListOf<String>()
        if (exportable.isEmpty()) return results
        try {
            if (!directory.exists() && !directory.mkdirs()) {
                errors += "Export directory could not be created: ${directory.path}"
                return results
            }
            if (!directory.isDirectory || !directory.canWrite()) {
                errors += "Export directory is not writable: ${directory.path}"
                return results
            }
        } catch (error: Exception) {
            errors += "Export directory failure: ${directory.path}: ${error.javaClass.simpleName}: ${error.message}"
            return results
        }
        var exportedBytes = 0L
        for ((index, candidate) in exportable.withIndex()) {
            if (index >= limits.maxExportFiles) {
                errors += "Export file limit reached (${limits.maxExportFiles}); remaining strong/supporting candidates not copied"
                break
            }
            if (candidate.file.length() > limits.maxExportBytes - exportedBytes) {
                errors += "Export byte limit would be exceeded; skipped ${candidate.file.path} size=${candidate.file.length()}"
                continue
            }
            val destination = File(directory, "%03d-%s".format(Locale.US, index + 1, candidate.file.name))
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                BufferedInputStream(FileInputStream(candidate.file)).use { input ->
                    BufferedOutputStream(FileOutputStream(destination)).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copied += count
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                exportedBytes += copied
                results += "source=${candidate.file.canonicalPath} destination=${destination.canonicalPath} " +
                    "size=$copied SHA-256=$hash classification=${candidate.classification} " +
                    "matched=${candidate.matches.joinToString(",").ifEmpty { "filename" }} " +
                    "symbols=${candidate.jniSymbols.joinToString(",").ifEmpty { "none" }} " +
                    "DT_NEEDED=${candidate.neededLibraries.joinToString(",").ifEmpty { "none" }}"
            } catch (error: Exception) {
                if (destination.exists() && !destination.delete()) {
                    errors += "Partial export could not be removed: ${destination.path}"
                }
                errors += "Export failed source=${candidate.file.path} destination=${destination.path}: " +
                    "${error.javaClass.simpleName}: ${error.message}"
            }
        }
        return results
    }

    private fun buildReport(
        rootCounts: Map<String, Int>,
        packageCount: Int,
        candidates: List<Candidate>,
        exported: List<String>,
    ): String = buildString {
        appendLine("Phase 3B.8 — Full E01 CarPlay system inventory")
        appendLine("Scanner start=$scanStartedAt end=${timestamp()}")
        appendLine("Scan limits: depth=${limits.maxDepth}; files/root=${limits.maxFilesPerRoot}; files/total=${limits.maxFilesTotal}; " +
            "bytes/file=${limits.maxBytesPerFile}; ZIP bytes/file=${limits.maxArchiveBytes}; " +
            "total sampled=${limits.maxTotalReadBytes}; ZIP entry bytes=${limits.maxZipEntryBytes}; " +
            "export files=${limits.maxExportFiles}; export bytes=${limits.maxExportBytes}")
        appendLine("Scanned roots:")
        for (root in roots) appendLine("  $root files=${rootCounts[root] ?: 0}")
        appendLine("Unreadable/missing roots: ${unreadableRoots.joinToString(" | ").ifEmpty { "none" }}")
        appendLine("Files scanned=$filesScanned; files skipped=$filesSkipped; sampled bytes=$totalBytesSampled")
        appendLine("Installed packages inspected=$packageCount")
        appendLine("Package native-library paths:")
        packageLibraryPaths.take(2000).forEach { appendLine("  $it") }
        appendLine("APK/JAR candidates=${candidates.count { it.file.extension.lowercase(Locale.ROOT) in setOf("apk", "jar") }}")
        appendLine("Native-library candidates=${candidates.count { it.file.extension.equals("so", true) }}")
        appendLine("JNI symbol matches=${candidates.flatMap { it.jniSymbols }.distinct().joinToString(",").ifEmpty { "none" }}")
        appendLine("DT_NEEDED relationships:")
        candidates.filter { it.neededLibraries.isNotEmpty() }.forEach { candidate ->
            appendLine("  ${candidate.file.path} -> ${candidate.neededLibraries.joinToString(",")}")
        }
        appendLine("Candidates:")
        candidates.take(limits.maxReportCandidates).forEach { candidate ->
            appendLine("  ${candidate.classification}: ${candidate.file.path} size=${candidate.file.length()} " +
                "matched=${candidate.matches.joinToString(",").ifEmpty { "filename" }} " +
                "symbols=${candidate.jniSymbols.joinToString(",").ifEmpty { "none" }}")
        }
        if (reportCandidatesOmitted > 0) appendLine("Additional candidates omitted=$reportCandidatesOmitted")
        appendLine("Exported files (${exported.size}):")
        exported.forEach { appendLine("  $it") }
        appendLine("Failures (${errors.size}):")
        errors.take(2000).forEach { appendLine("  $it") }
        appendLine("STOP after collection. Static inspection on PC required before any CarPlay runtime work.")
    }

    private fun timestamp(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(java.util.Date())

    private fun isForbiddenPath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return listOf("/dev", "/proc", "/sys").any { normalized == it || normalized.startsWith("$it/") }
    }

    private fun isBroadDataPath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized == "/data" || normalized in setOf(
            "/data/app", "/data/app-private", "/data/app-lib", "/data/data",
            "/data/user", "/data/user_de", "/data/dalvik-cache", "/data/local",
        )
    }

    private fun isDataFilesystemPath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized == "/data" || normalized.startsWith("/data/")
    }

    private fun isPackageScopedNativeDir(directory: File, packageName: String): Boolean {
        val path = try { directory.canonicalPath.replace('\\', '/') } catch (error: Exception) {
            errors += "Cannot resolve nativeLibraryDir ${directory.path}: ${error.message}"
            return false
        }
        if (isForbiddenPath(directory.path) || isForbiddenPath(path) ||
            isBroadDataPath(directory.path) || isBroadDataPath(path) || path in setOf(
                "/data/app", "/data/app-private", "/data/app-lib", "/data/data",
                "/data/user", "/data/user_de", "/data/dalvik-cache", "/data/local",
            )
        ) return false
        if (SYSTEM_ROOTS.any { path == it || path.startsWith("$it/") }) return true
        return path.split('/').any { it == packageName || it.startsWith("$packageName-") }
    }

    companion object {
        const val STRONG = "STRONG CANDIDATE"
        const val SUPPORTING = "SUPPORTING DEPENDENCY"
        const val REFERENCE = "REFERENCE ONLY"
        const val FALSE_POSITIVE = "FALSE POSITIVE / LOW VALUE"

        val SYSTEM_ROOTS = listOf(
            "/system/app", "/system/priv-app", "/system/framework", "/system/lib", "/system/lib64",
            "/system/vendor/app", "/system/vendor/framework", "/system/vendor/lib", "/system/vendor/lib64",
            "/vendor/app", "/vendor/framework", "/vendor/lib", "/vendor/lib64",
        )
        private val INSPECTABLE_EXTENSIONS = setOf("apk", "jar", "so", "xml", "conf", "properties")
        private val TERMS = listOf(
            "AppleCore_jni", "ApplePrivate_jni", "AppleInterface", "ApplePrivate", "AppleService",
            "com.neusoft.appleservice", "CarplayMode", "CarPlay", "CarPlayReceiver", "CarPlaySwitch",
            "com.neusoft.ca.carplay.runningstate", "com.neusoft.apple.device.disconnected",
            "iap", "iap2", "mfi", "iphone", "ipod", "usbncm", "usbncm0", "ncm", "projection",
            "accessory", "authentication",
        )
        private val JNI_SYMBOL_PATTERNS = listOf(
            "Java_com_neusoft_applecore_AppleInterface_",
            "Java_com_neusoft_appleservice_ApplePrivate_",
        )

        fun matchingTerms(value: String): List<String> {
            val lowered = value.lowercase(Locale.ROOT)
            return TERMS.filter { lowered.contains(it.lowercase(Locale.ROOT)) }
        }

        fun classify(
            fileName: String,
            extension: String,
            matchedTerms: Set<String>,
            jniSymbols: Set<String>,
            neededLibraries: Set<String> = emptySet(),
            embeddedStrongLibrary: Boolean = false,
        ): String {
            val lowerName = fileName.lowercase(Locale.ROOT)
            val strongName = lowerName.contains("applecore_jni") ||
                lowerName.contains("appleprivate_jni")
            val nativeEvidence = isStrongNativeEvidence(fileName, matchedTerms, jniSymbols)
            if (strongName || (extension.equals("so", true) && nativeEvidence) || embeddedStrongLibrary) return STRONG
            if (neededLibraries.any { it.lowercase(Locale.ROOT).contains("applecore") ||
                    it.lowercase(Locale.ROOT).contains("appleprivate") }) return SUPPORTING
            val specificReference = matchedTerms.any {
                it.equals("carplay", true) || it.equals("carplaymode", true) ||
                    it.equals("carplayreceiver", true) || it.equals("carplayswitch", true) ||
                    it.equals("appleinterface", true) || it.equals("appleprivate", true) ||
                    it.equals("appleservice", true) || it.equals("com.neusoft.appleservice", true) ||
                    it.equals("com.neusoft.ca.carplay.runningstate", true) ||
                    it.equals("com.neusoft.apple.device.disconnected", true) ||
                    it.equals("iap2", true) || it.equals("mfi", true) ||
                    it.equals("applecore_jni", true) || it.equals("appleprivate_jni", true)
            }
            if ((extension.equals("apk", true) || extension.equals("jar", true)) && specificReference) return REFERENCE
            return if (matchedTerms.isNotEmpty()) FALSE_POSITIVE else FALSE_POSITIVE
        }

        private fun isStrongNativeEvidence(
            name: String,
            terms: Set<String>,
            symbols: Set<String>,
        ): Boolean {
            if (symbols.any { it.startsWith("Java_com_neusoft_apple") ||
                    it.startsWith("appleCore_native_") || it.startsWith("applePrivate_native_") }) return true
            val lowerName = name.lowercase(Locale.ROOT)
            val lowerTerms = terms.map { it.lowercase(Locale.ROOT) }.toSet()
            val hasApple = lowerName.contains("apple") || lowerTerms.any {
                it.contains("apple") || it == "appleinterface" || it == "appleprivate"
            }
            val hasTransport = lowerTerms.any { it in setOf("carplay", "iap2", "mfi", "iphone", "usbncm") }
            return hasApple && hasTransport
        }

    }
}
