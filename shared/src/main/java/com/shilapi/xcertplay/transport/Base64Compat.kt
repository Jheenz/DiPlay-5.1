package com.shilapi.xcertplay.transport

import android.util.Base64

/** Base64 operations available on all supported Android API levels. */
internal object Base64Compat {
    fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun decode(encoded: String): ByteArray =
        Base64.decode(encoded, Base64.DEFAULT)

    fun decodeMime(encoded: ByteArray): ByteArray =
        Base64.decode(encoded, Base64.DEFAULT)

    fun encodeMime(bytes: ByteArray, lineLength: Int = 64): String {
        require(lineLength > 0) { "lineLength must be positive" }
        val encoded = encode(bytes)
        if (encoded.isEmpty()) return encoded
        return encoded.chunked(lineLength).joinToString("\n")
    }
}
