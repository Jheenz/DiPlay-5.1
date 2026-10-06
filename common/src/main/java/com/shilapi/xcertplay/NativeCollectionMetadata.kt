package com.shilapi.xcertplay

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

internal object NativeCollectionMetadata {
    data class Metadata(val architecture: String, val needed: List<String>, val configCandidates: List<String>)
    private data class Segment(val type: Long, val offset: Long, val address: Long, val size: Long)
    private val configName = Regex("(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+\\.(?:conf|cfg|ini|xml|json|properties)")

    fun inspect(file: File): Metadata = RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        fun range(offset: Long, size: Long) {
            require(offset >= 0 && size >= 0 && offset <= length && size <= length - offset) {
                "ELF offset/size outside file: $offset/$size"
            }
        }
        fun bytes(offset: Long, count: Int): ByteArray {
            range(offset, count.toLong())
            input.seek(offset)
            return ByteArray(count).also { input.readFully(it) }
        }
        val ident = bytes(0, 16)
        require(ident.take(4) == listOf<Byte>(0x7f, 0x45, 0x4c, 0x46)) { "Not an ELF file" }
        val wide = when (ident[4].toInt()) {
            1 -> false
            2 -> true
            else -> error("Unsupported ELF class")
        }
        val little = when (ident[5].toInt()) {
            1 -> true
            2 -> false
            else -> error("Unsupported ELF byte order")
        }
        fun number(offset: Long, count: Int): Long {
            val value = bytes(offset, count)
            var result = 0L
            for (i in 0 until count) {
                val index = if (little) count - i - 1 else i
                result = (result shl 8) or (value[index].toLong() and 255)
            }
            require(result >= 0) { "Unsupported unsigned ELF value" }
            return result
        }
        val machine = number(18, 2)
        val architecture = when (machine) {
            40L -> "ARM32"
            183L -> "AArch64"
            3L -> "x86"
            62L -> "x86_64"
            else -> "machine-$machine"
        }
        val phOffset = number(if (wide) 32 else 28, if (wide) 8 else 4)
        val phSize = number(if (wide) 54 else 42, 2)
        val phCount = number(if (wide) 56 else 44, 2)
        require(phSize >= if (wide) 56 else 32) { "Invalid ELF program-header size" }
        require(phCount <= 1024) { "ELF program-header count exceeds 1024" }
        range(phOffset, phSize * phCount)
        val segments = (0 until phCount.toInt()).map { index ->
            val p = phOffset + index * phSize
            Segment(number(p, 4), number(p + if (wide) 8 else 4, if (wide) 8 else 4),
                number(p + if (wide) 16 else 8, if (wide) 8 else 4),
                number(p + if (wide) 32 else 16, if (wide) 8 else 4))
        }
        val neededOffsets = mutableListOf<Long>()
        var stringAddress: Long? = null
        var stringSize: Long? = null
        for (segment in segments.filter { it.type == 2L }) {
            range(segment.offset, segment.size)
            val entrySize = if (wide) 16 else 8
            require(segment.size / entrySize <= 65536) { "ELF dynamic table exceeds 65536 entries" }
            for (index in 0 until (segment.size / entrySize).toInt()) {
                val p = segment.offset + index * entrySize
                val tag = number(p, entrySize / 2)
                val value = number(p + entrySize / 2, entrySize / 2)
                if (tag == 0L) break
                when (tag) {
                    1L -> neededOffsets.add(value)
                    5L -> stringAddress = value
                    10L -> stringSize = value
                }
            }
        }
        require(neededOffsets.size <= 256) { "ELF DT_NEEDED count exceeds 256" }
        val needed = if (neededOffsets.isEmpty()) emptyList() else {
            val address = checkNotNull(stringAddress) { "ELF DT_STRTAB unavailable" }
            val size = checkNotNull(stringSize) { "ELF DT_STRSZ unavailable" }
            val load = checkNotNull(segments.firstOrNull {
                it.type == 1L && address >= it.address && address - it.address < it.size
            }) { "ELF string table has no file-backed PT_LOAD mapping" }
            val displacement = address - load.address
            require(size <= load.size - displacement) { "ELF string table exceeds PT_LOAD" }
            val offset = load.offset + displacement
            range(offset, size)
            neededOffsets.map { start ->
                require(start < size) { "ELF DT_NEEDED outside string table" }
                input.seek(offset + start)
                val text = StringBuilder()
                var terminated = false
                for (i in 0 until minOf(4096L, size - start).toInt()) {
                    val byte = input.readUnsignedByte()
                    if (byte == 0) { terminated = true; break }
                    require(byte in 33..126) { "Invalid ELF dependency name" }
                    text.append(byte.toChar())
                }
                require(terminated && text.isNotEmpty()) { "Unterminated/empty ELF dependency name" }
                text.toString()
            }.distinct()
        }
        Metadata(architecture, needed, configStrings(file))
    }

    private fun configStrings(file: File): List<String> {
        require(file.length() <= 32L * 1024 * 1024) { "Config-reference scan exceeds 32 MiB; library can still be exported" }
        val result = linkedSetOf<String>()
        val text = StringBuilder()
        var oversized = false
        fun finish() {
            if (!oversized) {
                val value = text.toString()
                if (isConfigReference(value)) result.add(value)
                require(result.size <= 128) { "Config-reference candidates exceed 128" }
            }
            text.setLength(0)
            oversized = false
        }
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                for (i in 0 until count) {
                    val byte = buffer[i].toInt() and 255
                    if (byte in 33..126) {
                        if (text.length < 4096) text.append(byte.toChar()) else oversized = true
                    } else finish()
                }
            }
            finish()
        }
        return result.toList()
    }

    internal fun isConfigReference(value: String): Boolean =
        configName.matches(value.removePrefix("/").lowercase(Locale.ROOT)) &&
            value.split('/').none { it == ".." || it == "." }
}
