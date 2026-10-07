package com.shilapi.xcertplay.transport

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.app.PendingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTransferCompatibilityTest {
    @Test
    fun api22LimitsEachTransferToSixteenKiBAndPassesExactLegacyQueueLength() {
        assertEquals(
            listOf(1, 16_383, 16_384, 16_384, 16_384, 16_384),
            listOf(1, 16_383, 16_384, 16_385, 32_768, 65_535)
                .map { minOf(it, UsbTransferCompatibility.maximumTransferBytes(22)) },
        )
        assertEquals(16_384, UsbTransferCompatibility.legacyQueueLength(ByteBuffer.allocateDirect(16_384)))
    }

    @Test
    fun api27RetainsLegacyTransferLimitAndApi28AllowsLargerTransfers() {
        assertEquals(16_384, UsbTransferCompatibility.maximumTransferBytes(27))
        assertEquals(Int.MAX_VALUE, UsbTransferCompatibility.maximumTransferBytes(28))
    }

    @Test
    fun permissionPendingIntentUsesOnlyFlagsSupportedByApi22() {
        assertEquals(
            PendingIntent.FLAG_UPDATE_CURRENT,
            UsbTransferCompatibility.permissionPendingIntentFlags(22),
        )
        assertEquals(
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            UsbTransferCompatibility.permissionPendingIntentFlags(23),
        )
    }

    @Test
    fun writeFullyChunksLogicalPacketsAndReassemblesPartialWrites() {
        val sizes = listOf(1, 16_383, 16_384, 16_385, 32_768, 65_535)
        for (size in sizes) {
            val data = ByteArray(size) { (it % 251).toByte() }
            val output = ByteArrayOutputStream()
            val calls = mutableListOf<Int>()
            val written = UsbTransferCompatibility.writeFully(
                data = data,
                timeoutMillis = 200,
                maximumTransferBytes = 16_384,
            ) { requested, _ ->
                assertTrue("transfer exceeded 16 KiB", requested.size <= 16_384)
                calls += requested.size
                val count = minOf(1_000, requested.size)
                output.write(requested, 0, count)
                count
            }
            assertEquals(size, written)
            assertEquals(data.toList(), output.toByteArray().toList())
            assertTrue(calls.all { it in 1..16_384 })
        }
    }

    @Test
    fun writeFullyReportsNoProgressAndDoesNotHidePartialLogicalWrites() {
        val data = ByteArray(20_000) { it.toByte() }
        assertEquals(
            -1,
            UsbTransferCompatibility.writeFully(data, 100, 16_384) { _, _ -> -1 },
        )

        val transferred = AtomicInteger()
        val partial = UsbTransferCompatibility.writeFully(data, 100, 16_384) { request, _ ->
            if (transferred.getAndAdd(request.size) == 0) 100 else 0
        }
        assertEquals(100, partial)
    }

    @Test
    fun api22TimeoutCancelsAndDrainsTheBlockingUsbRequest() {
        val cancelled = CountDownLatch(1)
        val result = UsbTransferCompatibility.await(
            apiLevel = 22,
            timeoutMillis = 40,
            isClosed = { false },
            backend = object : UsbRequestWaitBackend<String> {
                override fun waitWithTimeout(timeoutMillis: Long): String = error("not used on API 22")
                override fun waitBlocking(): String {
                    assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                    return "cancelled request drained"
                }
                override fun cancel() {
                    cancelled.countDown()
                }
            },
        )
        assertEquals(UsbRequestWaitResult.TimedOut(requestDrained = true), result)
    }

    @Test
    fun api22CloseCancelsWaitAndReturnsClosedRatherThanTimingOut() {
        val closed = AtomicBoolean(false)
        val unblocked = CountDownLatch(1)
        val started = CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<UsbRequestWaitResult<String>> {
                UsbTransferCompatibility.await(
                    apiLevel = 22,
                    timeoutMillis = 5_000,
                    isClosed = closed::get,
                    backend = object : UsbRequestWaitBackend<String> {
                        override fun waitWithTimeout(timeoutMillis: Long): String = error("not used on API 22")
                        override fun waitBlocking(): String {
                            started.countDown()
                            assertTrue(unblocked.await(2, TimeUnit.SECONDS))
                            return "closed"
                        }
                        override fun cancel() {
                            unblocked.countDown()
                        }
                    },
                )
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            closed.set(true)
            unblocked.countDown()
            assertEquals(UsbRequestWaitResult.Closed, future.get(2, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun newerTimedWaitKeepsPlatformTimeoutBehavior() {
        val cancelCount = AtomicInteger()
        val result = UsbTransferCompatibility.await(
            apiLevel = 26,
            timeoutMillis = 100,
            isClosed = { false },
            backend = object : UsbRequestWaitBackend<String> {
                override fun waitWithTimeout(timeoutMillis: Long): String {
                    assertEquals(100L, timeoutMillis)
                    throw TimeoutException()
                }
                override fun waitBlocking(): String = error("not used on API 26")
                override fun cancel() {
                    cancelCount.incrementAndGet()
                }
            },
        )
        assertEquals(UsbRequestWaitResult.TimedOut(requestDrained = false), result)
        assertEquals(0, cancelCount.get())
    }
}
