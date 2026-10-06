package com.shilapi.xcertplay.transport

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.Closeable
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One manually requested cache sample. No callback registration or controller commands. */
class NForetekBluetoothCacheStatus(
    context: Context,
    private val onUpdate: (String) -> Unit,
    private val resolve: (Context, IBinder) -> CacheInterface = ::resolveInstalledInterface,
) : Closeable {
    class CacheInterface(val type: Class<*>, val instance: Any) {
        init {
            check(type.isInterface && Modifier.isPublic(type.modifiers) && type.isInstance(instance)) {
                "Resolved object does not implement the public vendor interface"
            }
            GETTERS.forEach { (name, resultType) ->
                val method = type.getMethod(name)
                check(method.returnType == resultType && !Modifier.isStatic(method.modifiers)) {
                    "Unexpected signature for $name"
                }
            }
        }

        fun read(name: String): Any? {
            check(name in GETTERS) { "Not an approved cache getter: $name" }
            return type.getMethod(name).invoke(instance)
        }
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val lines = mutableListOf<String>()
    private var closed = false
    private var started = false
    private var finished = false
    private var bindRegistered = false
    private var connected = false
    @Volatile private var report = "Phase 3B.3 cache-only vendor Bluetooth status: not sampled"
    private val timeout = Runnable { synchronized(this) {
        if (!finished && !closed) finish("TIMEOUT after 5 seconds; outstanding vendor IPC cannot be cancelled; no further getters scheduled")
    } }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            synchronized(this@NForetekBluetoothCacheStatus) {
                if (finished || closed || connected) return
                connected = true
                if (name != COMPONENT || binder == null) {
                    finish("FAIL ${if (binder == null) "null Binder" else "unexpected component $name"}")
                    return
                }
                add("Bind connected=true; Binder class=${binder.javaClass.name}")
                if (!ipcOutstanding.compareAndSet(false, true)) {
                    finish("FAIL previous vendor IPC remains outstanding; no interface/getter access")
                    return
                }
                worker.execute {
                    try {
                        if (cancelled()) return@execute
                        val descriptor = binder.interfaceDescriptor
                        record("Binder descriptor=$descriptor")
                        check(descriptor == DESCRIPTOR) { "Unexpected Binder descriptor; no vendor interface invoked" }
                        if (cancelled()) return@execute
                        val api = resolve(app, binder)
                        record("Interface resolved=${api.type.name}; implementation=${api.instance.javaClass.name}; genuine installed Stub.asInterface")
                        for ((getter, _) in GETTERS) {
                            if (cancelled()) return@execute
                            try {
                                val value = api.read(getter)
                                record("$getter raw=${value ?: "<null>"}; ${interpret(getter, value)}")
                            } catch (error: Exception) {
                                record(failure(getter, error))
                            } catch (error: LinkageError) {
                                record(failure(getter, error))
                            }
                        }
                        main.post { synchronized(this@NForetekBluetoothCacheStatus) {
                            if (!finished && !closed) finish("Cache sampling finished; individual FAIL lines indicate incomplete values, not transport success")
                        } }
                    } catch (error: Exception) {
                        failFromWorker(error)
                    } catch (error: LinkageError) {
                        failFromWorker(error)
                    } finally {
                        ipcOutstanding.set(false)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = disconnected("service disconnected/crashed: $name")
        override fun onNullBinding(name: ComponentName) = disconnected("null binding: $name")
        override fun onBindingDied(name: ComponentName) = disconnected("binding died: $name")
    }

    fun diagnosticReport(): String = report
    @Synchronized fun isComplete(): Boolean = finished && !bindRegistered

    /** UI thread, only after the operator's explicit button press. Instances are one-shot. */
    @Synchronized fun start() {
        if (closed || started) return
        started = true
        add("Only ${COMPONENT.flattenToString()}; flags=0, no auto-create/start/stop.")
        add("Five cache getters only. No callbacks, paired-device query, scan, SPP, connection, payload, authentication or CarPlay.")
        add("Cached values may be stale. Vendor onDestroy can affect shared Bluetooth; flags=0 does not eliminate vendor lifecycle risk.")
        if (ipcOutstanding.get()) {
            finish("FAIL previous vendor IPC remains outstanding; no new bind")
            return
        }
        try {
            val pm = app.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getServiceInfo(COMPONENT, PackageManager.GET_DISABLED_COMPONENTS)
            check(info.packageName == COMPONENT.packageName && info.name == COMPONENT.className) { "Unexpected service metadata" }
            val componentSetting = pm.getComponentEnabledSetting(COMPONENT)
            val appSetting = pm.getApplicationEnabledSetting(COMPONENT.packageName)
            val componentEnabled = componentSetting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (componentSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled)
            val appEnabled = appSetting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (appSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.applicationInfo?.enabled == true)
            check(info.exported && componentEnabled && appEnabled) { "Service/application not exported/enabled" }
            check(info.permission.isNullOrEmpty() ||
                pm.checkPermission(info.permission, app.packageName) == PackageManager.PERMISSION_GRANTED) { "Service permission not granted" }
            main.postDelayed(timeout, TIMEOUT_MS)
            val accepted = app.bindService(Intent().setComponent(COMPONENT), connection, 0)
            bindRegistered = true
            add("bindService returned=$accepted; flags=0")
            if (finished) unbind()
            else if (!accepted) finish("FAIL bind rejected/not running/inaccessible")
        } catch (error: Exception) {
            finish(failure("Bind", error))
        } catch (error: LinkageError) {
            finish(failure("Bind", error))
        }
    }

    private fun disconnected(reason: String) = synchronized(this) {
        if (!finished && !closed) finish("FAIL $reason")
    }

    private fun failFromWorker(error: Throwable) {
        val text = failure("Interface/sample", error)
        main.post { synchronized(this) { if (!finished && !closed) finish(text) } }
    }

    @Synchronized private fun cancelled() = finished || closed
    private fun record(text: String) {
        main.post { synchronized(this) { if (!finished && !closed) add(text) } }
    }

    private fun add(text: String) {
        lines.add(text)
        report = "Phase 3B.3 cache-only vendor Bluetooth status\n" + lines.joinToString("\n")
        Log.i(TAG, text)
        onUpdate(report)
    }

    private fun finish(text: String) {
        if (finished) return
        finished = true
        main.removeCallbacks(timeout)
        add(text)
        unbind()
        worker.shutdown()
    }

    private fun unbind() {
        if (!bindRegistered) {
            add("Unbind: no registered binding")
            return
        }
        bindRegistered = false
        try {
            app.unbindService(connection)
            add("Unbind SUCCESS; no stopService sent")
        } catch (error: Exception) { add(failure("Unbind", error)) }
        catch (error: LinkageError) { add(failure("Unbind", error)) }
    }

    @Synchronized override fun close() {
        if (closed) return
        if (started && !finished) finish("Cancelled: activity paused/destroyed; no further getters")
        closed = true
        worker.shutdown()
    }

    companion object {
        const val TAG = "DiPlayPhase3B3Device"
        const val DESCRIPTOR = "com.nforetek.bt.aidl.INfCommandBluetooth"
        const val TIMEOUT_MS = 5000L
        val COMPONENT = ComponentName("com.nforetek.bt", "com.nforetek.bt.service.NfServiceBluetooth")
        private val ipcOutstanding = AtomicBoolean()
        private val GETTERS = linkedMapOf(
            "getBtLocalName" to String::class.java,
            "getBtLocalAddress" to String::class.java,
            "isBtEnabled" to Boolean::class.javaPrimitiveType,
            "getBtState" to Int::class.javaPrimitiveType,
            "getNfServiceVersionName" to String::class.java,
        )
        private const val VERIFIED_APK_SHA256 = "aff5a4de709f95392e92158a348ad0c9a584d616d1e07203da307437ee009674"

        fun interpret(name: String, value: Any?): String = when (name) {
            "getBtState" -> "interpreted state=${when (value) { 302 -> "ON"; 300 -> "OFF"; else -> "UNKNOWN (not 302/300)" }}"
            "isBtEnabled" -> "cached enabled=${when (value) { true -> "YES"; false -> "NO"; else -> "UNKNOWN" }}"
            "getBtLocalName" -> "local name=${if (value is String && value.isNotBlank()) value else "unavailable/empty"}"
            "getBtLocalAddress" -> "local address=${if (value is String && value.isNotBlank() && value != "00:00:00:00:00:00") value else "unavailable/empty/zero"}"
            else -> "service version=${if (value is String && value.isNotBlank()) value else "unavailable/empty"}"
        }

        private fun failure(stage: String, error: Throwable): String {
            val cause = if (error is InvocationTargetException) error.targetException else error
            Log.w(TAG, "$stage failed", cause)
            return "$stage FAIL ${cause.javaClass.simpleName}: ${cause.message}"
        }

        @Suppress("DEPRECATION")
        private fun resolveInstalledInterface(context: Context, binder: IBinder): CacheInterface {
            val info = context.packageManager.getApplicationInfo(COMPONENT.packageName, 0)
            check(info.splitSourceDirs.isNullOrEmpty()) { "Unvalidated split vendor APK; refusing execution" }
            val source = File(checkNotNull(info.sourceDir) { "Installed vendor sourceDir unavailable" })
            check(source.isFile && source.canRead()) { "Installed vendor APK unreadable" }
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(hash == VERIFIED_APK_SHA256) { "Installed vendor APK differs from statically audited version: SHA-256=$hash; no asInterface/getters invoked" }
            val cache = File(context.codeCacheDir, "nforetek-cache-status")
            check(cache.isDirectory || cache.mkdirs()) { "Private class-loader cache unavailable" }
            val loader = DexClassLoader(source.absolutePath, cache.absolutePath, null, context.classLoader)
            val type = Class.forName(DESCRIPTOR, false, loader)
            check(IInterface::class.java.isAssignableFrom(type)) { "Vendor command is not IInterface" }
            val stub = Class.forName("$DESCRIPTOR\$Stub", false, loader)
            check(Modifier.isPublic(stub.modifiers) && type.isAssignableFrom(stub)) { "Invalid public vendor Stub" }
            check(type.classLoader === loader && stub.classLoader === loader) { "Vendor class shadowed by unaudited parent loader" }
            val method = stub.getMethod("asInterface", IBinder::class.java)
            check(Modifier.isStatic(method.modifiers) && method.returnType == type) { "Invalid Stub.asInterface signature" }
            val instance = checkNotNull(method.invoke(null, binder)) { "Stub.asInterface returned null" }
            return CacheInterface(type, instance)
        }
    }
}
