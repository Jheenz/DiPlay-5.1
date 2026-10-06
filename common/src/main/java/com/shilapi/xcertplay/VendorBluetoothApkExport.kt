package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal object VendorBluetoothApkExport {
    private const val TAG = "DiPlayPhase3B2Device"
    internal const val CONTROL_NAMESPACE = "com.neusoft.shockwave.setting.btphonelogic"
    internal const val CONTROL_SERVICE = "$CONTROL_NAMESPACE.service.BluetoothControlService"
    private val candidateTerms = listOf("neusoft", "shockwave", "btphone", "bluetooth", "setting", "vehicle")
    private val packages = linkedMapOf(
        "com.neusoft.geely.btphone.nf" to "btphoneNF.apk",
        "com.nforetek.bt" to "Bluetooth-GocBtAPI.apk",
    )

    fun export(context: Context): String = buildString {
        appendLine("Vendor APK export: manual read-only source copy; no service/Binder/Bluetooth operations.")
        for ((packageName, fileName) in packages) {
            try {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                val source = File(checkNotNull(info.sourceDir) { "ApplicationInfo.sourceDir unavailable" })
                check(source.isFile && source.canRead()) { "Installed APK unreadable: ${source.absolutePath}" }
                appendLine("$packageName source=${source.absolutePath}")
                if (!info.splitSourceDirs.isNullOrEmpty()) appendLine("WARNING: installed split APKs present; only sourceDir/base APK requested and exported.")
                val saved = saveApk(context, source, fileName, this)
                appendLine("$packageName SUCCESS $saved")
                Log.i(TAG, "APK export $packageName SUCCESS $saved")
            } catch (error: Exception) {
                appendLine("$packageName FAIL ${error.javaClass.simpleName}: ${error.message}")
                Log.w(TAG, "APK export failed for $packageName", error)
            } catch (error: LinkageError) {
                appendLine("$packageName FAIL ${error.javaClass.simpleName}: ${error.message}")
                Log.w(TAG, "APK export vendor API unavailable for $packageName", error)
            }
        }
        append("Supply both verified APKs locally before Phase 3B.3. No SPP service initialization or callback registration implemented.")
    }

    fun exportControlImplementation(context: Context): String = buildString {
        appendLine("Stock Geely Bluetooth control APK search/export: manual metadata and file copy only.")
        appendLine("Target service=$CONTROL_SERVICE namespace=$CONTROL_NAMESPACE")
        appendLine("No service start/bind, broadcasts, callbacks, Bluetooth APIs or controller/device-node access.")
        val exact = linkedMapOf<String, MutableSet<String>>()
        val candidates = linkedMapOf<String, MutableSet<String>>()
        var inventorySucceeded = false
        fun record(packageName: String, description: String, isExact: Boolean) {
            val results = if (isExact) exact else candidates
            results.getOrPut(packageName) { linkedSetOf() }.add(description)
        }
        try {
            @Suppress("DEPRECATION")
            val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
                PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS or PackageManager.GET_DISABLED_COMPONENTS
            @Suppress("DEPRECATION")
            val installed = context.packageManager.getInstalledPackages(flags)
            inventorySucceeded = true
            appendLine("Visible installed packages=${installed.size}; visibility restrictions may apply on newer Android.")
            for (info in installed.sortedBy { it.packageName }) {
                try {
                    val packageName = checkNotNull(info.packageName) { "Package name unavailable" }
                    val names = manifestNames(info)
                    for (entry in names) {
                        if (inControlNamespace(entry.second)) {
                            record(packageName, "${entry.first}=${entry.second}", true)
                        } else if (candidateTerms.any { entry.second.contains(it, ignoreCase = true) }) {
                            record(packageName, "${entry.first}=${entry.second}", false)
                        }
                    }
                } catch (error: Exception) {
                    reportFailure("Package metadata ${info.packageName}", error, this)
                } catch (error: LinkageError) {
                    reportFailure("Package metadata ${info.packageName}", error, this)
                }
            }
        } catch (error: Exception) {
            reportFailure("Installed package enumeration", error, this)
        } catch (error: LinkageError) {
            reportFailure("Installed package enumeration", error, this)
        }
        try {
            @Suppress("DEPRECATION")
            val services = context.packageManager.queryIntentServices(
                Intent(CONTROL_SERVICE), PackageManager.GET_DISABLED_COMPONENTS)
            appendLine("Exact service-action metadata matches=${services.size}")
            for (match in services) {
                val service = match.serviceInfo
                if (service == null || service.packageName.isNullOrEmpty() || service.name.isNullOrEmpty()) {
                    appendLine("Service-action metadata FAIL: missing service/package/class.")
                    Log.w(TAG, "Control service-action metadata missing service/package/class")
                } else {
                    record(service.packageName, "service=${service.name} action=$CONTROL_SERVICE " +
                        "enabled=${service.enabled} exported=${service.exported} permission=${service.permission ?: "none"}", true)
                }
            }
        } catch (error: Exception) {
            reportFailure("Exact service-action query", error, this)
        } catch (error: LinkageError) {
            reportFailure("Exact service-action query", error, this)
        }
        if (exact.isEmpty()) {
            appendLine("No exact service/namespace/action metadata match found${if (!inventorySucceeded) " (inventory failed)" else ""}.")
            appendLine("Candidate packages=${candidates.size}; candidates are NOT exported or launched.")
            for ((packageName, names) in candidates) {
                appendLine("CANDIDATE package=$packageName")
                names.forEach { appendLine("  $it") }
                try {
                    @Suppress("DEPRECATION")
                    val app = context.packageManager.getApplicationInfo(packageName, 0)
                    appendLine("  source=${app.sourceDir ?: "unavailable"}")
                    app.splitSourceDirs?.forEach { appendLine("  splitSource=$it") }
                } catch (error: Exception) {
                    reportFailure("Candidate source $packageName", error, this)
                } catch (error: LinkageError) {
                    reportFailure("Candidate source $packageName", error, this)
                }
            }
            appendLine("Manifest inspection cannot prove absence of an internal DEX-only class or a restricted package. Save this report for candidate selection/static inspection.")
        } else {
            appendLine("Exact owning packages=${exact.size}")
            for ((packageName, names) in exact) {
                appendLine("MATCH package=$packageName")
                names.forEach { appendLine("  $it") }
                try {
                    check(packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))) { "Unsafe package filename" }
                    @Suppress("DEPRECATION")
                    val app = context.packageManager.getApplicationInfo(packageName, 0)
                    val sources = listOf(checkNotNull(app.sourceDir) { "ApplicationInfo.sourceDir unavailable" }) +
                        app.splitSourceDirs.orEmpty().toList()
                    for ((index, path) in sources.withIndex()) {
                        val kind = if (index == 0) "base" else "split-$index"
                        try {
                            val source = File(path)
                            check(source.isFile && source.canRead()) { "Installed APK unreadable: ${source.absolutePath}" }
                            appendLine("  $kind source=${source.absolutePath}")
                            val saved = saveApk(context, source, "$packageName-$kind.apk", this)
                            appendLine("$packageName $kind SUCCESS $saved")
                            Log.i(TAG, "Control APK export $packageName $kind SUCCESS $saved")
                        } catch (error: Exception) {
                            reportFailure("$packageName $kind export", error, this)
                        } catch (error: LinkageError) {
                            reportFailure("$packageName $kind export", error, this)
                        }
                    }
                } catch (error: Exception) {
                    reportFailure("Exact package $packageName", error, this)
                } catch (error: LinkageError) {
                    reportFailure("Exact package $packageName", error, this)
                }
            }
        }
        append("STOP after collecting APKs/report. Local static inspection required before any transport or new vendor interaction.")
    }

    private fun manifestNames(info: PackageInfo): List<Pair<String, String>> = buildList {
        add("package" to info.packageName)
        info.applicationInfo?.className?.let { add("application" to componentName(info.packageName, it)) }
        info.services?.forEach { add("service" to componentName(info.packageName, it.name)) }
        info.receivers?.forEach { add("receiver" to componentName(info.packageName, it.name)) }
        info.activities?.forEach { add("activity" to componentName(info.packageName, it.name)) }
        info.providers?.forEach { add("provider" to componentName(info.packageName, it.name)) }
    }

    private fun componentName(packageName: String, name: String): String = when {
        name.startsWith(".") -> packageName + name
        "." !in name -> "$packageName.$name"
        else -> name
    }

    private fun inControlNamespace(name: String): Boolean =
        name == CONTROL_NAMESPACE || name.startsWith("$CONTROL_NAMESPACE.")

    private fun reportFailure(operation: String, error: Throwable, report: StringBuilder) {
        report.appendLine("$operation FAIL ${error.javaClass.simpleName}: ${error.message}")
        Log.w(TAG, "$operation failed", error)
    }

    internal fun saveApk(context: Context, source: File, fileName: String, report: StringBuilder): String {
        if (Build.VERSION.SDK_INT <= 28 && context.packageManager.checkPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE, context.packageName) == PackageManager.PERMISSION_GRANTED) {
            try {
                @Suppress("DEPRECATION")
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DiPlayVendorDump")
                return copyVerified(source, File(directory, fileName))
            } catch (error: Exception) {
                Log.w(TAG, "Public file export failed for $fileName; trying external files", error)
                report.appendLine("Public destination FAIL ${error.javaClass.simpleName}: ${error.message}; trying app external files.")
            }
        } else report.appendLine("Public destination unavailable under current storage permission/API policy; no permission prompt. Using app external files.")
        val external = context.getExternalFilesDir(null) ?: throw IOException("App external files directory unavailable")
        return copyVerified(source, File(File(external, "DiPlayVendorDump"), fileName))
    }

    fun copyVerified(source: File, destination: File): String {
        check(source.canonicalFile != destination.canonicalFile) { "Source and destination must differ" }
        val directory = checkNotNull(destination.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create export directory: $directory" }
        val before = source.length()
        check(before > 0) { "Source file is empty" }
        val temporary = File.createTempFile("vendor-apk-", ".partial", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val copied = source.inputStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        bytes += count
                    }
                    output.fd.sync()
                    bytes
                }
            }
            val hash = digest.digest()
            check(copied == before && source.length() == before && temporary.length() == copied) { "Source size changed or copy incomplete" }
            val verified = MessageDigest.getInstance("SHA-256")
            temporary.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    verified.update(buffer, 0, count)
                }
            }
            check(hash.contentEquals(verified.digest())) { "Copied file SHA-256 mismatch" }
            check(temporary.renameTo(destination)) { "Cannot publish verified file: ${destination.absolutePath}; existing copy was not deleted" }
            return "path=${destination.absolutePath} size=$copied SHA-256=${hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }}"
        } finally {
            if (temporary.exists() && !temporary.delete()) Log.w(TAG, "Cannot clean partial export ${temporary.absolutePath}")
        }
    }
}
