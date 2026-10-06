package com.shilapi.xcertplay.transport

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import org.xmlpull.v1.XmlPullParser

/** Public metadata only: never obtains a Bluetooth adapter or invokes vendor components. */
class VehicleBluetoothInvestigation(context: Context, private val onUpdate: (String) -> Unit) : Closeable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val lines = mutableListOf<String>()
    private var registered = false
    private var starting = false
    @Volatile private var closed = false
    @Volatile private var generation = 0
    private var startedAt = 0L
    private var events = 0
    private var status = "not started"
    private var latestFailure = "none"
    @Volatile private var report = "Phase 3B.1 Vehicle Bluetooth investigation: not started"
    private var timeout: Runnable? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            synchronized(this@VehicleBluetoothInvestigation) {
                if (!registered || closed) return
                attempt("Broadcast metadata") {
                    if (events >= MAX_EVENTS) return@attempt
                    events++
                    val keys = intent.extras?.keySet()?.sorted().orEmpty()
                    val state = if (intent.action == "android.bluetooth.adapter.action.STATE_CHANGED")
                        " state=${intent.getIntExtra("android.bluetooth.adapter.extra.STATE", -1)}" +
                            " previous=${intent.getIntExtra("android.bluetooth.adapter.extra.PREVIOUS_STATE", -1)}"
                    else ""
                    add("Broadcast +${SystemClock.elapsedRealtime() - startedAt}ms action=${intent.action}$state extraKeys=$keys (other values omitted)")
                    publish()
                }
            }
        }
    }

    fun diagnosticReport(): String = report

    @Synchronized fun start() {
        if (closed || starting || registered) return
        starting = true
        val run = ++generation
        lines.clear()
        events = 0
        status = "Inventory running; broadcasts not registered yet"
        latestFailure = "none"
        add("Read-only public metadata; no binding, commands, adapter lookup, scan, RFCOMM or iAP2.")
        add("Hardware confirmed: vehicle GEELY_BT active; Android CAR_BT disabled, address 00:00:00:00:00:00, zero bonded devices. Current vehicle state is not inferred.")
        add("API ${Build.VERSION.SDK_INT}; package visibility and permissions can restrict results. Empty inventory is not proof of absence.")
        publish()
        worker.execute {
            if (closed || generation != run) return@execute
            val actions = linkedSetOf(
                "android.bluetooth.adapter.action.STATE_CHANGED",
                "android.bluetooth.adapter.action.LOCAL_NAME_CHANGED",
                "android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.device.action.BOND_STATE_CHANGED",
                "android.bluetooth.device.action.ACL_CONNECTED",
                "android.bluetooth.device.action.ACL_DISCONNECTED",
                "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
            )
            attempt("Service inventory") { inventoryServices() }
            inventoryPackages(actions)
            inventoryProperties()
            publish()
            main.post {
                synchronized(this) {
                    if (!closed && generation == run) observe(actions)
                }
            }
        }
    }

    private fun inventoryServices() {
        add("--- Public system-service availability (no Binder listing/private ServiceManager) ---")
        for (name in listOf(Context.BLUETOOTH_SERVICE, Context.ACTIVITY_SERVICE, Context.AUDIO_SERVICE, Context.TELEPHONY_SERVICE)) {
            attempt("System service $name") {
                val service = app.getSystemService(name)
                add("System service $name: ${service?.javaClass?.name ?: "unavailable/null"} (not invoked)")
            }
        }
        attempt("Running services") {
            val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            checkNotNull(manager) { "ActivityManager unavailable" }
            @Suppress("DEPRECATION")
            val running = checkNotNull(manager.getRunningServices(200)) { "Running-service list unavailable" }
            val matches = running.filter { matchesName("${it.service.flattenToString()} ${it.process}") }
            add("Running services returned=${running.size} matching=${matches.size}; limit=200; Android 8+ normally restricts this to own services")
            matches.forEach { add("Running ${it.service.flattenToString()} process=${it.process} pid=${it.pid}") }
        }
    }

    private fun inventoryPackages(actions: MutableSet<String>) {
        add("--- Visible packages and exported components; receiver actions from public APK manifest metadata ---")
        attempt("Package enumeration") {
            val pm = checkNotNull(app.packageManager) { "PackageManager unavailable" }
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or PackageManager.GET_DISABLED_COMPONENTS)
            var matched = 0
            packages.sortedBy { it.packageName }.forEach { pkg ->
                if (closed) return@forEach
                val components = pkg.services.orEmpty().toList() + pkg.receivers.orEmpty().toList() + pkg.providers.orEmpty().toList()
                if (!matchesName(pkg.packageName) && !matchesName(pkg.applicationInfo?.name.orEmpty()) &&
                    components.none { matchesName(it.name) }) return@forEach
                matched++
                attempt("Package ${pkg.packageName}") {
                    val info = checkNotNull(pkg.applicationInfo) { "Application metadata unavailable" }
                    add("Package ${pkg.packageName} label=${pm.getApplicationLabel(info)} applicationClass=${info.name} version=${pkg.versionName} uid=${info.uid} enabled=${info.enabled} apk=${info.sourceDir} nativeLibraryDir=${info.nativeLibraryDir}")
                    pkg.services.orEmpty().filter { it.exported }.forEach { component("Service", it, "permission=${it.permission}") }
                    pkg.receivers.orEmpty().filter { it.exported }.forEach { component("Receiver", it, "permission=${it.permission}") }
                    pkg.providers.orEmpty().filter { it.exported }.forEach {
                        component("Provider", it, "authority=${it.authority} readPermission=${it.readPermission} writePermission=${it.writePermission}")
                    }
                    val exported = pkg.receivers.orEmpty().filter { it.exported && it.enabled }.map { it.name }.toSet()
                    if (exported.isNotEmpty()) attempt("Manifest receiver actions ${pkg.packageName}") {
                        val resources = pm.getResourcesForApplication(info)
                        resources.assets.openXmlResourceParser("AndroidManifest.xml").use { xml ->
                            receiverActions(xml, pkg.packageName, exported).forEach { (receiverName, action) ->
                                add("Receiver action ${pkg.packageName}/$receiverName: $action")
                                if (matchesName(action)) {
                                    if (actions.size < MAX_ACTIONS) actions.add(action)
                                    else add("Action subscription cap reached; not observed: $action")
                                }
                            }
                        }
                    }
                }
            }
            add("Visible packages=${packages.size}; matched=$matched. Exported does not imply permission to access; no component invoked.")
        }
    }

    private fun component(kind: String, info: ComponentInfo, detail: String) =
        add("$kind ${info.packageName}/${info.name} enabled=${info.enabled} process=${info.processName} exported=${info.exported} $detail")

    private fun inventoryProperties() {
        add("--- Ordinary readable property files; no getprop process or hidden SystemProperties API ---")
        for (path in listOf("/system/build.prop", "/vendor/build.prop", "/system/vendor/build.prop")) {
            attempt("Property file $path") {
                val file = File(path)
                check(file.isFile && file.canRead()) { "Unavailable or unreadable by ordinary app" }
                var found = 0
                file.bufferedReader().useLines { contents ->
                    contents.take(10000).forEach { line ->
                        val key = line.substringBefore('=').trim()
                        if ('=' in line && safePropertyKey(key)) {
                            found++
                            add("Property $path $key=${line.substringAfter('=').take(256)}")
                        }
                    }
                }
                add("Property file $path: safe relevant entries=$found; live property state cannot be enumerated via public Android API")
            }
        }
    }

    private fun observe(actions: Set<String>) {
        starting = false
        attempt("Broadcast registration") {
            val filter = IntentFilter().apply { actions.forEach { addAction(it) } }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter)
            registered = true
            startedAt = SystemClock.elapsedRealtime()
            status = "Observing for 30 seconds; toggle Geely Bluetooth now"
            add("--- Observing for 30 seconds; toggle Geely Bluetooth in vehicle UI now ---")
            add("Subscribed actions (${actions.size}, max $MAX_ACTIONS): ${actions.joinToString()}")
            add("No wildcard Android broadcast subscription exists; undeclared/protected/vendor-private events may be invisible. Extra values omitted except standard adapter state integers.")
            val end = Runnable { synchronized(this) { stopObservation("30-second window completed") } }
            timeout = end
            main.postDelayed(end, WINDOW_MS)
        }
        if (!registered) {
            status = "Observation not started: receiver registration failed; inventory remains exportable"
            add(status)
        }
        Log.i(TAG, "Manual inventory finished; observation registered=$registered actions=${actions.size}")
        publish()
    }

    private fun stopObservation(reason: String) {
        timeout?.let(main::removeCallbacks)
        timeout = null
        if (registered) {
            registered = false
            attempt("Receiver cleanup") { app.unregisterReceiver(receiver) }
        }
        status = "Observation stopped: $reason"
        add("$status; events=$events (cap=$MAX_EVENTS).")
        Log.i(TAG, "Observation stopped: $reason; events=$events")
        publish()
    }

    private fun attempt(stage: String, action: () -> Unit) {
        try { action() }
        catch (error: Exception) { failure(stage, error) }
        catch (error: LinkageError) { failure(stage, error) }
    }

    @Synchronized private fun failure(stage: String, error: Throwable) {
        latestFailure = "$stage FAIL ${error.javaClass.simpleName}: ${error.message}"
        add(latestFailure)
        Log.w(TAG, "$stage failed", error)
    }

    @Synchronized private fun add(line: String) {
        if (closed) return
        if (lines.size < MAX_LINES) lines.add(line)
        else if (lines.size == MAX_LINES) lines.add("Inventory truncated at $MAX_LINES lines.")
    }

    @Synchronized private fun publish() {
        if (closed) return
        report = "Phase 3B.1: $status\nObserved events=$events (cap=$MAX_EVENTS); latest failure=$latestFailure\n" +
            lines.joinToString("\n")
        if (!closed) onUpdate(report)
    }

    @Synchronized override fun close() {
        if (closed) return
        stopObservation("activity destroyed")
        closed = true
        generation++
        worker.shutdownNow()
    }

    companion object {
        const val TAG = "DiPlayPhase3B1Device"
        const val WINDOW_MS = 30_000L
        private const val MAX_EVENTS = 200
        private const val MAX_LINES = 4000
        private const val MAX_ACTIONS = 128
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        fun receiverActions(xml: XmlPullParser, packageName: String, exported: Set<String>): List<Pair<String, String>> {
            val result = mutableListOf<Pair<String, String>>()
            var receiverName: String? = null
            var event = xml.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && xml.name == "receiver") {
                    val name = xml.getAttributeValue(ANDROID_NS, "name")
                    receiverName = name?.let {
                        when {
                            it.startsWith(".") -> packageName + it
                            '.' !in it -> "$packageName.$it"
                            else -> it
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && xml.name == "receiver") receiverName = null
                else if (event == XmlPullParser.START_TAG && xml.name == "action" && receiverName in exported) {
                    val action = xml.getAttributeValue(ANDROID_NS, "name")
                    if (action != null && receiverName != null) result.add(receiverName to action)
                }
                event = xml.next()
            }
            return result
        }
        fun matchesName(name: String): Boolean {
            val lower = name.lowercase(Locale.ROOT)
            return listOf("bluetooth", "bt", "geely", "ecarx", "smartplatform", "mcu", "car", "phone", "handsfree", "hfp").any { it in lower }
        }
        fun safePropertyKey(key: String): Boolean =
            key in setOf("ro.product.brand", "ro.product.manufacturer", "ro.product.model", "ro.product.device",
                "ro.product.board", "ro.hardware", "ro.build.display.id", "ro.build.version.release", "ro.build.version.sdk") ||
                key.matches(Regex("(ro|persist)\\.(bluetooth|bt|geely|ecarx|smartplatform|mcu)\\.(name|version|platform|chip|type|support|enabled)"))
    }
}
