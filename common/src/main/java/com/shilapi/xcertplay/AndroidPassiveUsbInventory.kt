package com.shilapi.xcertplay

import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice

class AndroidPassiveUsbInventory(
    private val manager: UsbManager,
    private val includeAllConfigurations: Boolean = false,
    private val includeFlattenedInterfaces: Boolean = true,
) : PassiveUsbInventory {
    override fun snapshot(): List<PassiveUsbDevice> = manager.deviceList.values.map(::describeDevice)

    internal fun describeDevice(device: UsbDevice): PassiveUsbDevice {
        val vendorId = device.vendorId
        return PassiveUsbDevice(
            name = device.deviceName,
            vendorId = vendorId,
            productId = device.productId,
            deviceClass = device.deviceClass,
            subclass = device.deviceSubclass,
            protocol = device.deviceProtocol,
            hasPermission = manager.hasPermission(device),
            interfaces = if (includeFlattenedInterfaces) {
                (0 until device.interfaceCount).map { index -> describe(device.getInterface(index)) }
            } else emptyList(),
            configurations = if (vendorId == 0x05ac || includeAllConfigurations) {
                (0 until device.configurationCount).map { index ->
                    val configuration = device.getConfiguration(index)
                    PassiveUsbConfiguration(
                        id = configuration.id,
                        name = configuration.name,
                        selfPowered = configuration.isSelfPowered,
                        remoteWakeup = configuration.isRemoteWakeup,
                        maxPowerMilliamps = configuration.maxPower,
                        interfaces = (0 until configuration.interfaceCount).map {
                            describe(configuration.getInterface(it))
                        },
                    )
                }
            } else emptyList(),
        )
    }

    private fun describe(intf: UsbInterface): PassiveUsbInterface =
        PassiveUsbInterface(
            id = intf.id,
            interfaceClass = intf.interfaceClass,
            subclass = intf.interfaceSubclass,
            protocol = intf.interfaceProtocol,
            alternateSetting = intf.alternateSetting,
            name = intf.name,
            endpoints = (0 until intf.endpointCount).map { endpointIndex ->
                val endpoint = intf.getEndpoint(endpointIndex)
                PassiveUsbEndpoint(
                    address = endpoint.address,
                    number = endpoint.endpointNumber,
                    direction = endpoint.direction,
                    type = endpoint.type,
                    maxPacketSize = endpoint.maxPacketSize,
                    interval = endpoint.interval,
                )
            },
        )
}
