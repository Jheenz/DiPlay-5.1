package com.shilapi.xcertplay

import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDeviceConnection

class AndroidQDriveDescriptorAccess(private val manager: UsbManager) : QDriveDescriptorAccess {
    override fun devices() = AndroidPassiveUsbInventory(manager).snapshot()

    override fun inspect(device: PassiveUsbDevice, report: (String) -> Unit): Boolean? {
        val current = manager.deviceList[device.name] ?: error("Apple device detached")
        check(current.vendorId == 0x05ac && current.productId == device.productId) { "USB identity changed — STOP" }
        check(manager.hasPermission(current)) { "USB permission required — STOP" }
        val connection = manager.openDevice(current) ?: error("openDevice returned null")
        try {
            report("open result=PASS; no interfaces claimed")
            val value = readOnlyUsbConfiguration(connection, report)
            report("Current configuration value=$value name=${device.configurations.singleOrNull { it.id == value }?.name ?: "unavailable"}")
            return inspectConnection(connection, report)
        } finally {
            try {
                connection.close()
                report("cleanup connection close=PASS")
            } catch (error: Exception) {
                report("cleanup connection close=FAIL ${error.javaClass.simpleName}: ${error.message}")
                throw error
            }
        }
    }

    internal fun inspectConnection(
        connection: UsbDeviceConnection,
        report: (String) -> Unit,
        checkActive: () -> Unit = {},
        configurationId: Int? = null,
        expectedConfigurationCount: Int? = null,
    ): Boolean? {
        checkActive()
        val raw = connection.rawDescriptors ?: error("Raw descriptors unavailable")
        val interfaces = QDriveDescriptors.interfaces(raw)
        if (expectedConfigurationCount != null) {
            check(raw[17].toInt() and 255 == expectedConfigurationCount) { "Raw configuration count mismatch" }
        }
        report("Raw descriptor SHA256=${java.security.MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) }}")
        report("getRawDescriptors result=PASS; QDrive checks first descriptor/alternate per interface ID in every configuration, in descriptor order.")
        report("Cached names shown above; language provenance unavailable. Reading only checked iInterface strings in QDrive's first LANGID.")
        var uncertain = false
        for (intf in interfaces) {
            if (configurationId != null && intf.configurationId != configurationId) continue
            report("Checked configuration index=${intf.configurationIndex} ID=${intf.configurationId} interface ID=${intf.interfaceId} alt=${intf.alternate} class=${intf.interfaceClass}/${intf.subclass}/${intf.protocol} iInterface=${intf.stringIndex}")
            checkActive()
            val result = readString(connection, intf.stringIndex, report, checkActive)
            uncertain = uncertain || result.uncertain
            val match = result.text?.contains("Valeria") == true
            report("result=${result.status} ${result.detail}")
            result.text?.let { text ->
                report("Retrieved interface ASCII string=\"${text.replace("\n", "\\n").replace("\r", "\\r")}\"")
            }
            report("Valeria substring match=${if (result.uncertain) "unavailable" else match.toString()}; QDrive decision=${if (match) "TERMINATE TRUE" else if (result.uncertain) "UNRESOLVED; diagnostic continues" else "CONTINUE (zeroed output on missing/failed read)"}")
            if (match) {
                report("strstr != NULL -> helper TRUE -> configuration-selection branch (not executed)")
                return true
            }
        }
        if (uncertain) {
            report("Complete scan reached its end, but an unresolved native outcome prevents proving helper FALSE.")
            return null
        }
        report("No checked string contains Valeria (or no interfaces): helper FALSE -> vendor-request branch (not executed)")
        return false
    }

    private fun readString(
        connection: UsbDeviceConnection, index: Int, report: (String) -> Unit, checkActive: () -> Unit,
    ): QDriveStringResult {
        if (index == 0) return QDriveStringResult("NO_STRING_INDEX", detail = "libusb returns -2 without a request; cleared output remains empty")
        fun request(value: Int, language: Int, buffer: ByteArray): Int {
            checkActive()
            return connection.controlTransfer(0x80, 6, value, language, buffer, buffer.size, 1_000)
        }
        try {
            val languages = ByteArray(255)
            val languageCount = request(0x0300, 0, languages)
            if (languageCount < 0) return QDriveStringResult("STRING_READ_FAILED", detail = "LANGID transfer=$languageCount; libusb returns before output conversion")
            if (languageCount < 4) return QDriveStringResult("MALFORMED", detail = "LANGID transfer=$languageCount < 4; libusb rejects before output conversion")
            check(languageCount <= languages.size) { "Impossible LANGID transfer length=$languageCount" }
            // QDrive uses the first LANGID bytes without validating the descriptor-zero header.
            val language = (languages[2].toInt() and 255) or ((languages[3].toInt() and 255) shl 8)
            report("First LANGID=$language (GET_DESCRIPTOR string index 0)")
            val buffer = ByteArray(255)
            val count = request(0x0300 or index, language, buffer)
            if (count < 0) return QDriveStringResult("STRING_READ_FAILED", detail = "interface transfer=$count; libusb returns before output conversion")
            if (count !in 2..buffer.size) return QDriveStringResult("MALFORMED", uncertain = true,
                detail = "interface transfer=$count; native header bytes may be uninitialized")
            val length = buffer[0].toInt() and 255
            if (buffer[1].toInt() and 255 != 3 || count < length) {
                return QDriveStringResult("MALFORMED", detail = "type/length rejected by libusb before output conversion")
            }
            if (length < 2) return QDriveStringResult("MALFORMED",
                detail = "bLength=$length < 2; native conversion writes empty output")
            if (length % 2 != 0) return QDriveStringResult("MALFORMED", uncertain = true,
                detail = "invalid/odd bLength=$length; native converter behavior not safely established")
            return QDriveStringResult("STRING_READ_PASS", QDriveDescriptors.ascii(buffer.copyOf(length)))
        } catch (error: RuntimeException) {
            return QDriveStringResult("STRING_READ_FAILED", uncertain = true,
                detail = "Android ${error.javaClass.simpleName}: ${error.message}; not a native transfer return code")
        }
    }
}
