package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

internal object AppleStackExport {
    internal const val PACKAGE = "com.neusoft.appleservice"
    private const val TAG = "DiPlayPhase3B5Device"
    private val roots = listOf("libAppleCore_jni.so", "libApplePrivate_jni.so")
    private val terms = listOf("apple", "usb", "iap", "carplay", "ncm", "accessory", "authentication", "auth")
    private val safeName = Regex("[A-Za-z0-9_.-]+")

    fun export(context: Context, libraryPath: String? = null): String = buildString {
        appendLine("Phase 3B.5 Apple/USB stack collection ONLY: package metadata and verified file copies.")
        appendLine("No bind/start/stop, broadcasts, native loading, /dev access, USB role changes, phone connection or authentication.")
        val directories = linkedSetOf<File>()
        val copied = linkedSetOf<String>()
        val rootMetadata = mutableListOf<Pair<String, NativeCollectionMetadata.Metadata>>()
        val configReferences = linkedSetOf<String>()
        val archives = mutableListOf<File>()
        fun failure(operation: String, error: Throwable) {
            appendLine("$operation FAIL ${error.javaClass.simpleName}: ${error.message}")
            Log.w(TAG, "$operation failed", error)
        }
        fun attempt(operation: String, block: () -> Unit) {
            try { block() } catch (error: Exception) { failure(operation, error) }
            catch (error: LinkageError) { failure(operation, error) }
        }
        fun directory(path: String, origin: String) = attempt("Library directory $path") {
            val file = safeFile(File(path))
            check(File(path).isAbsolute) { "Relative library directory is not an installed absolute location" }
            appendLine("Library directory origin=$origin path=$file readable=${file.isDirectory && file.canRead()}")
            if (file.isDirectory && file.canRead()) directories.add(file)
            else appendLine("Library directory FAIL unavailable/unreadable: $file")
        }
        fun copy(file: File, label: String, output: String) = attempt("Copy $label") {
            val source = safeFile(file)
            check(source.isFile && source.canRead()) { "Source unavailable/unreadable: $source" }
            if (source.path !in copied) {
                appendLine("$label source=$source")
                val saved = VendorBluetoothApkExport.saveApk(context, source, output, this)
                copied.add(source.path)
                appendLine("$label SUCCESS $saved")
                Log.i(TAG, "$label SUCCESS $saved")
            } else appendLine("$label already collected source=$source")
        }
        attempt("Installed package $PACKAGE") {
            @Suppress("DEPRECATION")
            val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
                PackageManager.GET_DISABLED_COMPONENTS or PackageManager.GET_SHARED_LIBRARY_FILES
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(PACKAGE, flags)
            check(info.packageName == PACKAGE) { "PackageManager returned a different/missing package identity" }
            val app = checkNotNull(info.applicationInfo) { "ApplicationInfo unavailable" }
            @Suppress("DEPRECATION")
            appendLine("Package=${info.packageName} versionName=${info.versionName} versionCode=${info.versionCode}")
            appendLine("sourceDir=${app.sourceDir} nativeLibraryDir=${app.nativeLibraryDir}")
            appendLine("splitSourceDirs=${app.splitSourceDirs.orEmpty().joinToString()}")
            appendLine("sharedLibraryFiles=${app.sharedLibraryFiles.orEmpty().joinToString()}")
            fun components(kind: String, items: Array<out ComponentInfo>?) {
                items.orEmpty().forEach {
                    if (it.exported || terms.any { term -> it.name.orEmpty().contains(term, true) }) {
                        val permission = when (it) {
                            is android.content.pm.ServiceInfo -> it.permission
                            is android.content.pm.ActivityInfo -> it.permission
                            is android.content.pm.ProviderInfo ->
                                "read=${it.readPermission} write=${it.writePermission} authority=${it.authority}"
                            else -> null
                        }
                        appendLine("$kind=${it.name} exported=${it.exported} enabled=${it.enabled} permission=${permission ?: "none"}")
                    }
                }
            }
            components("service", info.services)
            components("receiver", info.receivers)
            components("provider", info.providers)
            app.nativeLibraryDir?.let { directory(it, "ApplicationInfo.nativeLibraryDir") }
            app.sharedLibraryFiles.orEmpty().forEach { path ->
                attempt("Shared library metadata $path") {
                    val source = safeFile(File(path))
                    source.parentFile?.let { directory(it.path, "sharedLibraryFiles parent") }
                }
            }
            val sources = listOf(checkNotNull(app.sourceDir) { "sourceDir unavailable" }) +
                app.splitSourceDirs.orEmpty()
            for ((index, path) in sources.withIndex()) {
                val kind = if (index == 0) "base" else "split-$index"
                copy(File(path), "$PACKAGE $kind", "$PACKAGE-$kind.apk")
                attempt("APK archive inventory $path") {
                    val source = safeFile(File(path))
                    check(source.isFile && source.canRead()) { "APK unavailable/unreadable" }
                    archives.add(source)
                }
            }
            attempt("Apple manifest service/action metadata") {
                val resources = context.packageManager.getResourcesForApplication(app)
                resources.assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
                    var component: String? = null
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG) {
                            if (parser.name in listOf("service", "receiver", "provider", "activity")) {
                                component = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                            }
                            if (parser.name == "action") {
                                val action = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                                if (terms.any { action.orEmpty().contains(it, true) || component.orEmpty().contains(it, true) }) {
                                    appendLine("Manifest component=$component action=$action (metadata only)")
                                }
                            }
                        } else if (parser.eventType == XmlPullParser.END_TAG &&
                            parser.name in listOf("service", "receiver", "provider", "activity")) component = null
                        parser.next()
                    }
                }
            }
        }
        attempt("Runtime library search path metadata") {
            (libraryPath ?: System.getProperty("java.library.path").orEmpty())
                .split(File.pathSeparatorChar).filter { it.isNotBlank() }.forEach {
                directory(it, "java.library.path (public Java property; not a hidden Android property)")
            }
        }
        appendLine("Native collection: roots plus direct DT_NEEDED dependencies only; no recursive dependency expansion.")
        appendLine("Config strings are reference candidates, not proof of active configuration; contents are never logged.")
        fun inspect(file: File, label: String): NativeCollectionMetadata.Metadata? {
            var metadata: NativeCollectionMetadata.Metadata? = null
            attempt("Static ELF metadata $label") {
                metadata = NativeCollectionMetadata.inspect(safeFile(file))
                appendLine("ELF $label architecture=${metadata?.architecture} DT_NEEDED=${metadata?.needed?.joinToString()}")
                metadata?.configCandidates?.forEach { appendLine("Config reference candidate $label -> $it") }
            }
            return metadata
        }
        fun libraryFiles(name: String): List<File> {
            val requested = File(name)
            check(safeName.matches(requested.name) && requested.name.matches(Regex(".+\\.so(?:\\.[0-9]+)*")) &&
                (requested.isAbsolute || requested.name == name)) {
                "Unsafe/non-library DT_NEEDED name: $name"
            }
            if (requested.isAbsolute) {
                val source = safeFile(requested)
                check(checkNotNull(source.parentFile) { "DT_NEEDED parent unavailable" } in directories) {
                    "DT_NEEDED path outside resolved library directories: $source"
                }
                if (!source.exists()) return emptyList()
                check(source.isFile && source.canRead()) { "Library unreadable: $source" }
                return listOf(source)
            }
            return directories.mapNotNull { parent ->
                var result: File? = null
                attempt("Resolve $name in $parent") {
                    val candidate = safeFile(File(parent, name))
                    check(candidate.parentFile == parent) { "Library symlink leaves resolved directory: $candidate" }
                    if (candidate.exists()) {
                        check(candidate.isFile && candidate.canRead()) { "Library unreadable: $candidate" }
                        result = candidate
                    }
                }
                result
            }.distinct()
        }
        fun archiveLibraries(name: String, architecture: String? = null): List<Pair<File, String>> {
            if (File(name).isAbsolute) return emptyList()
            val result = mutableListOf<Pair<File, String>>()
            for (archive in archives) attempt("Archive library inventory $archive") {
                ZipFile(archive).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val parts = entry.name.split('/')
                        if (parts.size == 3 && parts[0] == "lib" && safeName.matches(parts[1]) &&
                            parts[2] == name && (architecture == null || when (architecture) {
                                "ARM32" -> parts[1] in listOf("armeabi", "armeabi-v7a")
                                "AArch64" -> parts[1] == "arm64-v8a"
                                else -> parts[1] == architecture
                            })) {
                            result.add(archive to entry.name)
                            check(result.size <= 64) { "Archive library matches exceed 64" }
                        }
                    }
                }
            }
            return result
        }
        fun archiveCopy(archive: File, entryName: String, root: Boolean) = attempt("Archive collection $archive!/$entryName") {
            val key = "$archive!/$entryName"
            if (key in copied) return@attempt
            ZipFile(archive).use { zip ->
                val entry = checkNotNull(zip.getEntry(entryName)) { "Archive entry disappeared" }
                check(entry.size in 1..(64L * 1024 * 1024)) { "Archive library size unavailable/empty/exceeds 64 MiB" }
                val temporary = File.createTempFile("apple-stack-", ".so", context.cacheDir)
                try {
                    zip.getInputStream(entry).use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytes = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                check(bytes <= entry.size) { "Archive library exceeds declared size" }
                                output.write(buffer, 0, count)
                            }
                            check(bytes == entry.size) { "Incomplete archive library extraction" }
                            output.fd.sync()
                        }
                    }
                    val parts = entryName.split('/')
                    val saved = VendorBluetoothApkExport.saveApk(context, temporary,
                        "apple-stack-archive-${archives.indexOf(archive)}-${parts[1]}-${parts[2]}", this)
                    copied.add(key)
                    appendLine("Library archive source=$key SUCCESS $saved")
                    val metadata = inspect(temporary, key)
                    if (root && metadata != null) rootMetadata.add(key to metadata)
                    metadata?.let { configReferences.addAll(it.configCandidates) }
                } finally {
                    if (temporary.exists() && !temporary.delete()) {
                        appendLine("Temporary extraction cleanup FAIL $temporary")
                        Log.w(TAG, "Cannot delete temporary Apple extraction $temporary")
                    }
                }
            }
        }
        for (name in roots) attempt("Root library $name") {
            val files = libraryFiles(name)
            val entries = archiveLibraries(name)
            if (files.isEmpty() && entries.isEmpty()) appendLine("Root library $name FAIL not found in resolved readable directories/APKs.")
            for (file in files) {
                copy(file, "Root library $name", "apple-stack-dir-${directories.indexOf(file.parentFile)}-$name")
                inspect(file, file.path)?.let { rootMetadata.add(file.path to it) }
            }
            entries.forEach { (archive, entry) -> archiveCopy(archive, entry, true) }
        }
        for ((source, metadata) in rootMetadata) {
            configReferences.addAll(metadata.configCandidates)
            for (name in metadata.needed) attempt("Direct dependency $source -> $name") {
                val files = libraryFiles(name)
                val entries = archiveLibraries(name, metadata.architecture)
                if (files.isEmpty() && entries.isEmpty()) appendLine("Direct dependency $source -> $name FAIL unresolved; not guessed.")
                for (file in files) {
                    val dependency = inspect(file, file.path)
                    if (dependency != null && dependency.architecture == metadata.architecture) {
                        copy(file, "Direct dependency $source -> $name", "apple-stack-dir-${directories.indexOf(file.parentFile)}-${file.name}")
                        configReferences.addAll(dependency.configCandidates)
                    } else appendLine("Direct dependency $source -> $file FAIL architecture mismatch or unavailable metadata; expected ${metadata.architecture}.")
                }
                entries.forEach { (archive, entry) -> archiveCopy(archive, entry, false) }
            }
        }
        for ((index, reference) in configReferences.withIndex()) attempt("Config candidate $reference") {
            val candidates = mutableListOf<File>()
            if (reference.startsWith("/")) {
                val file = safeFile(File(reference))
                check(directories.any { within(file, it) } ||
                    listOf("/system/", "/vendor/", "/product/", "/odm/", "/oem/", "/etc/").any { file.path.startsWith(it) }) {
                    "Config reference outside readable library/system-config scope"
                }
                candidates.add(file)
            } else directories.forEach { directory ->
                attempt("Config resolution $reference in $directory") {
                    val file = safeFile(File(directory, reference))
                    check(within(file, directory)) { "Config reference leaves library directory" }
                    candidates.add(file)
                }
            }
            val readable = candidates.distinct().filter { it.isFile && it.canRead() }
            if (readable.isEmpty()) appendLine("Config candidate $reference FAIL unresolved/unreadable; no path guessed.")
            readable.forEachIndexed { match, file ->
                copy(file, "Config candidate $reference", "apple-stack-config-$index-$match-${file.name}")
            }
        }
        appendLine("Collected source files=${copied.size}; SUCCESS lines give exact destination, byte count and SHA-256. Review individual failures; this is not proof of a complete native stack.")
        append("STOP after collection. Copy exported files/report to the PC for static inspection; no hardware interaction or Phase 3C.")
    }

    private fun within(file: File, directory: File): Boolean =
        file.path.startsWith(directory.path + File.separator)

    internal fun safeFile(file: File): File {
        fun allowed(path: String): Boolean {
            val normalized = path.replace('\\', '/').substringAfter(':')
            return listOf("/dev", "/proc", "/sys").none {
                normalized == it || normalized.startsWith("$it/")
            }
        }
        check(allowed(file.absolutePath)) { "Device/kernel path forbidden: $file" }
        val canonical = file.canonicalFile
        check(allowed(canonical.path)) { "Device/kernel symlink target forbidden: $canonical" }
        return canonical
    }
}
