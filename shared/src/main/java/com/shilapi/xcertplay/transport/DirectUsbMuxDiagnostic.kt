package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One manual, plaintext ProductType query. No pairing client or projection controller is reachable. */
class DirectUsbMuxDiagnostic(
    private val access: DirectUsbMuxAccess,
    private val stepTimeoutMillis: Long = 5_000,
    private val totalTimeoutMillis: Long = 25_000,
) : Closeable {
    private val lock = Any()
    private val lines = CopyOnWriteArrayList<String>()
    private var pipe: UsbMuxBulkPipe? = null
    private val started = AtomicBoolean()
    @Volatile private var cleanupFailed = false
    @Volatile private var stopReason: String? = null
    @Volatile private var stage = "not started"
    @Volatile private var verdict = "INSUFFICIENT EVIDENCE"

    fun report(): String = (listOf(TITLE, SAFETY) + lines +
        listOf("Verdict: $verdict")).joinToString("\n")

    fun cancel(reason: String) {
        val active = synchronized(lock) {
            if (stopReason != null) return
            stopReason = reason
            lines += "STOP: $reason"
            if (reason.contains("Trust")) verdict = "INSUFFICIENT EVIDENCE"
            pipe
        }
        closeResource(active, "cancel USB")
    }

    override fun close() = cancel("Diagnostic cancelled")

    fun run(): String {
        check(started.compareAndSet(false, true)) { "Diagnostic instances are single-use" }
        val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "direct-usbmux-deadline").apply { isDaemon = true }
        }
        val deadline = watchdog.schedule({ cancel("Overall transport timeout") }, totalTimeoutMillis, TimeUnit.MILLISECONDS)
        var mux: Iap2UsbMuxHost? = null
        var channel: LockdownPlistChannel? = null
        var muxConfirmed = false
        var lockdownConfirmed = false
        try {
            at("Apple device selection")
            val apples = access.devices().filter { it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID }
            lines += "Apple device detected=${apples.isNotEmpty()} count=${apples.size}"
            if (apples.size != 1) throw IphoneUsbException.DeviceUnavailable("Exactly one Apple device is required; no device guessed")
            val device = apples.single()
            lines += "VID=${hex(device.vendorId)} PID=${hex(device.productId)}"
            at("USBMUX descriptor selection")
            val selected = DirectUsbMuxSelection.find(device)
                ?: throw IphoneUsbException.Protocol("No unambiguous 255/254/2 USBMUX interface with bulk OUT/IN")
            val intf = selected.usbInterface
            lines += "Selected interface index=${selected.index} id=${intf.id} alternate=${intf.alternateSetting} class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} protocol=${intf.interfaceProtocol}"
            lines += "USBMUX identity=255/254/2 (existing DiPlay selector); 255/253/1 is Ethernet, not USBMUX"
            lines += endpoint("OUT", selected.out)
            lines += endpoint("IN", selected.input)
            at("USB permission")
            val permitted = access.hasPermission(device)
            lines += "permissionAlreadyGranted=$permitted"
            if (!permitted) throw IphoneUsbException.PermissionDenied("USB permission required — STOP")
            at("USB open/claim")
            val opened = access.open(device, selected) { line ->
                lines += line
                if (line.contains("cleanup") && line.contains("FAIL")) cleanupFailed = true
            }
            synchronized(lock) { pipe = opened }
            checkStopped()
            at("USBMUX v2 handshake")
            mux = Iap2UsbMuxHost.open(opened, readTimeoutMillis = 500, handshakeTimeoutMillis = stepTimeoutMillis)
            muxConfirmed = true
            lines += "USBMUX handshake=PASS (outbound v2 framing; inbound valid v2 response; setup sent)"
            at("Lockdown port 62078 connect")
            val stream = mux.connect(Iap2UsbMuxHost.LOCKDOWN_PORT, stepTimeoutMillis)
            lines += "USBMUX TCP connect=PASS; Lockdown connection=PASS (port 62078)"
            channel = LockdownPlistChannel(stream, maximumMessageBytes = 64 * 1024, defaultTimeoutMillis = stepTimeoutMillis)
            at("Lockdown GetValue(ProductType)")
            val response = channel.request(
                LockdownPlistValue.Dictionary(linkedMapOf(
                    "Label" to LockdownPlistValue.Text("DiPlay-Phase3D2"),
                    "Request" to LockdownPlistValue.Text("GetValue"),
                    "Key" to LockdownPlistValue.Text("ProductType"),
                )), stepTimeoutMillis,
            )
            checkStopped()
            if (response.entries.containsKey("Error")) {
                val error = (response.entries["Error"] as? LockdownPlistValue.Text)?.value
                val safeError = error?.takeIf { it.matches(Regex("[A-Za-z]{1,64}")) } ?: "unrecognized remote error"
                throw IphoneUsbException.Protocol("Lockdown returned $safeError; no retry, pairing or Trust approval")
            }
            if ((response.entries["Request"] as? LockdownPlistValue.Text)?.value != "GetValue") {
                throw IphoneUsbException.Protocol("Lockdown response Request was not GetValue")
            }
            val productType = (response.entries["Value"] as? LockdownPlistValue.Text)?.value
                ?: throw IphoneUsbException.Protocol("Lockdown ProductType Value was not text")
            if (!productType.matches(Regex("[A-Za-z0-9,._-]{1,64}"))) {
                throw IphoneUsbException.Protocol("Lockdown ProductType did not match safe model metadata")
            }
            lockdownConfirmed = true
            lines += "Lockdown query=PASS; response Request=GetValue ValueType=Text ProductType=$productType"
        } catch (error: Exception) {
            lines += "Failure stage=$stage error=${error.javaClass.simpleName}: ${error.message}"
        } finally {
            closeResource(channel, "Lockdown channel")
            closeResource(mux, "USBMUX host")
            closeResource(synchronized(lock) { pipe }, "USB pipe")
            deadline.cancel(false)
            watchdog.shutdownNow()
            lines += "Summary USBMUX handshake confirmed=$muxConfirmed; harmless Lockdown query confirmed=$lockdownConfirmed"
            lines += "cleanup overall=${if (cleanupFailed) "FAIL (see details)" else "PASS (all acquired resources closed; none if stopped before open)"}"
            if (!muxConfirmed) lines += "USBMUX handshake=not confirmed; Lockdown connection/query=not attempted"
            else if (!lockdownConfirmed) lines += "Lockdown query=not confirmed (see failure stage)"
            verdict = when {
                stopReason?.contains("Trust") == true -> "INSUFFICIENT EVIDENCE"
                lockdownConfirmed -> "DIRECT USBMUX + LOCKDOWN CONFIRMED"
                muxConfirmed -> "DIRECT USBMUX CONFIRMED — LOCKDOWN NOT CONFIRMED"
                stage == "USB permission" || stage == "USB open/claim" -> "USB INTERFACE ACCESS FAILED"
                stage == "USBMUX v2 handshake" -> "USB TRANSPORT FAILED"
                else -> "INSUFFICIENT EVIDENCE"
            }
            lines += "STOP after Phase 3D.2; no pairing records, session, TLS, service or projection started."
        }
        return report()
    }

    private fun at(nextStage: String) {
        checkStopped()
        stage = nextStage
        lines += "Stage=$stage"
    }

    private fun checkStopped() {
        stopReason?.let { throw IphoneUsbException.DeviceUnavailable(it) }
    }

    private fun closeResource(resource: Closeable?, label: String) {
        if (resource == null) return
        try {
            resource.close()
        } catch (error: Exception) {
            cleanupFailed = true
            lines += "cleanup $label=FAIL ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    companion object {
        const val TITLE = "Phase 3D.2 — Direct USBMUX/Lockdown test"
        const val SAFETY = "PHASE 3D.2 TRANSPORT TEST ONLY — pairing, iAP2, MFi and CarPlay disabled"
        val NOT_RUN = "$TITLE: not run\n$SAFETY\nVerdict: INSUFFICIENT EVIDENCE"
        private fun hex(value: Int) = "0x%04X".format(Locale.US, value)
        private fun endpoint(label: String, endpoint: android.hardware.usb.UsbEndpoint) =
            "Selected endpoint $label address=${hex(endpoint.address)} number=${endpoint.endpointNumber} direction=${endpoint.direction} type=${endpoint.type} maxPacketSize=${endpoint.maxPacketSize} interval=${endpoint.interval}"
    }
}
