package com.shilapi.xcertplay.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder

object UsbMuxVersionPacket {
    const val LENGTH = 20

    fun request(): ByteArray = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN)
        .putInt(0).putInt(LENGTH).putInt(2).putInt(0).putInt(0).array()
}
