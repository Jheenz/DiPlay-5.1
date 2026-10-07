package com.shilapi.xcertplay.transport

import java.io.Closeable

/** Already-open bulk transport; no permission, configuration, pairing or service capabilities. */
interface UsbMuxBulkPipe : Closeable {
    fun write(data: ByteArray, timeoutMillis: Int)
    fun read(timeoutMillis: Long): ByteArray?
}
