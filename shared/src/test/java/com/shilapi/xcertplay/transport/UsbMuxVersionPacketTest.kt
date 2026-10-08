package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class UsbMuxVersionPacketTest {
    @Test fun exactAuditedPacketAndFreshArray() {
        val expected = byteArrayOf(0,0,0,0,0,0,0,20,0,0,0,2,0,0,0,0,0,0,0,0)
        assertArrayEquals(expected, UsbMuxVersionPacket.request())
        val first = UsbMuxVersionPacket.request()
        first[0] = 6
        assertArrayEquals(expected, UsbMuxVersionPacket.request())
        assertEquals(20, UsbMuxVersionPacket.LENGTH)
    }
}
