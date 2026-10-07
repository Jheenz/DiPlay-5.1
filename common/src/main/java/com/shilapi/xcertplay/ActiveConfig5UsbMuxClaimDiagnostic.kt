package com.shilapi.xcertplay

import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

interface ActiveConfig5UsbMuxClaimConnection : Closeable {
    fun configuration(report: (String) -> Unit): Int
    fun claimUsbMux(): Boolean
    fun releaseUsbMux(): Boolean
}

interface ActiveConfig5UsbMuxClaimAccess {
    fun devices(): List<PassiveUsbDevice>
    fun open(device: PassiveUsbDevice): ActiveConfig5UsbMuxClaimConnection
}

enum class UsbMuxClaimFailureReason {
    PRECONDITION_FAILURE, PERMISSION_UNAVAILABLE, DEVICE_OPEN_FAILURE,
    GET_CONFIGURATION_FAILURE, ACTIVE_CONFIGURATION_NOT_5, SCOPED_INTERFACE_MISSING_OR_AMBIGUOUS,
    CLAIM_RETURNED_FALSE, CLAIM_EXCEPTION, RELEASE_RETURNED_FALSE, RELEASE_EXCEPTION,
    DEVICE_DISAPPEARED, DEVICE_STATE_CHANGED, CLEANUP_FAILURE, CANCELLED, ALREADY_RUN,
}

class UsbMuxClaimFailure(val reason: UsbMuxClaimFailureReason, message: String) : IllegalStateException(message)

internal fun requireUsbMuxClaim(condition: Boolean, reason: UsbMuxClaimFailureReason, message: String) {
    if (!condition) throw UsbMuxClaimFailure(reason, message)
}

internal object ActiveConfig5UsbMuxClaimSelection {
    fun device(devices: List<PassiveUsbDevice>): PassiveUsbDevice {
        val apples = devices.filter { it.vendorId == 0x05ac }
        requireUsbMuxClaim(apples.size == 1, UsbMuxClaimFailureReason.PRECONDITION_FAILURE,
            "Exactly one Apple device required; count=${apples.size}; none guessed")
        val device = apples.single()
        interface5(device)
        return device
    }

    fun interface5(device: PassiveUsbDevice): PassiveUsbInterface {
        requireUsbMuxClaim(device.vendorId == 0x05ac && device.productId == 0x12a8,
            UsbMuxClaimFailureReason.PRECONDITION_FAILURE, "VID/PID must be 0x05AC/0x12A8")
        requireUsbMuxClaim(device.hasPermission, UsbMuxClaimFailureReason.PERMISSION_UNAVAILABLE,
            "Existing USB permission unavailable; no permission requested")
        requireUsbMuxClaim(device.configurations.size == 5, UsbMuxClaimFailureReason.PRECONDITION_FAILURE,
            "Exactly five configurations required")
        val config = device.configurations.singleOrNull { it.id == 5 }
            ?: throw UsbMuxClaimFailure(UsbMuxClaimFailureReason.PRECONDITION_FAILURE, "Unique configuration ID5 required")
        fun PassiveUsbInterface.matches(cls: Int, sub: Int, proto: Int) =
            interfaceClass == cls && subclass == sub && protocol == proto
        requireUsbMuxClaim(config.interfaces.any {
            it.matches(255, 42, 255) && it.name?.contains("Valeria") == true
        }, UsbMuxClaimFailureReason.PRECONDITION_FAILURE,
            "Configuration5 Valeria descriptor and case-sensitive interface-name evidence required")
        requireUsbMuxClaim(config.interfaces.any { it.matches(2, 13, 0) } &&
            config.interfaces.any { it.matches(10, 0, 1) }, UsbMuxClaimFailureReason.PRECONDITION_FAILURE,
            "Configuration5 CDC-NCM Control2/13/0 and Data10/0/1 required (metadata only)")
        val candidates = config.interfaces.filter { it.matches(255, 254, 2) }
        val mux = candidates.singleOrNull()
            ?: throw UsbMuxClaimFailure(UsbMuxClaimFailureReason.SCOPED_INTERFACE_MISSING_OR_AMBIGUOUS,
                "Configuration5 USBMUX candidate count=${candidates.size}; exactly one required")
        requireUsbMuxClaim(mux.id == 1 && mux.alternateSetting == 0 &&
            config.interfaces.count { it.id == 1 } == 1,
            UsbMuxClaimFailureReason.SCOPED_INTERFACE_MISSING_OR_AMBIGUOUS, "Unique USBMUX ID1/alt0 required in configuration5")
        requireUsbMuxClaim(mux.endpoints.size == 2 &&
            mux.endpoints.count { it.address == 0x04 && it.number == 4 && it.direction == 0 && it.type == 2 && it.maxPacketSize > 0 } == 1 &&
            mux.endpoints.count { it.address == 0x85 && it.number == 5 && it.direction == 128 && it.type == 2 && it.maxPacketSize > 0 } == 1,
            UsbMuxClaimFailureReason.SCOPED_INTERFACE_MISSING_OR_AMBIGUOUS, "Exact bulk OUT0x04/IN0x85 endpoint pair required")
        return mux
    }

    fun unchanged(expected: PassiveUsbDevice, devices: List<PassiveUsbDevice>) {
        requireUsbMuxClaim(devices.any { it.name == expected.name },
            UsbMuxClaimFailureReason.DEVICE_DISAPPEARED, "Selected device disappeared; no reopen/reclaim")
        val current = device(devices)
        requireUsbMuxClaim(current == expected, UsbMuxClaimFailureReason.DEVICE_STATE_CHANGED,
            "Device identity/configuration-scoped descriptors changed; STOP")
    }
}

class ActiveConfig5UsbMuxClaimDiagnostic(
    private val access: ActiveConfig5UsbMuxClaimAccess,
    private val clock: QDriveTransitionClock = SystemQDriveTransitionClock,
) {
    private val cancelled = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    private fun active() = requireUsbMuxClaim(!cancelled.get(), UsbMuxClaimFailureReason.CANCELLED, "Diagnostic cancelled; STOP")

    fun run(): String {
        val report = StringBuilder("$TITLE\n$SAFETY\n")
        var connection: ActiveConfig5UsbMuxClaimConnection? = null
        var claimed = false
        var released = false
        var closed = false
        var baseline: PassiveUsbDevice? = null
        var reason: UsbMuxClaimFailureReason? = null
        var stage = UsbMuxClaimFailureReason.PRECONDITION_FAILURE
        fun failure(error: Exception, fallback: UsbMuxClaimFailureReason) {
            val code = (error as? UsbMuxClaimFailure)?.reason ?: fallback
            if (reason == null) reason = code
            report.appendLine("Failure=$code ${error.javaClass.simpleName}: ${error.message}; STOP (no workaround)")
        }
        try {
            requireUsbMuxClaim(started.compareAndSet(false, true), UsbMuxClaimFailureReason.ALREADY_RUN,
                "One-shot diagnostic already run; no retry")
            active()
            val devices = access.devices()
            inventory(devices, report)
            val device = ActiveConfig5UsbMuxClaimSelection.device(devices)
            baseline = device
            val mux = ActiveConfig5UsbMuxClaimSelection.interface5(device)
            report.appendLine("Preflight=PASS; Valeria=true (configuration5 interface-name evidence); configurationCount=5")
            report.appendLine("Selected configurationId=5 interfaceId=${mux.id} alt=${mux.alternateSetting} " +
                "class=${mux.interfaceClass} subclass=${mux.subclass} protocol=${mux.protocol} force=false")
            mux.endpoints.forEach { report.appendLine("Selected ${endpoint(it)}") }
            active()
            stage = UsbMuxClaimFailureReason.DEVICE_OPEN_FAILURE
            connection = access.open(device)
            report.appendLine("open=PASS (single connection)")
            stage = UsbMuxClaimFailureReason.GET_CONFIGURATION_FAILURE
            val configuration = connection.configuration { report.appendLine(it) }
            report.appendLine("Same-connection GET_CONFIGURATION=$configuration")
            requireUsbMuxClaim(configuration == 5, UsbMuxClaimFailureReason.ACTIVE_CONFIGURATION_NOT_5,
                "Active configuration must be5; readback=$configuration")
            ActiveConfig5UsbMuxClaimSelection.unchanged(device, access.devices())
            active()
            stage = UsbMuxClaimFailureReason.CLAIM_EXCEPTION
            report.appendLine("Claim timestamp=${clock.timestamp()} activeConfiguration=5 configurationId=5 interfaceId=1 alt=0 force=false")
            val start = clock.elapsedMillis()
            try {
                claimed = connection.claimUsbMux()
                report.appendLine("claimInterface returned=$claimed")
            } finally {
                report.appendLine("Claim elapsedMs=${clock.elapsedMillis() - start}; no retry")
            }
            requireUsbMuxClaim(claimed, UsbMuxClaimFailureReason.CLAIM_RETURNED_FALSE, "claimInterface returned false")
            ActiveConfig5UsbMuxClaimSelection.unchanged(device, access.devices())
            active()
        } catch (error: Exception) {
            failure(error, stage)
        } finally {
            val opened = connection
            if (opened != null) {
                try {
                    if (claimed) {
                        val start = clock.elapsedMillis()
                        report.appendLine("Release timestamp=${clock.timestamp()} configurationId=5 interfaceId=1")
                        try {
                            released = opened.releaseUsbMux()
                            report.appendLine("releaseInterface returned=$released")
                            if (!released) throw UsbMuxClaimFailure(UsbMuxClaimFailureReason.RELEASE_RETURNED_FALSE,
                                "releaseInterface returned false")
                        } catch (error: Exception) {
                            failure(error, UsbMuxClaimFailureReason.RELEASE_EXCEPTION)
                        } finally {
                            report.appendLine("Release elapsedMs=${clock.elapsedMillis() - start}; no retry")
                        }
                    } else report.appendLine("Release=NOT ATTEMPTED (claim did not return true)")
                } finally {
                    try {
                        opened.close()
                        closed = true
                        report.appendLine("Cleanup connection close=PASS")
                    } catch (error: Exception) {
                        failure(error, UsbMuxClaimFailureReason.CLEANUP_FAILURE)
                        report.appendLine("Cleanup connection close=FAIL")
                    }
                }
            } else report.appendLine("Cleanup=NOT NEEDED (no connection acquired); claim/release not attempted")
        }
        if (connection != null && baseline != null) {
            try {
                ActiveConfig5UsbMuxClaimSelection.unchanged(baseline, access.devices())
                report.appendLine("Final passive device/configuration-scoped inventory unchanged=true")
                active()
            } catch (error: Exception) {
                failure(error, UsbMuxClaimFailureReason.DEVICE_STATE_CHANGED)
            }
        }
        report.appendLine(if (reason == null && claimed && released && closed) PASS else "Outcome=${reason ?: UsbMuxClaimFailureReason.PRECONDITION_FAILURE} — STOP")
        report.appendLine(PROTOCOL_NOT_TESTED)
        report.appendLine("Bulk transfers=0; interrupt transfers=0. No vendor request, configuration setter, alternate change, " +
            "driver detach, USBMUX open/version/setup07, usbmuxd, Lockdown/Pair/Trust, iAP2, MFi, NCM networking, AirPlay or CarPlay.")
        report.appendLine("Trust prompt is untouched and not a prerequisite. STOP after Phase3D.2I.")
        return report.toString()
    }

    companion object {
        const val TITLE = "Phase 3D.2I — Active configuration 5 USBMUX claim"
        const val SAFETY = "CLAIM/RELEASE ONLY — NO USBMUX PROTOCOL TRAFFIC"
        const val PASS = "ACTIVE CONFIGURATION 5 USBMUX INTERFACE CLAIM CONFIRMED"
        const val PROTOCOL_NOT_TESTED = "USBMUX PROTOCOL NOT TESTED — NO BULK TRAFFIC SENT"
        val NOT_RUN = "$TITLE: not run\n$SAFETY"

        private fun endpoint(ep: PassiveUsbEndpoint) =
            "Endpoint address=0x%02X".format(Locale.US, ep.address) +
                " number=${ep.number} direction=${ep.direction} type=${ep.type} maxPacketSize=${ep.maxPacketSize} interval=${ep.interval}"

        private fun inventory(devices: List<PassiveUsbDevice>, report: StringBuilder) {
            report.appendLine("Preflight configuration-scoped inventory; deviceCount=${devices.size}; no flattened interface selection")
            devices.forEach { device ->
                report.appendLine("Device name=${device.name} VID=0x%04X PID=0x%04X permissionAlreadyGranted=${device.hasPermission}"
                    .format(Locale.US, device.vendorId, device.productId))
                report.appendLine("deviceClass=${device.deviceClass} subclass=${device.subclass} protocol=${device.protocol} configurationCount=${device.configurations.size}")
                device.configurations.forEach { config ->
                    report.appendLine("ConfigurationId=${config.id} name=${config.name} maxPower=${config.maxPowerMilliamps} " +
                        "selfPowered=${config.selfPowered} remoteWakeup=${config.remoteWakeup} interfaceCount=${config.interfaces.size}")
                    config.interfaces.forEach { intf ->
                        report.appendLine("  InterfaceId=${intf.id} alt=${intf.alternateSetting} class=${intf.interfaceClass} " +
                            "subclass=${intf.subclass} protocol=${intf.protocol} name=${intf.name} endpointCount=${intf.endpoints.size}")
                        intf.endpoints.forEach { report.appendLine("    ${endpoint(it)}") }
                    }
                }
            }
        }
    }
}
