package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test

class Iap2PreAuthProbeTest {
    private var time = 0L
    private val marker = byteArrayOf(0xff.toByte(), 0x55, 0x02, 0x00, 0xee.toByte(), 0x10)
    private val sync = Iap2LinkEngine.SynchronizationPayload(4, 4096, 2000, 500, 4, 3,
        listOf(Iap2LinkEngine.SessionDescriptor(10, 0, 2))).encode()

    private inner class FakeStream(vararg chunks: ByteArray) : BlockingDuplexByteStream {
        val incoming = java.util.ArrayDeque(chunks.toList())
        val output = mutableListOf<ByteArray>()
        var ended = false
        override fun send(data: ByteArray) { output.add(data.copyOf()) }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            time += timeoutMillis
            return if (incoming.isNotEmpty()) incoming.removeFirst() else if (ended) byteArrayOf() else null
        }
        override fun close() = Unit
    }

    @Test fun stopsAtIdentificationWithoutSendingAnyControlPayload() {
        val frame = Iap2CsmFramer.encodeFrame(0x1d00, byteArrayOf())
        val stream = FakeStream(marker, packet(0xc0, 1, 99, 0, sync),
            packet(0x40, 2, 99, 10, frame.copyOfRange(0, 3)),
            packet(0x40, 3, 99, 10, frame.copyOfRange(3, frame.size)))
        val events = mutableListOf<String>()
        val result = Iap2PreAuthProbe { time }.run(stream, onEvent = events::add)
        assertTrue(result.linkReady)
        assertEquals(0x1d00, result.firstMessageId)
        assertTrue(result.reason.contains("no IdentificationInformation"))
        assertTrue(events.any { it.contains("first iAP2 framing event") })
        assertArrayEquals(marker, stream.output.first())
        assertTrue(stream.output.drop(1).all { it.size >= 9 && it[7].toInt() == 0 })
    }

    @Test fun authenticationRequestIsReportedWithoutPayloadLoggingOrResponse() {
        val stream = FakeStream(marker, packet(0xc0, 1, 99, 0, sync),
            packet(0x40, 2, 99, 10, Iap2CsmFramer.encodeFrame(0xaa02, "secret-challenge".toByteArray())))
        val events = mutableListOf<String>()
        val result = Iap2PreAuthProbe { time }.run(stream, onEvent = events::add)
        assertEquals(0xaa02, result.firstMessageId)
        assertTrue(result.reason.contains("no response (0xaa03)"))
        assertFalse(events.joinToString().contains("secret-challenge"))
        assertTrue(stream.output.drop(1).all { it[7].toInt() == 0 })
    }

    @Test fun noPeerProducesBoundedFramingTimeoutNotSuccess() {
        val result = Iap2PreAuthProbe { time }.run(FakeStream(), timeoutMillis = 500)
        assertFalse(result.linkReady)
        assertNull(result.firstMessageId)
        assertTrue(result.reason.contains("Timed out"))
        assertEquals(500, time.toInt())
    }

    @Test fun negotiatedLinkWithoutControlIsDistinguishedFromRfcommOnly() {
        val result = Iap2PreAuthProbe { time }.run(FakeStream(marker, packet(0xc0, 1, 99, 0, sync)), timeoutMillis = 500)
        assertTrue(result.linkReady)
        assertNull(result.firstMessageId)
        assertTrue(result.reason.contains("no control frame"))
    }

    @Test fun peerCloseAndCancellationAreExplicitFailures() {
        assertThrows(IOException::class.java) { Iap2PreAuthProbe { time }.run(FakeStream().apply { ended = true }) }
        assertThrows(InterruptedIOException::class.java) { Iap2PreAuthProbe { time }.run(FakeStream(), cancelled = { true }) }
    }

    @Test fun everyAuthenticationBoundaryIsNonResponding() {
        assertTrue(preAuthBoundary(0xaa00).contains("no certificate"))
        assertTrue(preAuthBoundary(0xaa05).contains("no authentication response"))
        assertTrue(preAuthBoundary(0x4300).contains("no CSM response"))
    }

    private fun packet(control: Int, sequence: Int, acknowledgement: Int, sessionId: Int, payload: ByteArray): ByteArray {
        val length = 10 + payload.size
        val header = byteArrayOf(0xff.toByte(), 0x5a, (length ushr 8).toByte(), length.toByte(),
            control.toByte(), sequence.toByte(), acknowledgement.toByte(), sessionId.toByte(), 0)
        header[8] = checksum(header.copyOf(8)).toByte()
        return header + payload + checksum(payload).toByte()
    }

    private fun checksum(bytes: ByteArray): Int = (-bytes.sumOf { it.toInt() and 0xff }) and 0xff
}
