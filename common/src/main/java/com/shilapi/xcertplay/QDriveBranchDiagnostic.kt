package com.shilapi.xcertplay

interface QDriveDescriptorAccess {
    fun devices(): List<PassiveUsbDevice>
    fun inspect(device: PassiveUsbDevice, report: (String) -> Unit): Boolean?
}

class QDriveBranchDiagnostic(private val access: QDriveDescriptorAccess) {
    fun scan(): String = buildString {
        appendLine(TITLE)
        appendLine(SAFETY)
        appendLine("Comparison: case-sensitive C strstr(interface ASCII string, \"Valeria\"); substring search, not exact equality.")
        appendLine("QDrive helper TRUE = first string containing Valeria -> configuration-selection branch.")
        appendLine("No examined string contains Valeria -> helper FALSE -> vendor-request branch. Neither branch executed.")
        var stage = "device selection"
        var result: Boolean? = null
        try {
            val device = access.devices().filter { it.vendorId == 0x05ac }.single()
            appendLine("VID=0x%04X PID=0x%04X".format(java.util.Locale.US, device.vendorId, device.productId))
            stage = "permission"
            appendLine("permissionAlreadyGranted=${device.hasPermission}")
            check(device.hasPermission) { "USB permission required — STOP" }
            device.configurations.forEachIndexed { configIndex, config ->
                appendLine("Cached configuration index=$configIndex ID=${config.id} name=${config.name ?: "unavailable"}")
                config.interfaces.forEachIndexed { index, intf ->
                    appendLine("  interface index=$index ID=${intf.id} alt=${intf.alternateSetting} class=${intf.interfaceClass}/${intf.subclass}/${intf.protocol} name=${intf.name ?: "unavailable"}")
                }
            }
            stage = "read-only descriptors / comparison / cleanup"
            result = access.inspect(device) { appendLine(it) }
        } catch (error: Exception) {
            appendLine("Failure stage=$stage error=${error.javaClass.simpleName}: ${error.message}")
        }
        appendLine("Valeria condition = ${when (result) { true -> "MATCH"; false -> "NO MATCH"; null -> "UNAVAILABLE" }}")
        appendLine(when (result) {
            true -> "VALERIA CONDITION MATCHES — CONFIGURATION-SELECTION BRANCH SUPPORTED"
            false -> "VALERIA CONDITION DOES NOT MATCH — VENDOR-REQUEST BRANCH SUPPORTED"
            null -> "VALERIA CONDITION COULD NOT BE DETERMINED"
        })
        appendLine("MATCH means the audited helper's case-sensitive Valeria substring predicate.")
        appendLine("STOP after Phase 3D.2C2. No branch performed.")
    }

    companion object {
        const val TITLE = "Phase 3D.2C1 — QDrive USB branch discriminator"
        const val SAFETY = "READ-ONLY DESCRIPTORS + GET_CONFIGURATION ONLY — no configuration change, vendor request, interface claim or transport"
        val NOT_RUN = "$TITLE: not run\n$SAFETY"
    }
}

internal data class QDriveInterfaceString(
    val configurationIndex: Int,
    val configurationId: Int,
    val interfaceId: Int,
    val alternate: Int,
    val interfaceClass: Int,
    val subclass: Int,
    val protocol: Int,
    val stringIndex: Int,
)

internal data class QDriveStringResult(
    val status: String,
    val text: String? = null,
    val uncertain: Boolean = false,
    val detail: String = "",
)

internal object QDriveDescriptors {
    fun interfaces(raw: ByteArray): List<QDriveInterfaceString> {
        fun byte(offset: Int) = raw[offset].toInt() and 255
        check(raw.size >= 18 && byte(0) == 18 && byte(1) == 1) { "Malformed/missing device descriptor" }
        val expectedConfigurations = byte(17)
        check(expectedConfigurations in 1..8) { "Unsupported configuration count=$expectedConfigurations" }
        val result = mutableListOf<QDriveInterfaceString>()
        var offset = 18
        var configurationIndex = 0
        val ids = mutableSetOf<Int>()
        while (offset < raw.size) {
            check(offset + 9 <= raw.size && byte(offset) == 9 && byte(offset + 1) == 2) { "Malformed/missing configuration descriptor" }
            val end = offset + byte(offset + 2) + (byte(offset + 3) shl 8)
            check(end in (offset + 9)..raw.size) { "Truncated configuration descriptor" }
            val configId = byte(offset + 5)
            check(configId > 0 && ids.add(configId)) { "Duplicate/invalid configuration ID" }
            val expectedInterfaces = byte(offset + 4)
            val seen = mutableSetOf<Int>()
            offset += 9
            while (offset < end) {
                check(offset + 2 <= end) { "Truncated descriptor header" }
                val length = byte(offset)
                check(length >= 2 && offset + length <= end) { "Malformed descriptor length" }
                if (byte(offset + 1) == 4) {
                    check(length >= 9) { "Malformed interface descriptor" }
                    val id = byte(offset + 2)
                    if (seen.add(id)) {
                        check(result.size < 64) { "Interface inspection limit exceeded — STOP" }
                        result += QDriveInterfaceString(configurationIndex, configId, id,
                            byte(offset + 3), byte(offset + 5), byte(offset + 6), byte(offset + 7), byte(offset + 8))
                    }
                }
                offset += length
            }
            check(seen.size == expectedInterfaces) { "Interface count mismatch" }
            configurationIndex++
        }
        check(configurationIndex == expectedConfigurations) { "Raw descriptors do not contain every advertised configuration" }
        return result
    }

    fun descriptor(buffer: ByteArray, count: Int): ByteArray {
        check(count in 2..buffer.size) { "String descriptor read failed count=$count" }
        val length = buffer[0].toInt() and 255
        check(buffer[1].toInt() and 255 == 3 && length in 2..count && length % 2 == 0) {
            "Malformed USB string descriptor"
        }
        return buffer.copyOf(length)
    }

    fun ascii(descriptor: ByteArray): String = buildString {
        for (offset in 2 until descriptor.size step 2) {
            val low = descriptor[offset].toInt() and 255
            val high = descriptor[offset + 1].toInt() and 255
            if (low == 0 && high == 0) break
            append(if (high == 0 && low < 128) low.toChar() else '?')
        }
    }
}
