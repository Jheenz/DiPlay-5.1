package com.shilapi.xcertplay.transport

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class NForetekServiceCandidate(val component: ComponentName, val bindEligible: Boolean, val reason: String)

/** Manual metadata and zero-flag binding only; never calls a vendor interface method. */
class NForetekServiceDiagnostics(context: Context, private val onUpdate: (String) -> Unit) : Closeable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val metadataWorker = Executors.newSingleThreadExecutor()
    private val metadataPending = AtomicBoolean()
    private val lines = mutableListOf<String>()
    private var services = emptyList<NForetekServiceCandidate>()
    private var busy = false
    private var closed = false
    private var generation = 0
    private var active: BindAttempt? = null
    private var status = "not inspected"
    @Volatile private var report = "Phase 3B.2 NForetek service diagnostics: not inspected"

    private inner class BindAttempt(val component: ComponentName, val token: Int) {
        var registered = false
        var finished = false
        var metadataRequested = false
        val timeout = Runnable { synchronized(this@NForetekServiceDiagnostics) {
            finish(this, "TIMEOUT: no usable Binder metadata within 5 seconds; service may not be running")
        } }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                synchronized(this@NForetekServiceDiagnostics) {
                    if (finished || closed || active !== this@BindAttempt || metadataRequested) return
                    if (name != component) {
                        finish(this@BindAttempt, "FAIL unexpected component ${name.flattenToString()}")
                        return
                    }
                    if (binder == null) {
                        finish(this@BindAttempt, "FAIL null Binder")
                        return
                    }
                    try {
                        metadataRequested = true
                        add("Connected ${name.flattenToString()}; Binder class=${binder.javaClass.name}; public interfaces=${binder.javaClass.interfaces.joinToString { it.name }}")
                        publish()
                        metadataPending.set(true)
                        metadataWorker.execute {
                            val metadata = try {
                                val descriptor = checkNotNull(binder.interfaceDescriptor) { "Binder descriptor unavailable/null" }
                                check(descriptor.isNotBlank()) { "Binder descriptor empty" }
                                "Binder descriptor=$descriptor; alive=${binder.isBinderAlive}; " +
                                    "local interface metadata=${binder.queryLocalInterface(descriptor)?.javaClass?.name ?: "none (remote proxy or unavailable)"}; " +
                                    "no asInterface/vendor method invoked"
                            } catch (error: Exception) { failureText("Binder metadata", error) }
                            catch (error: LinkageError) { failureText("Binder metadata", error) }
                            finally { metadataPending.set(false) }
                            main.post { synchronized(this@NForetekServiceDiagnostics) {
                                if (!finished && !closed && active === this@BindAttempt && generation == token) {
                                    add(metadata)
                                    finish(this@BindAttempt, if (metadata.contains(" FAIL ")) "FAIL Binder metadata unavailable; unbinding"
                                        else "Connected; metadata captured; immediately unbinding (not transport success)")
                                }
                            } }
                        }
                    } catch (error: Exception) {
                        metadataPending.set(false)
                        finish(this@BindAttempt, failureText("Connected Binder metadata", error))
                    } catch (error: LinkageError) {
                        metadataPending.set(false)
                        finish(this@BindAttempt, failureText("Connected Binder metadata", error))
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                synchronized(this@NForetekServiceDiagnostics) {
                    if (!finished) finish(this@BindAttempt, "FAIL service disconnected/crashed: ${name.flattenToString()}")
                }
            }

            override fun onNullBinding(name: ComponentName) {
                synchronized(this@NForetekServiceDiagnostics) {
                    if (!finished) finish(this@BindAttempt, "FAIL onNullBinding: ${name.flattenToString()}")
                }
            }

            override fun onBindingDied(name: ComponentName) {
                synchronized(this@NForetekServiceDiagnostics) {
                    if (!finished) finish(this@BindAttempt, "FAIL binding died: ${name.flattenToString()}")
                }
            }
        }
    }

    fun diagnosticReport(): String = report
    @Synchronized fun candidates(): List<NForetekServiceCandidate> = services.toList()

    @Synchronized fun inspect() {
        if (closed || busy) return
        busy = true
        val token = ++generation
        services = emptyList()
        lines.clear()
        status = "Inspecting public service/APK metadata"
        add("No adapter lookup, service start, vendor commands, manual Binder transactions, pairing, scan, SPP/RFCOMM, iAP2 or authentication.")
        add("Binding is separately selected by the operator; flags=0, no BIND_AUTO_CREATE. Exported/permission checks predict eligibility, not actual bind success.")
        publish()
        worker.execute {
            val result = mutableListOf<NForetekServiceCandidate>()
            attempt("Package/service inspection") {
                val pm = app.packageManager
                @Suppress("DEPRECATION")
                val packages = pm.getInstalledPackages(PackageManager.GET_SERVICES or PackageManager.GET_DISABLED_COMPONENTS or
                    PackageManager.GET_SHARED_LIBRARY_FILES)
                packages.sortedBy { it.packageName }.forEach { pkg ->
                    if (isClosed(token)) return@forEach
                    val matching = pkg.services.orEmpty().filter { knownService(it.packageName, it.name) }
                    if (matching.isEmpty() && pkg.packageName != NFORETEK_PACKAGE) return@forEach
                    add("Package ${pkg.packageName} version=${pkg.versionName}; visible services=${matching.size}")
                    matching.sortedBy { it.name }.forEach { info ->
                        attempt("Service ${info.name}") {
                            result.add(inspectService(pm, info))
                        }
                    }
                    pkg.applicationInfo?.let { info ->
                        attempt("APK interfaces ${pkg.packageName}") {
                            NForetekApkInterfaces.inspect(app, info, ::add) { isClosed(token) }
                        }
                    } ?: add("APK interfaces ${pkg.packageName}: application metadata unavailable")
                }
                add("Discovered known services=${result.size}; absence may mean package visibility restrictions or different component names.")
            }
            synchronized(this) {
                if (closed || generation != token) return@execute
                services = result
                busy = false
                status = "Inspection complete; select an eligible service for optional zero-flag bind"
                publish()
            }
        }
    }

    private fun inspectService(pm: PackageManager, info: ServiceInfo): NForetekServiceCandidate {
        val component = ComponentName(info.packageName, info.name)
        val permission = info.permission
        val granted = permission.isNullOrEmpty() || pm.checkPermission(permission, app.packageName) == PackageManager.PERMISSION_GRANTED
        val setting = pm.getComponentEnabledSetting(component)
        val componentEnabled = setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled)
        val applicationInfo = checkNotNull(info.applicationInfo) { "Application metadata unavailable" }
        val appSetting = pm.getApplicationEnabledSetting(info.packageName)
        val applicationEnabled = appSetting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (appSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && applicationInfo.enabled)
        val sppBindingBlocked = info.packageName == NFORETEK_PACKAGE && info.name == "com.nforetek.bt.service.NfServiceSpp"
        val eligible = info.exported && componentEnabled && applicationEnabled && granted && !sppBindingBlocked
        val reason = "exported=${info.exported} enabled=$componentEnabled applicationEnabled=$applicationEnabled " +
            "permission=${permission ?: "none"} grantedToDiPlay=$granted" +
            if (sppBindingBlocked) "; SPP binding disabled: audited no-op implementation/shared vendor lifecycle risk" else ""
        add("Service ${component.flattenToString()} process=${info.processName} $reason eligible=$eligible")
        if (!permission.isNullOrEmpty()) attempt("Permission $permission") {
            @Suppress("DEPRECATION")
            val protection = pm.getPermissionInfo(permission, 0).protectionLevel
            add("Permission $permission protectionLevel=0x${protection.toString(16)} " +
                "base=${when (protection and PermissionInfo.PROTECTION_MASK_BASE) {
                    PermissionInfo.PROTECTION_NORMAL -> "normal"
                    PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
                    PermissionInfo.PROTECTION_SIGNATURE -> "signature"
                    else -> "other"
                }}; no permission request or grant attempted")
        }
        return NForetekServiceCandidate(component, eligible, reason)
    }

    /** Called on the UI thread after explicit service selection; rechecks metadata before binding. */
    @Synchronized fun testBind(component: ComponentName) {
        if (closed || busy) return
        if (metadataPending.get()) {
            add("Bind FAIL previous Binder metadata IPC remains outstanding after timeout; no additional bind attempted")
            publish()
            return
        }
        if (services.none { it.component == component && it.bindEligible }) {
            add("Bind FAIL service not in manually inspected eligible list: ${component.flattenToString()}")
            publish()
            return
        }
        busy = true
        val test = BindAttempt(component, ++generation)
        active = test
        status = "Binding ${component.flattenToString()} flags=0 (already-running only)"
        try {
            @Suppress("DEPRECATION")
            val info = app.packageManager.getServiceInfo(component, PackageManager.GET_DISABLED_COMPONENTS)
            check(knownService(info.packageName, info.name)) { "Component is not a known vendor service" }
            check(inspectService(app.packageManager, info).bindEligible) { "Service is no longer exported/enabled/permitted" }
            val accepted = app.bindService(Intent().setComponent(component), test.connection, 0)
            test.registered = true
            add("bindService returned=$accepted; flags=0; no auto-create")
            if (test.finished) {
                unbind(test)
            } else if (!accepted) finish(test, "FAIL bindService returned false (not running/inaccessible/not bindable)")
            else main.postDelayed(test.timeout, BIND_TIMEOUT_MS)
        } catch (error: Exception) { finish(test, failureText("Bind", error)) }
        catch (error: LinkageError) { finish(test, failureText("Bind", error)) }
        publish()
    }

    private fun finish(test: BindAttempt, result: String) {
        if (test.finished) return
        test.finished = true
        main.removeCallbacks(test.timeout)
        add("${test.component.flattenToString()}: $result")
        Log.i(TAG, "${test.component.flattenToString()}: $result")
        unbind(test)
        if (active === test) {
            active = null
            busy = false
            status = result
        }
        publish()
    }

    private fun unbind(test: BindAttempt) {
        if (!test.registered) return
        test.registered = false
        attempt("Unbind ${test.component.flattenToString()}") {
            app.unbindService(test.connection)
            add("Unbound ${test.component.flattenToString()}")
        }
    }

    private fun attempt(stage: String, action: () -> Unit) {
        try { action() }
        catch (error: Exception) { add(failureText(stage, error)) }
        catch (error: LinkageError) { add(failureText(stage, error)) }
    }

    private fun failureText(stage: String, error: Throwable): String {
        Log.w(TAG, "$stage failed", error)
        return "$stage FAIL ${error.javaClass.simpleName}: ${error.message}"
    }

    @Synchronized private fun isClosed(token: Int) = closed || generation != token
    @Synchronized private fun add(line: String) {
        if (closed) return
        if (lines.size < 5000) lines.add(line)
        else if (lines.size == 5000) lines.add("Report truncated at 5000 lines")
    }
    @Synchronized private fun publish() {
        report = "Phase 3B.2: $status\n" + lines.joinToString("\n")
        if (!closed) onUpdate(report)
    }

    @Synchronized override fun close() {
        if (closed) return
        active?.let { finish(it, "Cancelled: activity paused/destroyed") }
        closed = true
        generation++
        worker.shutdownNow()
        metadataWorker.shutdownNow()
    }

    companion object {
        const val TAG = "DiPlayPhase3B2Device"
        const val BIND_TIMEOUT_MS = 5000L
        const val NFORETEK_PACKAGE = "com.nforetek.bt"
        fun knownService(packageName: String, className: String): Boolean =
            (packageName == NFORETEK_PACKAGE && className in setOf(
                "com.nforetek.bt.service.NfServiceBluetooth", "com.nforetek.bt.service.NfServiceSpp",
                "com.nforetek.bt.service.NfServiceHfp", "com.nforetek.bt.service.NfServiceA2dp",
                "com.nforetek.bt.service.NfServiceAvrcp", "com.nforetek.bt.service.NfServicePbap")) ||
                (className == "com.neusoft.geely.btphone.nf.BtManagerService" &&
                    className.startsWith("$packageName.")) ||
                ((packageName == "com.neusoft.optimus.wheeljack.setting" ||
                    packageName.startsWith("com.neusoft.optimus.wheeljack.setting.")) &&
                    className.startsWith("$packageName.") &&
                    className.substringAfterLast('.') in setOf("BluetoothControlService", "BluetoothService"))
    }
}
