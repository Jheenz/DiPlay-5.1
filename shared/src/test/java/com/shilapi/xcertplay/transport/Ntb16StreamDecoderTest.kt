package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ntb16StreamDecoderTest {
    @Test
    fun fragmentedNtb16BlocksAreReassembledAcrossLegacyUsbChunks() {
        val frameSizes = listOf(1, 16_383, 16_384, 16_385, 32_768, 65_507)
        for (size in frameSizes) {
            val frame = ByteArray(size) { (it % 239).toByte() }
            val block = Ntb16Codec.build(frame, sequence = size and 0xffff)
            val decoder = Ntb16StreamDecoder()
            val received = ArrayList<ByteArray>()

            var offset = 0
            while (offset < block.size) {
                val end = minOf(offset + UsbTransferCompatibility.LEGACY_MAX_TRANSFER_BYTES, block.size)
                received += decoder.offer(block.copyOfRange(offset, end))
                offset = end
            }

            assertEquals("frame size $size", 1, received.size)
            assertArrayEquals("frame size $size", frame, received.single())
            assertEquals(0, decoder.bufferedBytes)
        }
    }

    @Test
    fun oneByteFragmentsAndCoalescedPaddedBlocksPreserveFrameBoundaries() {
        val first = ByteArray(16_356) { (it % 251).toByte() }
        val second = byteArrayOf(0x33, 0x44, 0x55)
        val firstBlock = Ntb16Codec.build(first, 1)
        val secondBlock = Ntb16Codec.build(second, 2)
        assertEquals(16_385, firstBlock.size)
        val wire = firstBlock + secondBlock
        val decoder = Ntb16StreamDecoder()
        val frames = ArrayList<ByteArray>()

        for (byte in wire) frames += decoder.offer(byteArrayOf(byte))

        assertEquals(2, frames.size)
        assertArrayEquals(first, frames[0])
        assertArrayEquals(second, frames[1])
        assertEquals(0, decoder.bufferedBytes)
        assertTrue(wire.size > firstBlock.size)
    }
}
