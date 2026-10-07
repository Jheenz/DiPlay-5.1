package com.shilapi.xcertplay

import java.util.Locale

/** Correlates descriptor values, not active configuration state or Android object identity. */
object PassiveUsbConfigurationMapping {
    fun describe(device: PassiveUsbDevice): String = buildString {
        appendLine("  configurationCount=${device.configurations.size}")
        appendLine("  Active/current configuration=UNKNOWN (not exposed by these passive Android getters)")
        appendLine("  Attributes: raw bmAttributes unavailable via public API22; reporting exposed flags only.")
        device.configurations.forEachIndexed { configIndex, config ->
            val flags = (if (config.selfPowered) 0x40 else 0) or (if (config.remoteWakeup) 0x20 else 0)
            appendLine("  Configuration arrayIndex=$configIndex id=${config.id} name=${config.name ?: "unavailable"}")
            appendLine("    attributes exposedMask=0x60 exposedBits=${hex(flags)} selfPowered=${config.selfPowered} remoteWakeup=${config.remoteWakeup} maxPower=${config.maxPowerMilliamps}mA interfaceCount=${config.interfaces.size}")
            config.interfaces.forEachIndexed { index, intf ->
                appendLine("    configurationId=${config.id} interfaceIndex=$index id=${intf.id} alt=${intf.alternateSetting ?: "unavailable"} class=${intf.interfaceClass} subclass=${intf.subclass} protocol=${intf.protocol} endpointCount=${intf.endpoints.size}")
                intf.endpoints.forEach { appendLine("      ${endpoint(it)}") }
            }
        }
        appendLine("  USBMUX candidate correlation: configuration -> interface -> alt setting -> endpoints")
        appendLine("  Descriptor-value matches only; multiple matches remain ambiguous. No interface selected.")
        val rows = device.configurations.flatMapIndexed { configIndex, config ->
            config.interfaces.mapIndexedNotNull { index, intf ->
                if (isCandidate(intf)) Row(configIndex, config.id, index, intf) else null
            }
        }
        rows.forEach { row ->
            val matches = device.interfaces.mapIndexedNotNull { index, intf ->
                if (sameDescriptors(intf, row.intf)) index else null
            }
            appendLine("    configIndex=${row.configIndex} configId=${row.configId} -> interfaceIndex=${row.index} id=${row.intf.id} -> alt=${row.intf.alternateSetting ?: "unavailable"} -> ${row.intf.endpoints.joinToString("; ", transform = ::endpoint)} -> flattenedDescriptorMatches=$matches")
        }
        device.interfaces.forEachIndexed { index, intf ->
            if (isCandidate(intf)) {
                val matches = rows.filter { sameDescriptors(it.intf, intf) }
                    .map { "configIndex=${it.configIndex}/configId=${it.configId}/interfaceIndex=${it.index}" }
                appendLine("    flattenedCandidate=$index id=${intf.id} alt=${intf.alternateSetting ?: "unavailable"} configurationDescriptorMatches=$matches")
                if (matches.isEmpty()) appendLine("      WARNING: no configuration-scoped descriptor match")
            }
        }
        appendLine("  Findings: flattenedCandidates=${device.interfaces.count(::isCandidate)} configurationScopedCandidates=${rows.size}")
        if (rows.map { it.configIndex }.distinct().size > 1) {
            appendLine("    USBMUX candidates appear in different configuration array entries; compare their reported IDs.")
        }
        rows.groupBy { it.configIndex to it.intf.id }.values.forEach { group ->
            if (group.mapNotNull { it.intf.alternateSetting }.distinct().size > 1) {
                appendLine("    Alternate settings represented within configIndex=${group.first().configIndex} interfaceId=${group.first().intf.id}.")
            }
            if (group.groupBy { it.intf }.values.any { it.size > 1 }) {
                appendLine("    Descriptor-identical repeated entries within one configuration; cause/Android duplication not established.")
            }
        }
        if (device.interfaces.filter(::isCandidate).groupBy { it }.values.any { it.size > 1 }) {
            appendLine("    Descriptor-identical flattened candidates cannot be assigned uniquely by descriptor equality alone.")
        }
        if (rows.map { it.intf.endpoints }.distinct().size > 1) {
            appendLine("    Configuration-scoped USBMUX candidates differ in endpoint descriptor properties.")
        }
        appendLine("  No active configuration inferred from ordering, configuration ID, endpoint addresses or permission.")
        appendLine("  Collect the real E01 report before any Phase 3D.2 selector change.")
    }

    private data class Row(val configIndex: Int, val configId: Int, val index: Int, val intf: PassiveUsbInterface)
    private fun sameDescriptors(first: PassiveUsbInterface, second: PassiveUsbInterface) =
        first.copy(name = null) == second.copy(name = null)
    private fun isCandidate(intf: PassiveUsbInterface) =
        intf.interfaceClass == 255 && intf.subclass == 254 && intf.protocol == 2
    private fun hex(value: Int) = "0x%02X".format(Locale.US, value)
    private fun endpoint(endpoint: PassiveUsbEndpoint) =
        "Endpoint address=${hex(endpoint.address)} number=${endpoint.number} direction=${endpoint.direction} type=${endpoint.type} maxPacketSize=${endpoint.maxPacketSize} interval=${endpoint.interval}"
}
