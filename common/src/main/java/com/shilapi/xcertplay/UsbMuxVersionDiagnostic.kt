package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.UsbMuxVersionPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

interface UsbMuxVersionConnection : ActiveConfig5UsbMuxClaimConnection {
    fun writeVersion(packet: ByteArray): Int
    fun readVersion(buffer: ByteArray): Int
}

interface UsbMuxVersionAccess {
    fun devices(): List<PassiveUsbDevice>
    fun open(device: PassiveUsbDevice): UsbMuxVersionConnection
}

internal fun versionInterface(device: PassiveUsbDevice): PassiveUsbInterface =
    ActiveConfig5UsbMuxClaimSelection.interface5(device).also { mux ->
        requireUsbMuxClaim(mux.endpoints.all { it.maxPacketSize > UsbMuxVersionPacket.LENGTH } &&
            mux.endpoints.single { it.address == 0x85 }.maxPacketSize <= UsbMuxVersionDiagnostic.READ_CAPACITY,
            UsbMuxClaimFailureReason.PRECONDITION_FAILURE,
            "One-completion profile requires maxPacketSize>20 and IN maxPacketSize<=1024")
    }

class UsbMuxVersionDiagnostic(
    private val access: UsbMuxVersionAccess,
    private val clock: QDriveTransitionClock = SystemQDriveTransitionClock,
) {
    private val cancelled = AtomicBoolean()
    private val detached = AtomicBoolean()
    private val started = AtomicBoolean()
    fun cancel() { cancelled.set(true) }
    fun deviceDetached() { detached.set(true) }
    private fun active() {
        requireUsbMuxClaim(!detached.get(), UsbMuxClaimFailureReason.DEVICE_DISAPPEARED, "USB detach observed; STOP")
        requireUsbMuxClaim(!cancelled.get(), UsbMuxClaimFailureReason.CANCELLED, "Cancelled; STOP")
    }

    fun run(): String {
        val report = StringBuilder("$TITLE\n$SAFETY\n")
        var connection: UsbMuxVersionConnection? = null
        var device: PassiveUsbDevice? = null
        var claimed = false
        var released = false
        var closed = false
        var validated = false
        var preflight = false
        var outCalls = 0
        var inCalls = 0
        var failure: String? = null
        var stage = "PRECONDITION_FAILURE"
        fun fail(error: Exception, fallback: String) {
            val code = (error as? UsbMuxClaimFailure)?.reason?.name ?: fallback
            if (failure == null) failure = code
            report.appendLine("Failure=$code ${error.javaClass.simpleName}: ${error.message}; STOP (no workaround)")
            error.suppressed.forEach {
                report.appendLine("Suppressed cleanup failure=${it.javaClass.simpleName}: ${it.message}; STOP")
            }
        }
        fun requireResult(condition: Boolean, code: String, message: String) {
            if (!condition) {
                stage = code
                throw IllegalStateException(message)
            }
        }
        fun stable() {
            active()
            ActiveConfig5UsbMuxClaimSelection.unchanged(checkNotNull(device), access.devices())
        }
        try {
            requireUsbMuxClaim(started.compareAndSet(false, true), UsbMuxClaimFailureReason.ALREADY_RUN, "One-shot; no retry")
            active()
            val devices = access.devices()
            devices.forEach { report.appendLine("Preflight inventory=$it") }
            device = ActiveConfig5UsbMuxClaimSelection.device(devices)
            val mux = versionInterface(device)
            preflight = true
            report.appendLine("Preflight=PASS device=${device.name} VID=0x05AC PID=0x12A8 permissionAlreadyGranted=true configurations=5")
            report.appendLine("Selected configurationId=5 interfaceId=${mux.id} alt=${mux.alternateSetting} " +
                "class=${mux.interfaceClass}/${mux.subclass}/${mux.protocol} force=false endpoints=${mux.endpoints}")
            active()
            stage = "DEVICE_OPEN_FAILURE"
            connection = access.open(device)
            report.appendLine("Open=PASS (one connection)")
            stage = "GET_CONFIGURATION_FAILURE"
            val configuration = connection.configuration { report.appendLine(it) }
            report.appendLine("Same-connection GET_CONFIGURATION=$configuration")
            requireResult(configuration == 5, "ACTIVE_CONFIGURATION_NOT_5", "Readback=$configuration; no setter")
            stable()
            stage = "CLAIM_EXCEPTION"
            val claimStart = clock.elapsedMillis()
            report.appendLine("Claim timestamp=${clock.timestamp()} configurationId=5 interfaceId=1 alt=0 force=false")
            try {
                claimed = connection.claimUsbMux()
                report.appendLine("Claim result=$claimed")
            } finally { report.appendLine("Claim elapsedMs=${clock.elapsedMillis() - claimStart}") }
            requireResult(claimed, "CLAIM_RETURNED_FALSE", "Claim failed; no transfer")
            stable()
            val request = UsbMuxVersionPacket.request()
            report.appendLine("OUT timestamp=${clock.timestamp()} endpoint=0x04 length=${request.size} timeoutMs=$TIMEOUT_MILLIS requestHex=${hex(request)}")
            stage = "BULK_OUT_FAILURE"
            val outStart = clock.elapsedMillis()
            outCalls++
            val written = try { connection.writeVersion(request) }
            finally { report.appendLine("OUT elapsedMs=${clock.elapsedMillis() - outStart}") }
            report.appendLine("OUT result=$written")
            requireResult(written >= 0, "BULK_OUT_FAILURE", "OUT negative result=$written")
            requireResult(written == 20, if (written < 20) "SHORT_OUT" else "INVALID_OUT_RESULT",
                "Exactly20 required; no suffix write/retry")
            stable()
            stage = "BULK_IN_FAILURE"
            val buffer = ByteArray(READ_CAPACITY)
            report.appendLine("IN timestamp=${clock.timestamp()} endpoint=0x85 capacity=$READ_CAPACITY timeoutMs=$TIMEOUT_MILLIS")
            val inStart = clock.elapsedMillis()
            inCalls++
            val received = try { connection.readVersion(buffer) }
            finally { report.appendLine("IN elapsedMs=${clock.elapsedMillis() - inStart}") }
            report.appendLine("IN result=$received")
            stable()
            requireResult(received >= 0, "BULK_IN_TIMEOUT_OR_FAILURE",
                "Negative Android result; timeout vs other transfer error not independently distinguishable")
            requireResult(received >= 20, "SHORT_RESPONSE", "No fragment continuation; received=$received")
            requireResult(received == 20, "UNEXPECTED_PENDING_DATA", "Extra/coalesced/padded data; received=$received; no discard")
            val fields = ByteBuffer.wrap(buffer, 0, 20).order(ByteOrder.BIG_ENDIAN)
            val protocol = fields.int
            val length = fields.int
            val major = fields.int
            report.appendLine("Parsed protocol=$protocol declaredLength=$length major=$major")
            requireResult(protocol == 0, "WRONG_PROTOCOL", "Unexpected protocol/pending traffic; raw payload withheld")
            requireResult(length == 20, "WRONG_DECLARED_LENGTH", "Malformed length=$length; no resynchronization")
            requireResult(major == 2, "WRONG_MAJOR_VERSION", "Unsupported major=$major; no fallback")
            report.appendLine("Parsed minor=${fields.int} reserved=${fields.int}")
            report.appendLine("Response hex=${hex(buffer.copyOf(20))}")
            validated = true
            report.appendLine("Validation=PASS (minor/reserved not required to echo)")
        } catch (error: Exception) {
            fail(error, stage)
        } finally {
            connection?.let { opened ->
                try {
                    if (claimed) {
                        val start = clock.elapsedMillis()
                        report.appendLine("Release timestamp=${clock.timestamp()}")
                        try {
                            released = opened.releaseUsbMux()
                            report.appendLine("Release result=$released")
                            if (!released) throw IllegalStateException("Release returned false")
                        } catch (error: Exception) { fail(error, "RELEASE_FAILURE") }
                        finally { report.appendLine("Release elapsedMs=${clock.elapsedMillis() - start}") }
                    } else report.appendLine("Release=NOT ATTEMPTED (claim not successful)")
                } finally {
                    try {
                        opened.close()
                        closed = true
                        report.appendLine("Cleanup close=PASS")
                    } catch (error: Exception) { fail(error, "CLEANUP_FAILURE"); report.appendLine("Cleanup close=FAIL") }
                }
            }
        }
        if (connection != null && device != null) {
            try { stable(); report.appendLine("Final passive inventory unchanged=true") }
            catch (error: Exception) { fail(error, "DEVICE_STATE_CHANGED") }
        }
        if (!preflight) report.appendLine("Preflight=FAILED/NOT COMPLETED; no claim or protocol transfer")
        if (connection == null) report.appendLine("Cleanup=no connection returned to diagnostic; any open-layer cleanup failure is reported above")
        if (!validated) report.appendLine("Validation=FAILED/NOT COMPLETED")
        report.appendLine(if (failure == null && validated && claimed && released && closed) PASS else "Outcome=${failure ?: "PRECONDITION_FAILURE"} — STOP")
        report.appendLine(NOT_STARTED)
        report.appendLine("VERSION EXCHANGE ONLY — SETUP07 NOT SENT")
        report.appendLine("Bulk OUT calls=$outCalls; bulk IN calls=$inCalls; interrupt transfers=0; retries=0; setup07=0; TCP connects=0; Lockdown requests=0")
        report.appendLine("Initial version has no nonce/tag: pending identical reply freshness cannot be proved; no pre-read/drain or follow-up read.")
        report.appendLine("No vendor/setter/alternate/driver detach/native code, pairing records/Trust, iAP2/MFi/NCM/AirPlay/CarPlay. Trust untouched. STOP after Phase3D.2K.")
        return report.toString()
    }

    companion object {
        const val TITLE = "Phase 3D.2K — USBMUX version exchange"
        const val SAFETY = "ONE USBMUX VERSION REQUEST/REPLY ONLY — NO LOCKDOWN"
        const val PASS = "USBMUX VERSION EXCHANGE CONFIRMED"
        const val NOT_STARTED = "SETUP07 / LOCKDOWN / PAIRING NOT STARTED"
        const val TIMEOUT_MILLIS = 1000
        const val READ_CAPACITY = 1024
        val NOT_RUN = "$TITLE: not run\n$SAFETY"
        private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(Locale.US, it.toInt() and 255) }
    }
}
