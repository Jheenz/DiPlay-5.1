package com.shilapi.xcertplay.transport

/**
 * Minimal NTB16 codec for the iPhone NCM data link.
 *
 * Only the 16-bit transfer-block layout used by LIVI's `iap2-usbmux/src/ntb.rs` is implemented:
 * one NTH16 header, one NDP16 table with one datagram, and the USB short-packet pad byte. There
 * is no NTB32, alignment, CRC, or NCM control-plane handling here.
 */
object Ntb16Codec {
    const val NTH16_SIG = 0x484d434e
    const val NDP16_SIG = 0x304d434e

    private const val NTH_LENGTH = 12
    private const val NDP_LENGTH = 16
    private const val DATAGRAM_INDEX = 28
    private const val USB_PACKET_SIZE = 512

    /** Wraps one Ethernet frame in one NTB16 block, padding an exact 512-byte boundary. */
    fun build(frame: ByteArray, sequence: Int): ByteArray {
        require(frame.isNotEmpty()) { "frame must not be empty" }
        require(frame.size <= MAX_DATAGRAM_BYTES) { "frame exceeds the NTB16 length field" }
        require(sequence in 0..0xffff) { "sequence must fit in u16" }

        val blockLength = DATAGRAM_INDEX + frame.size
        val block = ByteArray(blockLength)
        putU32(block, 0, NTH16_SIG)
        putU16(block, 4, NTH_LENGTH)
        putU16(block, 6, sequence)
        putU16(block, 8, blockLength)
        putU16(block, 10, NTH_LENGTH)

        putU32(block, 12, NDP16_SIG)
        putU16(block, 16, NDP_LENGTH)
        putU16(block, 18, 0)
        putU16(block, 20, DATAGRAM_INDEX)
        putU16(block, 22, frame.size)
        putU16(block, 24, 0)
        putU16(block, 26, 0)

        frame.copyInto(block, DATAGRAM_INDEX)
        // A transfer that ends exactly on a USB packet boundary is read as a short packet.
        return if (block.size % USB_PACKET_SIZE == 0) block + byteArrayOf(0) else block
    }

    /**
     * Extracts the Ethernet frames carried by one NTB16 block.
     *
     * Wire garbage returns an empty list instead of throwing; malformed entries are skipped where
     * a bounds check fails. A step guard stops a malformed NDP pointer chain from cycling.
     */
    fun parse(block: ByteArray): List<ByteArray> = parse(block, 0, block.size)

    /** Parses one NTB16 block from [offset] without copying the containing buffer. */
    fun parse(block: ByteArray, offset: Int, length: Int): List<ByteArray> {
        require(offset >= 0 && length >= 0 && offset <= block.size - length) {
            "NTB16 range is outside the source buffer"
        }
        val end = offset + length
        if (length < NTH_LENGTH || readU32(block, offset) != NTH16_SIG) return emptyList()

        val datagrams = ArrayList<ByteArray>(1)
        var ndpOffset = offset + readU16(block, offset + 10)
        var hops = 0
        val maxHops = maxOf(1, length / 4)
        while (ndpOffset != 0 && hops < maxHops) {
            if (ndpOffset < offset || ndpOffset + 12 > end) break
            if (readU32(block, ndpOffset) and 0x00ffffff != NDP16_SIG and 0x00ffffff) break
            val ndpLength = readU16(block, ndpOffset + 4)
            val nextNdp = readU16(block, ndpOffset + 6)
            var entry = ndpOffset + 8
            val ndpEnd = minOf(ndpOffset + ndpLength, end)
            while (entry + 4 <= ndpEnd) {
                val datagramIndex = readU16(block, entry)
                val datagramLength = readU16(block, entry + 2)
                if (datagramIndex == 0 || datagramLength == 0) break
                val datagramStart = offset + datagramIndex
                val datagramEnd = datagramStart + datagramLength
                if (datagramStart >= offset && datagramStart <= end && datagramEnd <= end) {
                    datagrams.add(block.copyOfRange(datagramStart, datagramEnd))
                }
                entry += 4
            }
            ndpOffset = if (nextNdp == 0) 0 else offset + nextNdp
            hops++
        }
        return datagrams
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    private const val MAX_DATAGRAM_BYTES = 0xffff - DATAGRAM_INDEX
}

/** Incremental NTB16 stream framing for USB reads that split or coalesce transfer blocks. */
internal class Ntb16StreamDecoder {
    private val buffered = ByteArray(MAX_WIRE_BLOCK_BYTES)
    private var head = 0
    private var tail = 0

    val bufferedBytes: Int get() = tail - head

    fun offer(chunk: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var offset = 0
        while (offset < chunk.size) {
            drain(frames)
            compact()
            val count = minOf(buffered.size - tail, chunk.size - offset)
            if (count <= 0) throw IllegalArgumentException("Incomplete NCM NTB exceeds its maximum size")
            chunk.copyInto(buffered, tail, offset, offset + count)
            tail += count
            offset += count
        }
        drain(frames)
        return frames
    }

    private fun drain(frames: MutableList<ByteArray>) {
        while (tail - head >= NTH_HEADER_BYTES) {
            if (readU32(buffered, head) != Ntb16Codec.NTH16_SIG) {
                throw IllegalArgumentException("NCM read buffer does not begin with an NTB16 header")
            }
            val blockLength = readU16(buffered, head + 8)
            if (blockLength !in MIN_BLOCK_BYTES..MAX_BLOCK_BYTES) {
                throw IllegalArgumentException("Invalid NCM NTB16 block length $blockLength")
            }
            val wireLength = blockLength + if (blockLength % USB_PACKET_SIZE == 0) 1 else 0
            if (tail - head < wireLength) return
            if (wireLength > blockLength && buffered[head + blockLength] != 0.toByte()) {
                throw IllegalArgumentException("Invalid NCM NTB16 short-packet pad")
            }
            frames += Ntb16Codec.parse(buffered, head, blockLength)
            head += wireLength
            if (head == tail) {
                head = 0
                tail = 0
            }
        }
    }

    private fun compact() {
        if (head == 0) return
        val size = tail - head
        if (size > 0) buffered.copyInto(buffered, 0, head, tail)
        head = 0
        tail = size
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    private companion object {
        const val NTH_HEADER_BYTES = 12
        const val MIN_BLOCK_BYTES = 28
        const val MAX_BLOCK_BYTES = 0xffff
        const val MAX_WIRE_BLOCK_BYTES = 0x1_0000
        const val USB_PACKET_SIZE = 512
    }
}
