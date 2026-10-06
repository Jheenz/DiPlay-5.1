package com.shilapi.xcertplay.transport

import android.content.Context
import android.content.pm.ApplicationInfo
import dalvik.system.DexClassLoader
import dalvik.system.DexFile
import java.io.File
import java.lang.reflect.Modifier

/** Enumerates metadata without initializing classes, constructing proxies or invoking methods. */
object NForetekApkInterfaces {
    @Suppress("DEPRECATION")
    fun inspect(context: Context, info: ApplicationInfo, output: (String) -> Unit, cancelled: () -> Boolean) {
        val paths = (listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty() +
            info.sharedLibraryFiles.orEmpty().filter { listOf("nforetek", "gocsdk", "bluetooth", "geely", "neusoft").any { term ->
                term in it.lowercase(java.util.Locale.ROOT)
            } }).distinct()
        check(paths.isNotEmpty()) { "No readable APK path reported" }
        val candidates = linkedSetOf<String>()
        val readablePaths = mutableListOf<String>()
        for (path in paths) {
            if (cancelled()) return
            try {
                val file = File(path)
                check(file.isFile && file.canRead()) { "APK/library unreadable: $path" }
                readablePaths.add(path)
                output("APK/library $path; nativeLibraryDir=${info.nativeLibraryDir}; no native library loaded")
                val dex = DexFile(file)
                try {
                    val entries = dex.entries()
                    var count = 0
                    while (entries.hasMoreElements() && !cancelled()) {
                        val name = entries.nextElement()
                        if (!relevantClass(name)) continue
                        count++
                        if (count == 251) {
                            output("Relevant DEX class list truncated at 250 for $path")
                        }
                        if (count <= 250) output("DEX class $name")
                        if (interfaceCandidate(name) && candidates.size < 80) candidates.add(name)
                    }
                } finally { dex.close() }
            } catch (error: Exception) {
                output("DEX metadata $path FAIL ${error.javaClass.simpleName}: ${error.message}")
                android.util.Log.w(NForetekServiceDiagnostics.TAG, "DEX metadata unavailable: $path", error)
            } catch (error: LinkageError) {
                output("DEX metadata $path FAIL ${error.javaClass.simpleName}: ${error.message}")
                android.util.Log.w(NForetekServiceDiagnostics.TAG, "DEX linkage unavailable: $path", error)
            }
        }
        output("Interface candidates=${candidates.size} (cap=80). Class loading uses initialize=false; no DESCRIPTOR field values, asInterface, constructor or vendor methods invoked.")
        if (candidates.isEmpty()) {
            output("No interface candidates found in ART-reported DEX entries; absent classes may live in inaccessible framework libraries.")
            return
        }
        val directory = File(context.codeCacheDir, "nforetek-metadata").apply {
            check(isDirectory || mkdirs()) { "Private metadata cache unavailable" }
        }
        val loader = DexClassLoader(readablePaths.joinToString(File.pathSeparator), directory.absolutePath, null, context.classLoader)
        for (name in candidates) {
            if (cancelled()) return
            inspectClass(name, loader, output)
        }
        output("Class visibility is not permission to invoke a remote interface. Spp/SPP names suggest serial profile only; raw iAP2 transport compatibility is unproven.")
    }

    fun inspectClass(name: String, loader: ClassLoader, output: (String) -> Unit) {
        try {
            val type = Class.forName(name, false, loader)
            output("Loadable $name public=${Modifier.isPublic(type.modifiers)} interface=${type.isInterface} " +
                "super=${type.superclass?.name} interfaces=${type.interfaces.joinToString { it.name }}")
            val methods = type.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.sortedBy { it.name }
            methods.take(120).forEach { method ->
                output("Public method $name.${method.name}(${method.parameterTypes.joinToString { it.name }}): ${method.returnType.name}; metadata only, not invoked")
            }
            if (methods.size > 120) output("Public method list truncated at 120 for $name")
        } catch (error: Exception) {
            output("Class metadata $name FAIL ${error.javaClass.simpleName}: ${error.message}")
            android.util.Log.w(NForetekServiceDiagnostics.TAG, "Class metadata unavailable: $name", error)
        } catch (error: LinkageError) {
            output("Class metadata $name FAIL ${error.javaClass.simpleName}: ${error.message}")
            android.util.Log.w(NForetekServiceDiagnostics.TAG, "Class linkage unavailable: $name", error)
        }
    }

    fun relevantClass(name: String): Boolean =
        name.startsWith("com.nforetek.") ||
            (name.startsWith("com.neusoft.") && listOf("bluetooth", "btmanager", "aidl", ".nf.", "spp").any { it in name.lowercase(java.util.Locale.ROOT) })

    fun interfaceCandidate(name: String): Boolean =
        relevantClass(name) && (name.contains(".aidl.") || name.endsWith("\$Stub") || name.endsWith("\$Stub\$Proxy") ||
            name.substringAfterLast('.').startsWith("INf") || name.substringAfterLast('.').startsWith("IBt"))
}
