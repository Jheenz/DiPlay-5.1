package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Iap2LinkEngineProtocolTest {
    private class FakeStream : BlockingDuplexByteStream {
        val writes = LinkedBlockingQueue<ByteArray>()
        @Volatile var closed = false
            private set

        override fun send(data: ByteArray) { writes.put(data.copyOf()) }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
            if (closed) ByteArray(0) else null.also { if (timeoutMillis > 0) Thread.sleep(minOf(timeoutMillis, 5)) }
        override fun close() { closed = true }
    }

    @Test fun productionWiredLinkAdvertisesItsExplicitZeroAcknowledgementProfile() {
        val stream = FakeStream()
        val link = Iap2LinkChannel.open(stream)
        try {
            val startup = stream.writes.poll(2, TimeUnit.SECONDS)
            assertNotNull("wired link emitted no startup bytes", startup)
            assertArrayEquals(Iap2LinkEngine.IAP2_MARKER,
                startup!!.copyOfRange(0, Iap2LinkEngine.IAP2_MARKER.size))
            val syncOffset = Iap2LinkEngine.IAP2_MARKER.size
            val syncLength = u16(startup, syncOffset + 2)
            assertEquals("synchronization control", SYNCHRONIZE, startup[syncOffset + 4].toInt() and 0xff)
            val sync = startup.copyOfRange(syncOffset + 9, syncOffset + syncLength)
            assertEquals("retransmission timeout", 0, u16(sync, 4))
            assertEquals("acknowledgment timeout", 0, u16(sync, 6))
            assertEquals("maximum retransmissions", 0, sync[8].toInt() and 0xff)
            assertEquals("maximum acknowledgements", 0, sync[9].toInt() and 0xff)
        } finally {
            link.close()
        }
        assertTrue(stream.closed)
    }

    @Test fun wiredMarkerAndSynchronizationReachNormalAndAdvertiseControlLimit() {
        val engine = Iap2LinkEngine()
        engine.start(wiredInitiator = true, nowMillis = 0)
        val startup = engine.takeOutput()
        assertArrayEquals(Iap2LinkEngine.IAP2_MARKER, startup.copyOfRange(0, Iap2LinkEngine.IAP2_MARKER.size))
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, engine.state())

        engine.feed(packet(SYN_ACK, 1, INITIAL_SEQUENCE, 0, peerSync().encode()), 1)

        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state())
        assertTrue(engine.writable())
        assertEquals(4096, engine.peerSynchronization().maxLength)
        assertTrue(generateSequence { engine.pollEvent() }.any { it == Iap2LinkEngine.Event.Writable(true) })
    }

    @Test fun acceptsFragmentedAndOutOfOrderValidPacketsInSequenceOrder() {
        val engine = readyEngine()
        val second = packet(ACK, 3, INITIAL_SEQUENCE, Iap2LinkEngine.CONTROL_SESSION_ID, byteArrayOf(3))
        val first = packet(ACK, 2, INITIAL_SEQUENCE, Iap2LinkEngine.CONTROL_SESSION_ID, byteArrayOf(2))
        engine.feed(second.copyOfRange(0, 5), 2)
        assertNull(engine.pollEvent())
        engine.feed(second.copyOfRange(5, second.size), 3)
        assertNull(engine.pollEvent())
        engine.feed(first, 4)

        val firstEvent = engine.pollEvent() as Iap2LinkEngine.Event.Control
        val secondEvent = engine.pollEvent() as Iap2LinkEngine.Event.Control
        assertArrayEquals(byteArrayOf(2), firstEvent.bytes)
        assertArrayEquals(byteArrayOf(3), secondEvent.bytes)
        assertNull(engine.pollEvent())
    }

    @Test fun waitsForDeclaredPacketPayloadAndChecksumBeforeDelivering() {
        val engine = readyEngine()
        val frame = packet(ACK, 2, INITIAL_SEQUENCE, Iap2LinkEngine.CONTROL_SESSION_ID, byteArrayOf(9, 8, 7, 6))
        engine.feed(frame.copyOf(frame.size - 1), 2)
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state())
        assertNull(engine.pollEvent())
        engine.feed(frame.copyOfRange(frame.lastIndex, frame.size), 3)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), (engine.pollEvent() as Iap2LinkEngine.Event.Control).bytes)
    }

    @Test fun badHeaderOrPayloadChecksumDoesNotDeliverAndValidRetransmissionCanRecover() {
        val engine = readyEngine()
        val valid = packet(ACK, 2, INITIAL_SEQUENCE, Iap2LinkEngine.CONTROL_SESSION_ID, byteArrayOf(0x41, 0x42))
        val badHeader = valid.copyOf().apply { this[8] = (this[8].toInt() xor 1).toByte() }
        engine.feed(badHeader, 2)
        assertNull(engine.pollEvent())

        val badPayload = valid.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        engine.feed(badPayload, 3)
        assertNull(engine.pollEvent())
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state())

        engine.feed(valid, 4)
        assertArrayEquals(byteArrayOf(0x41, 0x42), (engine.pollEvent() as Iap2LinkEngine.Event.Control).bytes)
    }

    @Test fun rejectsTruncatedSynchronizationDescriptorAndTransitionsToDead() {
        val engine = Iap2LinkEngine()
        engine.start(wiredInitiator = true, nowMillis = 0)
        engine.takeOutput()
        val malformedSync = peerSync().encode() + byteArrayOf(0x7f)
        assertNull(Iap2LinkEngine.SynchronizationPayload.decode(malformedSync))
        engine.feed(packet(SYN_ACK, 1, INITIAL_SEQUENCE, 0, malformedSync), 1)

        assertEquals(Iap2LinkEngine.State.DEAD, engine.state())
        assertTrue(generateSequence { engine.pollEvent() }.any { it is Iap2LinkEngine.Event.Dead })
    }

    @Test fun retransmitsOnPeerDeadlineThenTerminatesAtConfiguredRetryLimit() {
        val engine = readyEngine(peerSync(retransmissionTimeoutMillis = 100, maxRetransmissions = 2))
        while (engine.pollEvent() != null) Unit
        engine.sendControl(byteArrayOf(0x55), nowMillis = 1)
        val original = engine.takeOutput()
        assertEquals(11, u16(original, 2))

        engine.advanceTime(101)
        assertArrayEquals(original, engine.takeOutput())
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state())

        engine.advanceTime(201)
        assertEquals(Iap2LinkEngine.State.DEAD, engine.state())
        val dead = generateSequence { engine.pollEvent() }.filterIsInstance<Iap2LinkEngine.Event.Dead>().single()
        assertTrue(dead.reason.orEmpty().contains("was not acknowledged"))
    }

    @Test fun acknowledgementClearsOutstandingPacketAndCleanEofEndsSession() {
        val engine = readyEngine()
        while (engine.pollEvent() != null) Unit
        engine.sendControl(byteArrayOf(1, 2), nowMillis = 1)
        engine.takeOutput()
        engine.feed(packet(ACK, 1, INITIAL_SEQUENCE + 1, 0, ByteArray(0)), 2)
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state())
        engine.takeOutput()
        engine.advanceTime(10_000)
        assertTrue(engine.takeOutput().isEmpty())

        engine.feedEof()
        assertEquals(Iap2LinkEngine.State.DEAD, engine.state())
        assertTrue(generateSequence { engine.pollEvent() }.any { it == Iap2LinkEngine.Event.Dead(null) })
    }

    private fun readyEngine(peer: Iap2LinkEngine.SynchronizationPayload = peerSync()): Iap2LinkEngine {
        val engine = Iap2LinkEngine()
        engine.start(wiredInitiator = true, nowMillis = 0)
        engine.takeOutput()
        engine.feed(packet(SYN_ACK, 1, INITIAL_SEQUENCE, 0, peer.encode()), 1)
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state())
        engine.takeOutput()
        while (engine.pollEvent() != null) Unit
        return engine
    }

    private fun peerSync(
        retransmissionTimeoutMillis: Int = 100,
        maxRetransmissions: Int = 3,
    ) = Iap2LinkEngine.SynchronizationPayload(
        maxOutgoing = 4,
        maxLength = 4096,
        retransmissionTimeoutMillis = retransmissionTimeoutMillis,
        acknowledgementTimeoutMillis = 100,
        maxRetransmissions = maxRetransmissions,
        maxAcknowledgements = 3,
        sessions = listOf(Iap2LinkEngine.SessionDescriptor(Iap2LinkEngine.CONTROL_SESSION_ID, 0, 2)),
    )

    private fun packet(control: Int, sequence: Int, acknowledgement: Int, sessionId: Int, payload: ByteArray): ByteArray {
        val length = 10 + payload.size
        val header = byteArrayOf(0xff.toByte(), 0x5a, (length ushr 8).toByte(), length.toByte(),
            control.toByte(), sequence.toByte(), acknowledgement.toByte(), sessionId.toByte(), 0)
        header[8] = checksum(header.copyOf(8)).toByte()
        return header + payload + checksum(payload).toByte()
    }

    private fun checksum(bytes: ByteArray): Int = (-bytes.sumOf { it.toInt() and 0xff }) and 0xff
    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private companion object {
        const val INITIAL_SEQUENCE = 99
        const val SYN_ACK = 0xc0
        const val ACK = 0x40
        const val SYNCHRONIZE = 0x80
    }
}
