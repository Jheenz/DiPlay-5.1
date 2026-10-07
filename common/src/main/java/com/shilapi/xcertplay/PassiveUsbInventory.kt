package com.shilapi.xcertplay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun interface PassiveUsbInventory {
    fun snapshot(): List<PassiveUsbDevice>
}

data class PassiveUsbDevice(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val subclass: Int,
    val protocol: Int,
    val hasPermission: Boolean,
    val interfaces: List<PassiveUsbInterface>,
    val configurations: List<PassiveUsbConfiguration> = emptyList(),
)

data class PassiveUsbConfiguration(
    val id: Int,
    val name: String?,
    val selfPowered: Boolean,
    val remoteWakeup: Boolean,
    val maxPowerMilliamps: Int,
    val interfaces: List<PassiveUsbInterface>,
)

data class PassiveUsbInterface(
    val id: Int,
    val interfaceClass: Int,
    val subclass: Int,
    val protocol: Int,
    val endpoints: List<PassiveUsbEndpoint>,
    val alternateSetting: Int? = null,
    val name: String? = null,
)

data class PassiveUsbEndpoint(
    val address: Int,
    val number: Int,
    val direction: Int,
    val type: Int,
    val maxPacketSize: Int,
    val interval: Int,
)

class PassiveUsbDeviceDiagnostic(
    private val inventory: PassiveUsbInventory,
    private val configurationMapping: Boolean = false,
) {
    fun scan(): String = buildString {
        appendLine(if (configurationMapping) MAPPING_TITLE else TITLE)
        appendLine(SAFETY)
        appendLine("Sampled: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("Android's existing USB host list only; no USB role/mode changes or transport startup.")
        appendLine("Apple VID is not proof of an iPhone or CarPlay. No Trust approval is needed for this scan.")
        val devices = try {
            inventory.snapshot()
        } catch (error: SecurityException) {
            appendLine("ERROR enumerating USB devices: ${error.javaClass.simpleName}: ${error.message}")
            return@buildString
        } catch (error: IllegalStateException) {
            appendLine("ERROR enumerating USB devices: ${error.javaClass.simpleName}: ${error.message}")
            return@buildString
        }
        appendLine("Attached USB device count=${devices.size}")
        if (devices.isEmpty()) appendLine("No attached USB devices visible to Android UsbManager in this sample.")
        devices.sortedBy { it.name }.forEach { device ->
            appendLine("Device name=${device.name}")
            appendLine("  vendorId=${hex(device.vendorId, 4)} (${device.vendorId}) productId=${hex(device.productId, 4)} (${device.productId})")
            if (device.vendorId == 0x05ac) appendLine("  Apple USB device candidate")
            appendLine("  class=${device.deviceClass} subclass=${device.subclass} protocol=${device.protocol}")
            appendLine("  interfaceCount=${device.interfaces.size} permissionAlreadyGranted=${device.hasPermission}")
            appendLine("  Flattened UsbDevice interface view (ordering does not identify active configuration)")
            device.interfaces.forEachIndexed { index, intf ->
                appendLine("  Interface index=$index id=${intf.id} class=${intf.interfaceClass} subclass=${intf.subclass} protocol=${intf.protocol} endpointCount=${intf.endpoints.size}")
                appendLine("    alternateSetting=${intf.alternateSetting ?: "unavailable"}")
                intf.endpoints.forEach { endpoint ->
                    val direction = when (endpoint.direction) {
                        0x80 -> "IN"
                        0 -> "OUT"
                        else -> "UNKNOWN"
                    }
                    val type = when (endpoint.type) {
                        0 -> "CONTROL"
                        1 -> "ISOCHRONOUS"
                        2 -> "BULK"
                        3 -> "INTERRUPT"
                        else -> "UNKNOWN"
                    }
                    appendLine("    Endpoint address=${hex(endpoint.address, 2)} number=${endpoint.number} direction=$direction (${endpoint.direction}) type=$type (${endpoint.type}) maxPacketSize=${endpoint.maxPacketSize} interval=${endpoint.interval}")
                }
            }
            if (configurationMapping && device.vendorId == 0x05ac) {
                append(PassiveUsbConfigurationMapping.describe(device))
            }
        }
        appendLine("STOP after ${if (configurationMapping) "Phase 3D.2A" else "Phase 3D.1"}. No Lockdown, usbmuxd, iAP2, MFi, NCM, Bluetooth or CarPlay.")
    }

    companion object {
        const val TITLE = "Phase 3D.1 — Direct iPhone USB detection"
        const val SAFETY = "PASSIVE ENUMERATION ONLY — no USB device opened or interface claimed"
        val NOT_RUN = "$TITLE: not run\n$SAFETY"
        const val MAPPING_TITLE = "Phase 3D.2A — iPhone USB configuration mapping"
        val MAPPING_NOT_RUN = "$MAPPING_TITLE: not run\n$SAFETY"

        private fun hex(value: Int, width: Int) = "0x%0${width}X".format(Locale.US, value)
    }
}
