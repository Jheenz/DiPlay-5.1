package com.shilapi.xcertplay.transport

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbRequest
import android.os.Build
import java.nio.ByteBuffer
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal sealed class UsbRequestWaitResult<out T> {
    data class Completed<T>(val value: T) : UsbRequestWaitResult<T>()
    data class TimedOut(val requestDrained: Boolean) : UsbRequestWaitResult<Nothing>()
    object Closed : UsbRequestWaitResult<Nothing>()
}

internal interface UsbRequestWaitBackend<T> {
    @Throws(TimeoutException::class)
    fun waitWithTimeout(timeoutMillis: Long): T

    fun waitBlocking(): T

    fun cancel()
}

/** API-level differences and pre-API-28 USB transfer limits shared by the wired USB paths. */
internal object UsbTransferCompatibility {
    const val LEGACY_MAX_TRANSFER_BYTES = 16 * 1024

    private val timeoutExecutor = ScheduledThreadPoolExecutor(
        1,
        ThreadFactory { runnable ->
            Thread(runnable, "usb-request-timeout").apply { isDaemon = true }
        },
    ).apply { removeOnCancelPolicy = true }

    fun maximumTransferBytes(apiLevel: Int): Int =
        if (apiLevel < 28) LEGACY_MAX_TRANSFER_BYTES else Int.MAX_VALUE

    fun permissionPendingIntentFlags(apiLevel: Int): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (apiLevel >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    fun legacyQueueLength(buffer: ByteBuffer): Int = buffer.remaining()

    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    fun queue(request: UsbRequest, buffer: ByteBuffer, apiLevel: Int = Build.VERSION.SDK_INT): Boolean =
        if (apiLevel >= 26) {
            request.queue(buffer)
        } else {
            request.queue(buffer, legacyQueueLength(buffer))
        }

    @SuppressLint("NewApi")
    fun awaitUsbRequest(
        connection: UsbDeviceConnection,
        request: UsbRequest,
        timeoutMillis: Long,
        buffer: ByteBuffer,
        isClosed: () -> Boolean,
        apiLevel: Int = Build.VERSION.SDK_INT,
    ): UsbRequestWaitResult<UsbRequest?> = await(
        apiLevel = apiLevel,
        timeoutMillis = timeoutMillis,
        isClosed = isClosed,
        hasReceivedData = { buffer.position() > 0 },
        backend = object : UsbRequestWaitBackend<UsbRequest?> {
            override fun waitWithTimeout(timeoutMillis: Long): UsbRequest? =
                connection.requestWait(timeoutMillis)

            override fun waitBlocking(): UsbRequest? = connection.requestWait()

            override fun cancel() {
                request.cancel()
            }
        },
    )

    fun <T> await(
        apiLevel: Int,
        timeoutMillis: Long,
        isClosed: () -> Boolean,
        hasReceivedData: () -> Boolean = { false },
        backend: UsbRequestWaitBackend<T>,
    ): UsbRequestWaitResult<T> {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (apiLevel >= 26) {
            return try {
                UsbRequestWaitResult.Completed(backend.waitWithTimeout(timeoutMillis))
            } catch (_: TimeoutException) {
                UsbRequestWaitResult.TimedOut(requestDrained = false)
            } catch (failure: RuntimeException) {
                if (isClosed()) UsbRequestWaitResult.Closed else throw failure
            }
        }

        val timedOut = AtomicBoolean(false)
        val timeout: ScheduledFuture<*> = timeoutExecutor.schedule(
            {
                timedOut.set(true)
                backend.cancel()
            },
            timeoutMillis,
            TimeUnit.MILLISECONDS,
        )
        return try {
            val result = backend.waitBlocking()
            when {
                isClosed() -> UsbRequestWaitResult.Closed
                timedOut.get() && !hasReceivedData() ->
                    UsbRequestWaitResult.TimedOut(requestDrained = true)
                else -> UsbRequestWaitResult.Completed(result)
            }
        } catch (failure: RuntimeException) {
            if (isClosed()) UsbRequestWaitResult.Closed else throw failure
        } finally {
            timeout.cancel(false)
        }
    }

    /**
     * Writes one logical byte range while respecting the platform's per-transfer limit.
     * The callback must return bytes transferred, zero for no progress, or a negative failure.
     */
    fun writeFully(
        data: ByteArray,
        timeoutMillis: Int,
        maximumTransferBytes: Int,
        transfer: (ByteArray, Int) -> Int,
    ): Int {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        require(maximumTransferBytes > 0) { "maximumTransferBytes must be positive" }
        var totalTransferred = 0
        while (totalTransferred < data.size) {
            val chunkSize = minOf(maximumTransferBytes, data.size - totalTransferred)
            var chunkTransferred = 0
            while (chunkTransferred < chunkSize) {
                val start = totalTransferred + chunkTransferred
                val request = data.copyOfRange(start, start + chunkSize - chunkTransferred)
                val transferred = transfer(request, timeoutMillis)
                if (transferred <= 0) {
                    return if (totalTransferred + chunkTransferred == 0) {
                        transferred
                    } else {
                        totalTransferred + chunkTransferred
                    }
                }
                check(transferred <= request.size) {
                    "USB transfer returned more bytes than requested"
                }
                chunkTransferred += transferred
            }
            totalTransferred += chunkTransferred
        }
        return totalTransferred
    }
}
