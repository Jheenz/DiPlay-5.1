package com.shilapi.xcertplay.transport

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE)
class Base64CompatTest {
    @Test
    fun standardEncodingAndDecodingMatchKnownVectors() {
        val vectors = listOf(
            "" to "",
            "f" to "Zg==",
            "fo" to "Zm8=",
            "foo" to "Zm9v",
            "foobar" to "Zm9vYmFy",
        )
        for ((plain, encoded) in vectors) {
            val bytes = plain.toByteArray(StandardCharsets.US_ASCII)
            assertEquals(encoded, Base64Compat.encode(bytes))
            assertArrayEquals(bytes, Base64Compat.decode(encoded))
        }
    }

    @Test
    fun pemMimeEncodingWrapsAt64CharactersAndDecodesWhitespace() {
        val bytes = ByteArray(120) { (it * 7).toByte() }
        val encoded = Base64Compat.encodeMime(bytes)

        assertEquals(listOf(64, 64, 32), encoded.split('\n').map(String::length))
        assertArrayEquals(bytes, Base64Compat.decodeMime(encoded.toByteArray(StandardCharsets.US_ASCII)))
        assertArrayEquals(bytes, Base64Compat.decodeMime((" \n$encoded\t").toByteArray(StandardCharsets.US_ASCII)))
    }
}
