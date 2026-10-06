package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import org.xmlpull.v1.XmlPullParser

internal object AppleImplementationDiscovery {
    private const val TAG = "DiPlayPhase3B5bDevice"
    private val packageTerms = listOf("apple", "carplay", "iap", "iphone", "ipod", "usb", "ncm",
        "projection", "phone", "link", "mirror", "ecarx", "neusoft")
    private val nativeTerms = listOf("apple", "carplay", "iap", "mfi", "usb", "ncm", "accessory", "projection")
    internal val systemLibraryDirectories = listOf("/system/lib", "/system/lib64", "/vendor/lib",
        "/vendor/lib64", "/system/vendor/lib", "/system/vendor/lib64")

    fun discover(context: Context): String = collect(context, systemLibraryDirectories)

    internal fun collect(context: Context, libraryDirectories: List<String>): String = buildString {
        appendLine("Phase 3B.5b Apple implementation discovery: manual read-only inventory/export ONLY.")
        appendLine("No service start/bind/stop, intents/broadcasts, callbacks, native loading, /dev access, USB roles, Bluetooth commands, iPhone connection or authentication.")
        appendLine("Substring candidates are leads, not proof of Apple/iAP implementation. No vendor classes are loaded.")
        val directories = linkedMapOf<String, MutableSet<String>>()
        val copied = linkedMapOf<String, String>()
        var candidates = 0
        fun attempt(operation: String, action: () -> Unit) {
            try { action() } catch (error: Exception) {
                appendLine("$operation FAIL ${error.javaClass.simpleName}: ${error.message}")
                Log.w(TAG, "$operation failed", error)
            } catch (error: LinkageError) {
                appendLine("$operation FAIL ${error.javaClass.simpleName}: ${error.message}")
                Log.w(TAG, "$operation API unavailable", error)
            }
        }
        fun addDirectory(path: String, origin: String) = attempt("Native directory metadata $path") {
            check(File(path).isAbsolute) { "Relative native directory rejected" }
            val file = AppleStackExport.safeFile(File(path))
            directories.getOrPut(file.path) { linkedSetOf() }.add("$origin reportedPath=$path")
        }
        fun copy(file: File, label: String, name: String) = attempt("Export $label") {
            val source = AppleStackExport.safeFile(file)
            check(source.isFile && source.canRead()) { "Not an ordinary readable file: $source" }
            val prior = copied[source.path]
            if (prior != null) {
                appendLine("$label already exported source=$source $prior")
            } else {
                appendLine("$label source=$source")
                val saved = VendorBluetoothApkExport.saveApk(context, source, name, this)
                copied[source.path] = saved
                appendLine("$label SUCCESS $saved")
                Log.i(TAG, "$label source=$source SUCCESS $saved")
            }
        }
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
            PackageManager.GET_ACTIVITIES or PackageManager.GET_DISABLED_COMPONENTS
        libraryDirectories.forEach { addDirectory(it, "requested system native location") }
        attempt("Installed package enumeration") {
            @Suppress("DEPRECATION")
            val installed = context.packageManager.getInstalledPackages(flags)
            appendLine("Installed visible packages=${installed.size}; Android API 22 has no modern package visibility filter; newer Android inventory can be restricted.")
            for ((packageIndex, info) in installed.sortedBy { it.packageName }.withIndex()) {
                attempt("Package metadata ${info.packageName}") {
                    val packageName = checkNotNull(info.packageName) { "Missing package identity" }
                    check(packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))) { "Unsafe package name" }
                    val app = checkNotNull(info.applicationInfo) { "ApplicationInfo unavailable" }
                    app.nativeLibraryDir?.let { addDirectory(it, "package=$packageName nativeLibraryDir") }
                    val matches = linkedSetOf<String>()
                    if (relevant(packageName)) matches.add("package=$packageName")
                    app.className?.takeIf(::relevant)?.let { matches.add("application=$it") }
                    componentMatches(info).forEach { matches.add(it) }
                    val sources = listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty()
                    if (sources.isEmpty()) appendLine("Package $packageName source FAIL sourceDir/splitSourceDirs unavailable.")
                    sources.forEach { path ->
                        if (relevant(File(path).name)) matches.add("APK filename=${File(path).name} source=$path")
                    }
                    // Installed system manifests are inspected even when their package name does not match.
                    attempt("Manifest metadata $packageName") {
                        check(sources.isNotEmpty()) { "No installed APK sources for resource metadata" }
                        sources.forEach { AppleStackExport.safeFile(File(it)) }
                        val resources = context.packageManager.getResourcesForApplication(app)
                        resources.assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
                            manifestMatches(parser).forEach { matches.add(it) }
                        }
                    }
                    sources.forEach { path ->
                        attempt("Readable APK metadata $packageName source=$path") {
                            val source = AppleStackExport.safeFile(File(path))
                            check(source.isFile && source.canRead()) { "APK not an ordinary readable file: $source" }
                            @Suppress("DEPRECATION")
                            val archive = context.packageManager.getPackageArchiveInfo(source.path, flags)
                            if (archive == null) {
                                appendLine("APK metadata $source FAIL no PackageManager archive metadata (split or unsupported archive); installed metadata remains available.")
                                Log.w(TAG, "No archive metadata for $source")
                            } else {
                                if (relevant(archive.packageName.orEmpty())) matches.add("APK package=${archive.packageName} source=$source")
                                componentMatches(archive).forEach { matches.add("APK source=$source $it") }
                            }
                        }
                    }
                    if (matches.isNotEmpty()) {
                        candidates++
                        @Suppress("DEPRECATION")
                        appendLine("CANDIDATE package=$packageName versionName=${info.versionName} versionCode=${info.versionCode} system=${app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0}")
                        appendLine("  sourceDir=${app.sourceDir} splitSourceDirs=${app.splitSourceDirs.orEmpty().joinToString()} nativeLibraryDir=${app.nativeLibraryDir}")
                        matches.forEach { appendLine("  MATCH $it") }
                        for ((index, path) in sources.withIndex()) {
                            val kind = if (app.sourceDir != null && index == 0) "base" else "split-${if (app.sourceDir == null) index + 1 else index}"
                            copy(File(path), "Candidate package=$packageName $kind", "apple-discovery-$packageName-$kind.apk")
                        }
                    }
                    appendLine("Package inventory index=$packageIndex package=$packageName matching=${matches.isNotEmpty()}")
                }
            }
        }
        appendLine("Candidate installed packages=$candidates.")
        for ((index, entry) in directories.entries.withIndex()) {
            attempt("Native filename inventory ${entry.key}") {
                val directory = AppleStackExport.safeFile(File(entry.key))
                appendLine("Native directory index=$index path=$directory origins=${entry.value.joinToString()}")
                check(directory.isDirectory && directory.canRead()) { "Directory unavailable/unreadable" }
                val names = checkNotNull(directory.list()) { "Directory filename enumeration failed" }.sorted()
                appendLine("Native directory filenames=${names.size}; filenames only, no ELF/string scan or recursion.")
                for (name in names.filter { relevant(it, nativeTerms) }) {
                    appendLine("NATIVE CANDIDATE directory=$directory filename=$name")
                    attempt("Native candidate $directory/$name") {
                        check(name.matches(Regex("[A-Za-z0-9_.+-]+"))) { "Unsafe native filename" }
                        val file = AppleStackExport.safeFile(File(directory, name))
                        check(file.parentFile == directory) { "Symlink target leaves inventoried native directory: $file" }
                        copy(file, "Native candidate source=$file", "apple-discovery-native-$index-$name")
                    }
                }
            }
        }
        appendLine("Exported ordinary source files=${copied.size}. SUCCESS lines contain exact source/destination, bytes and SHA-256; partial failures remain above.")
        append("STOP after inventory/export. Supply files/report for local static inspection. No transport or Phase 3C.")
    }

    private fun relevant(value: String, terms: List<String> = packageTerms): Boolean =
        terms.any { value.contains(it, ignoreCase = true) }

    private fun componentMatches(info: PackageInfo): List<String> = buildList {
        fun inspect(kind: String, items: Array<out ComponentInfo>?) {
            items.orEmpty().forEach { component ->
                if (relevant(component.name.orEmpty())) {
                    val permission = when (component) {
                        is android.content.pm.ServiceInfo -> component.permission
                        is android.content.pm.ActivityInfo -> component.permission
                        is android.content.pm.ProviderInfo ->
                            "read=${component.readPermission} write=${component.writePermission} authority=${component.authority}"
                        else -> null
                    }
                    add("$kind=${component.name} exported=${component.exported} enabled=${component.enabled} permission=${permission ?: "none"}")
                }
            }
        }
        inspect("service", info.services)
        inspect("receiver", info.receivers)
        inspect("provider", info.providers)
        inspect("activity", info.activities)
    }

    internal fun manifestMatches(parser: XmlPullParser): List<String> {
        val results = linkedSetOf<String>()
        var component: String? = null
        var kind: String? = null
        var events = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            check(++events <= 100000) { "Manifest parser exceeded 100000 events" }
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (parser.name in listOf("application", "service", "receiver", "provider", "activity", "activity-alias")) {
                    kind = parser.name
                    component = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                    if (relevant(component.orEmpty())) results.add("manifest $kind=$component")
                }
                if (parser.name == "action") {
                    val action = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                    if (relevant(action.orEmpty()) || relevant(component.orEmpty())) {
                        results.add("manifest $kind=$component action=$action")
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == kind) {
                component = null
                kind = null
            }
            parser.next()
        }
        return results.toList()
    }
}
