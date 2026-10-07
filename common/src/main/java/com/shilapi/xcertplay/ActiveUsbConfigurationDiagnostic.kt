package com.shilapi.xcertplay

import java.util.Locale

interface ActiveUsbConfigurationAccess {
    fun devices(): List<PassiveUsbDevice>
    fun readConfiguration(device: PassiveUsbDevice, report: (String) -> Unit): Int
}

class ActiveUsbConfigurationDiagnostic(private val access: ActiveUsbConfigurationAccess) {
    fun scan(): String = buildString {
        appendLine(TITLE)
        appendLine(SAFETY)
        var stage = "Apple device selection"
        var value: Int? = null
        try {
            val apples = access.devices().filter { it.vendorId == 0x05ac }
            appendLine("Apple device detected=${apples.isNotEmpty()} count=${apples.size}")
            check(apples.size == 1) { "Exactly one Apple device required; no device guessed" }
            val device = apples.single()
            appendLine("VID=${hex(device.vendorId)} PID=${hex(device.productId)}")
            stage = "USB permission"
            appendLine("permissionAlreadyGranted=${device.hasPermission}")
            check(device.hasPermission) { "USB permission required — STOP" }
            stage = "open / GET_CONFIGURATION / close"
            value = access.readConfiguration(device) { appendLine(it) }
            appendLine("GET_CONFIGURATION result=PASS")
            appendLine("Active configuration value=$value")
            stage = "descriptor correlation"
            val matches = device.configurations.filter { it.id == value }
            if (value == 0) {
                appendLine("Device is unconfigured (bConfigurationValue=0); no active interface candidate.")
            } else if (matches.size != 1) {
                appendLine("ERROR descriptor correlation: matching configuration count=${matches.size}; no descriptor guessed")
            } else {
                val config = matches.single()
                appendLine("Matching descriptor configuration ID=${config.id}")
                appendLine("Descriptor name=${config.name ?: "unavailable"}")
                val mux = config.interfaces.filter {
                    it.interfaceClass == 255 && it.subclass == 254 && it.protocol == 2
                }
                appendLine("Scoped USBMUX candidate count=${mux.size} (metadata only; none claimed)")
                mux.forEach { intf ->
                    appendLine(describe(config.id, intf, "USBMUX candidate"))
                    val bulk = intf.endpoints.filter { it.type == 2 }
                    appendLine("  BULK OUT count=${bulk.count { it.direction == 0 }} IN count=${bulk.count { it.direction == 128 }}")
                    intf.endpoints.forEach { appendLine("  ${endpoint(it)}") }
                }
                val ethernet = config.interfaces.filter {
                    it.interfaceClass == 255 && it.subclass == 253 && it.protocol == 1
                }
                appendLine("Apple USB Ethernet present in active configuration=${ethernet.isNotEmpty()} descriptorEntries=${ethernet.size}")
                ethernet.forEach { intf ->
                    appendLine(describe(config.id, intf, "Apple USB Ethernet"))
                    intf.endpoints.forEach { appendLine("  ${endpoint(it)}") }
                }
                appendLine("Alternate-setting descriptors do not prove the currently active alternate setting; none activated.")
            }
        } catch (error: Exception) {
            appendLine("Failure stage=$stage error=${error.javaClass.simpleName}: ${error.message}")
            if (value == null) appendLine("GET_CONFIGURATION result=NOT CONFIRMED; active configuration=UNKNOWN")
        }
        if (value == null) {
            appendLine("Descriptor configuration / scoped USBMUX / active Ethernet presence=UNKNOWN")
        }
        appendLine("No active configuration inferred from array order. STOP after Phase 3D.2B.")
    }

    companion object {
        const val TITLE = "Phase 3D.2B — Active iPhone USB configuration"
        const val SAFETY = "READ-ONLY GET_CONFIGURATION ONLY — no interface claimed, configuration changed or transport started"
        val NOT_RUN = "$TITLE: not run\n$SAFETY"
        private fun hex(value: Int) = "0x%04X".format(Locale.US, value)
        private fun describe(config: Int, intf: PassiveUsbInterface, label: String) =
            "$label configurationId=$config interfaceId=${intf.id} alt=${intf.alternateSetting ?: "unavailable"} class=${intf.interfaceClass} subclass=${intf.subclass} protocol=${intf.protocol}"
        private fun endpoint(endpoint: PassiveUsbEndpoint) =
            "Endpoint address=${hex(endpoint.address)} number=${endpoint.number} direction=${endpoint.direction} type=${endpoint.type} maxPacketSize=${endpoint.maxPacketSize} interval=${endpoint.interval}"
    }
}
