package com.shilapi.xcertplay.transport

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class Iap2UsbMuxMultiplexTest {
    private class FakePipe : UsbMuxBulkPipe {
        data class SentTcp(val source: Int, val destination: Int, val sequence: Int, val acknowledgement: Int,
            val flags: Int, val payload: ByteArray)

        private val incoming = LinkedBlockingQueue<ByteArray>()
        val sent = mutableListOf<SentTcp>()
        private var muxSequence = 0
        private var remoteSequence = 1000
        private var writes = 0
        @Volatile private var protocolFailureOnRead = false
        @Volatile var closed = false
            private set

        override fun write(data: ByteArray, timeoutMillis: Int) {
            when (writes++) {
                0 -> incoming.put(versionReply())
                1 -> assertEquals("setup frame", 17, data.size)
                else -> {
                    assertEquals("TCP protocol", 6, readU32(data, 0))
                    val source = readU16(data, 16)
                    val destination = readU16(data, 18)
                    val sequence = readU32(data, 20)
                    val acknowledgement = readU32(data, 24)
                    val headerLength = ((data[28].toInt() ushr 4) and 0x0f) * 4
                    val flags = data[29].toInt() and 0xff
                    val payload = data.copyOfRange(16 + headerLength, data.size)
                    synchronized(sent) { sent += SentTcp(source, destination, sequence, acknowledgement, flags, payload) }
                    if ((flags and TCP_SYN) != 0) {
                        incoming.put(tcpReply(destination, source, TCP_SYN or TCP_ACK, remoteSequence++, ByteArray(0)))
                    }
                }
            }
        }

        override fun read(timeoutMillis: Long): ByteArray? {
            val bytes = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            if (protocolFailureOnRead) {
                protocolFailureOnRead = false
                throw IphoneUsbException.Protocol("Empty USBMUX completion")
            }
            return bytes
        }

        fun inject(remotePort: Int, localPort: Int, flags: Int, payload: ByteArray = ByteArray(0)) {
            incoming.put(tcpReply(remotePort, localPort, flags, remoteSequence++, payload))
        }

        fun injectTcp(remotePort: Int, localPort: Int, flags: Int, sequence: Int, acknowledgement: Int,
            payload: ByteArray = ByteArray(0)) {
            incoming.put(tcpReply(remotePort, localPort, flags, sequence, payload, acknowledgement))
        }

        fun injectMalformedTcpHeader(remotePort: Int, localPort: Int) {
            val frame = tcpReply(remotePort, localPort, TCP_ACK, remoteSequence++, ByteArray(0))
            frame[28] = 0x40
            incoming.put(frame)
        }

        fun injectUnknownProtocol(protocol: Int) {
            incoming.put(ByteArray(16).apply {
                putU32(this, 0, protocol)
                putU32(this, 4, 16)
                putU32(this, 8, MUX_MAGIC)
                putU16(this, 12, muxSequence++)
            })
        }

        fun injectInvalidMuxLength() {
            incoming.put(ByteArray(24).apply {
                putU32(this, 0, 6)
                putU32(this, 4, 0)
                putU32(this, 8, MUX_MAGIC)
                putU16(this, 12, muxSequence++)
            })
        }

        fun failNextReadWithProtocol() {
            protocolFailureOnRead = true
            incoming.offer(ByteArray(0))
        }

        override fun close() { closed = true }

        private fun versionReply() = ByteArray(20).apply {
            putU32(this, 0, 0)
            putU32(this, 4, 20)
            putU32(this, 8, 2)
            putU16(this, 12, 0)
        }

        private fun tcpReply(remotePort: Int, localPort: Int, flags: Int, sequence: Int, payload: ByteArray,
            acknowledgement: Int = 0): ByteArray {
            val tcp = ByteArray(20 + payload.size)
            putU16(tcp, 0, remotePort)
            putU16(tcp, 2, localPort)
            putU32(tcp, 4, sequence)
            putU32(tcp, 8, acknowledgement)
            tcp[12] = 0x50
            tcp[13] = flags.toByte()
            putU16(tcp, 14, 512)
            payload.copyInto(tcp, 20)
            val frame = ByteArray(16 + tcp.size)
            putU32(frame, 0, 6)
            putU32(frame, 4, frame.size)
            putU32(frame, 8, MUX_MAGIC)
            putU16(frame, 12, muxSequence++)
            tcp.copyInto(frame, 16)
            return frame
        }

        companion object {
            const val TCP_SYN = 0x02
            const val TCP_RST = 0x04
            const val TCP_FIN = 0x01
            const val TCP_ACK = 0x10
            const val MUX_MAGIC = 0xfeedface.toInt()

            fun putU16(target: ByteArray, offset: Int, value: Int) {
                target[offset] = (value ushr 8).toByte()
                target[offset + 1] = value.toByte()
            }

            fun putU32(target: ByteArray, offset: Int, value: Int) {
                target[offset] = (value ushr 24).toByte()
                target[offset + 1] = (value ushr 16).toByte()
                target[offset + 2] = (value ushr 8).toByte()
                target[offset + 3] = value.toByte()
            }

            fun readU16(source: ByteArray, offset: Int): Int =
                ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

            fun readU32(source: ByteArray, offset: Int): Int =
                ((source[offset].toInt() and 0xff) shl 24) or
                    ((source[offset + 1].toInt() and 0xff) shl 16) or
                    ((source[offset + 2].toInt() and 0xff) shl 8) or
                    (source[offset + 3].toInt() and 0xff)
        }
    }

    @Test fun twoAuthorizedSocketsRouteIndependentlyAndClosingServiceKeepsLockdownAlive() {
        val pipe = FakePipe()
        val host = Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe)
        val lockdown = host.connect(Iap2UsbMuxHost.LOCKDOWN_PORT)
        assertThrows(IllegalStateException::class.java) { host.connect(SERVICE_PORT) }
        host.authorizeDiagnosticServicePort(SERVICE_PORT)
        val service = host.connect(SERVICE_PORT)

        val lockdownPayload = "lockdown-response".toByteArray()
        val servicePayload = "service-handshake".toByteArray()
        pipe.inject(Iap2UsbMuxHost.LOCKDOWN_PORT, lockdown.sourcePort, FakePipe.TCP_ACK, lockdownPayload)
        pipe.inject(SERVICE_PORT, service.sourcePort, FakePipe.TCP_ACK, servicePayload)
        assertArrayEquals(lockdownPayload, lockdown.recv(64, 2_000))
        assertArrayEquals(servicePayload, service.recv(64, 2_000))

        service.close()
        assertTrue(lockdown.diagnosticState(), lockdown.diagnosticState().contains("TCP_OPEN=true"))
        lockdown.send("still-active".toByteArray())
        assertTrue(synchronized(pipe.sent) {
            pipe.sent.any { it.source == lockdown.sourcePort && it.destination == Iap2UsbMuxHost.LOCKDOWN_PORT &&
                it.payload.contentEquals("still-active".toByteArray()) }
        })
        assertFalse(pipe.closed)

        lockdown.close()
        host.close()
        assertTrue(pipe.closed)
    }

    @Test fun oneServiceAuthorizationIsRequiredAndOneShot() {
        val unopenedHost = Iap2UsbMuxHost.openReadOnlyDiagnostic(FakePipe())
        assertThrows(IllegalStateException::class.java) { unopenedHost.authorizeDiagnosticServicePort(SERVICE_PORT) }
        unopenedHost.close()

        val pipe = FakePipe()
        val host = Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe)
        val lockdown = host.connect(Iap2UsbMuxHost.LOCKDOWN_PORT)
        assertThrows(IllegalArgumentException::class.java) { host.authorizeDiagnosticServicePort(0) }
        host.authorizeDiagnosticServicePort(SERVICE_PORT)
        val service = host.connect(SERVICE_PORT)
        assertThrows(IllegalStateException::class.java) { host.authorizeDiagnosticServicePort(SERVICE_PORT + 1) }
        assertThrows(IllegalStateException::class.java) { host.connect(SERVICE_PORT) }
        service.close()
        lockdown.close()
        host.close()
    }

    @Test fun remotePortMismatchFailsClosedAndClosesBothSockets() {
        val pipe = FakePipe()
        val host = Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe)
        val lockdown = host.connect(Iap2UsbMuxHost.LOCKDOWN_PORT)
        host.authorizeDiagnosticServicePort(SERVICE_PORT)
        val service = host.connect(SERVICE_PORT)
        pipe.inject(SERVICE_PORT + 1, service.sourcePort, FakePipe.TCP_ACK, "wrong-peer".toByteArray())
        assertThrows(IphoneUsbException::class.java) { service.recv(64, 2_000) }
        assertFalse(lockdown.diagnosticState(), lockdown.diagnosticState().contains("TCP_OPEN=true"))
        assertFalse(service.diagnosticState(), service.diagnosticState().contains("TCP_OPEN=true"))
        host.close()
    }

    @Test fun unexpectedServiceResetClosesOnlyServiceSocketAndKeepsLockdownUsable() {
        val pipe = FakePipe()
        val host = Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe)
        val lockdown = host.connect(Iap2UsbMuxHost.LOCKDOWN_PORT)
        host.authorizeDiagnosticServicePort(SERVICE_PORT)
        val service = host.connect(SERVICE_PORT)
        pipe.inject(SERVICE_PORT, service.sourcePort, FakePipe.TCP_RST)
        assertThrows(IphoneUsbException::class.java) { service.recv(64, 2_000) }
        assertFalse(service.diagnosticState(), service.diagnosticState().contains("TCP_OPEN=true"))
        assertTrue(lockdown.diagnosticState(), lockdown.diagnosticState().contains("TCP_OPEN=true"))
        lockdown.send("Lockdown remains live".toByteArray())
        lockdown.close()
        host.close()
    }

    @Test fun lateServiceAckFinAndResetAreHandledWithoutBreakingLockdown() {
        val pipe = FakePipe()
        val host = Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe)
        val lockdown = host.connect(Iap2UsbMuxHost.LOCKDOWN_PORT)
        host.authorizeDiagnosticServicePort(SERVICE_PORT)
        val service = host.connect(SERVICE_PORT)
        service.close()
        val serviceFin = synchronized(pipe.sent) {
            pipe.sent.single { it.source == service.sourcePort && it.destination == SERVICE_PORT && it.flags == FakePipe.TCP_FIN or FakePipe.TCP_ACK }
        }
        assertEquals("FIN is sent at the next sequence", 1, serviceFin.sequence)

        pipe.inject(SERVICE_PORT, service.sourcePort, FakePipe.TCP_ACK)
        awaitState { host.diagnosticState().contains("closingSocketACK=1") }
        pipe.inject(SERVICE_PORT, service.sourcePort, FakePipe.TCP_FIN or FakePipe.TCP_ACK)
        awaitState { host.diagnosticState().contains("closingSocketFIN=1") }
        assertTrue(synchronized(pipe.sent) {
            pipe.sent.any { it.source == service.sourcePort && it.destination == SERVICE_PORT &&
                it.flags == FakePipe.TCP_ACK && it.sequence == serviceFin.sequence + 1 }
        })
        pipe.inject(SERVICE_PORT, service.sourcePort, FakePipe.TCP_RST or FakePipe.TCP_ACK)
        awaitState { host.diagnosticState().contains("closingSocketRST=1") }

        assertTrue(host.diagnosticState(), host.diagnosticState().contains("USBMUX_HOST_ACTIVE=true"))
        lockdown.send("StopSession request remains routable".toByteArray())
        assertTrue(synchronized(pipe.sent) {
            pipe.sent.any { it.source == lockdown.sourcePort && it.destination == Iap2UsbMuxHost.LOCKDOWN_PORT &&
                it.payload.contentEquals("StopSession request remains routable".toByteArray()) }
        })
        lockdown.close()
        host.close()
    }

    @Test fun exact56ByteRetiredServiceRstPayloadIsDroppedAndLockdownStopSessionBytesStillFlow() {
        val pipe = FakePipe()
        val diagnostics = CopyOnWriteArrayList<String>()
        val host = Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe, onDiagnostic = { diagnostics.add(it); Unit })
        val lockdown = host.connect(Iap2UsbMuxHost.LOCKDOWN_PORT)
        host.authorizeDiagnosticServicePort(SERVICE_PORT)
        val service = host.connect(SERVICE_PORT)
        service.close()

        val resetPayload = ByteArray(56) { it.toByte() }
        pipe.injectTcp(SERVICE_PORT, service.sourcePort, FakePipe.TCP_RST, sequence = 1002,
            acknowledgement = 2, payload = resetPayload)
        awaitState { host.diagnosticState().contains("closingSocketRSTPayloadFrames=1") }

        assertTrue(host.diagnosticState(), host.diagnosticState().contains("USBMUX_HOST_ACTIVE=true"))
        assertTrue(host.diagnosticState().contains("closingSocketRSTPayloadBytes=56"))
        assertTrue(diagnostics.none { it.startsWith("USBMUX_PROTOCOL_FAILURE") })

        val encryptedStopSessionRequest = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x04, 0x2a, 0x2b, 0x2c, 0x2d)
        val encryptedStopSessionResponse = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x03, 0x3a, 0x3b, 0x3c)
        lockdown.send(encryptedStopSessionRequest)
        pipe.injectTcp(Iap2UsbMuxHost.LOCKDOWN_PORT, lockdown.sourcePort, FakePipe.TCP_ACK,
            sequence = 1001, acknowledgement = 1 + encryptedStopSessionRequest.size,
            payload = encryptedStopSessionResponse)
        assertArrayEquals(encryptedStopSessionResponse, lockdown.recv(64, 2_000))
        assertTrue(host.diagnosticState(), host.diagnosticState().contains("USBMUX_HOST_ACTIVE=true"))
        lockdown.close()
        host.close()
    }

    @Test fun retiredServiceRstPayloadWithInvalidSequenceOrAcknowledgmentStillFailsClosed() {
        for (mode in listOf("sequence", "acknowledgment")) {
            val pipe = FakePipe()
            val diagnostics = CopyOnWriteArrayList<String>()
            val host = Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe, onDiagnostic = { diagnostics.add(it); Unit })
            host.connect(Iap2UsbMuxHost.LOCKDOWN_PORT)
            host.authorizeDiagnosticServicePort(SERVICE_PORT)
            val service = host.connect(SERVICE_PORT)
            service.close()
            pipe.injectTcp(SERVICE_PORT, service.sourcePort, FakePipe.TCP_RST,
                sequence = if (mode == "sequence") 1003 else 1002,
                acknowledgement = if (mode == "acknowledgment") 3 else 2,
                payload = ByteArray(56))
            awaitState { diagnostics.any { it.startsWith("USBMUX_PROTOCOL_FAILURE") } }
            val failure = diagnostics.single { it.startsWith("USBMUX_PROTOCOL_FAILURE") }
            val reason = if (mode == "sequence") "CLOSED_SOCKET_RST_SEQUENCE_INVALID"
                else "CLOSED_SOCKET_RST_ACKNOWLEDGEMENT_INVALID"
            assertTrue("$mode: $failure", failure.contains("reason=$reason"))
            assertTrue(failure.contains("socketRole=RETIRED_SERVICE"))
            assertTrue(failure.contains("tcpFlags=RST|PAYLOAD; payloadLength=56"))
            assertTrue(failure.contains("hostState=ACTIVE; hostFailure=NONE"))
            host.close()
        }
    }

    @Test fun payloadOrInvalidFlagsAfterServiceCloseStillFailClosed() {
        for (malformed in listOf("payload", "synAck", "header")) {
            val pipe = FakePipe()
            val host = Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe)
            val lockdown = host.connect(Iap2UsbMuxHost.LOCKDOWN_PORT)
            host.authorizeDiagnosticServicePort(SERVICE_PORT)
            val service = host.connect(SERVICE_PORT)
            service.close()
            if (malformed == "header") {
                pipe.injectMalformedTcpHeader(SERVICE_PORT, service.sourcePort)
            } else {
                val flags = if (malformed == "payload") FakePipe.TCP_ACK else FakePipe.TCP_SYN or FakePipe.TCP_ACK
                val payload = if (malformed == "payload") "not a close frame".toByteArray() else ByteArray(0)
                pipe.inject(SERVICE_PORT, service.sourcePort, flags, payload)
            }
            awaitState { host.diagnosticState().contains("USBMUX_HOST_ACTIVE=false") }
            assertTrue("$malformed: ${host.diagnosticState()}", host.diagnosticState().contains("hostFailure=Protocol"))
            assertFalse(lockdown.diagnosticState(), lockdown.diagnosticState().contains("TCP_OPEN=true"))
            host.close()
        }
    }

    @Test fun remotePortFailureReportsSocketFlagsValidityStageAndHostStateWithoutValues() {
        val pipe = FakePipe()
        val diagnosticLines = CopyOnWriteArrayList<String>()
        var stopStage = "BEFORE_STOPSESSION"
        val host = Iap2UsbMuxHost.openReadOnlyDiagnostic(
            pipe,
            onDiagnostic = { diagnosticLines.add(it); Unit },
            diagnosticStage = { stopStage },
        )
        host.connect(Iap2UsbMuxHost.LOCKDOWN_PORT)
        host.authorizeDiagnosticServicePort(SERVICE_PORT)
        val service = host.connect(SERVICE_PORT)
        stopStage = "DURING_STOPSESSION"
        pipe.inject(SERVICE_PORT + 1, service.sourcePort, FakePipe.TCP_ACK, "SECRET_PAYLOAD".toByteArray())
        awaitState { diagnosticLines.any { it.startsWith("USBMUX_PROTOCOL_FAILURE") } }

        val failure = diagnosticLines.single { it.startsWith("USBMUX_PROTOCOL_FAILURE") }
        assertTrue(failure, failure.contains("reason=TCP_REMOTE_PORT_MISMATCH"))
        assertTrue(failure, failure.contains("socketRole=SERVICE"))
        assertTrue(failure, failure.contains("tcpFlags=ACK|PAYLOAD"))
        assertTrue(failure, failure.contains("payloadLength=14"))
        assertTrue(failure, failure.contains("localSocketMatched=true; remotePortMatched=false"))
        assertTrue(failure, failure.contains("tcpSequenceExpectedMatch=true"))
        assertTrue(failure, failure.contains("tcpAcknowledgementWithinSentRange=true"))
        assertTrue(failure, failure.contains("stopSessionStage=DURING_STOPSESSION"))
        assertTrue(failure, failure.contains("hostState=ACTIVE; hostFailure=NONE; mappedSockets=2"))
        assertFalse(failure, failure.contains(SERVICE_PORT.toString()))
        assertFalse(failure, failure.contains("SECRET_PAYLOAD"))
        host.close()
    }

    @Test fun retiredSocketPayloadAndMalformedHeaderReportExactRedactedBranches() {
        run {
            val pipe = FakePipe()
            val diagnostics = CopyOnWriteArrayList<String>()
            val host = Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe, onDiagnostic = { diagnostics.add(it); Unit })
            host.connect(Iap2UsbMuxHost.LOCKDOWN_PORT)
            host.authorizeDiagnosticServicePort(SERVICE_PORT)
            val service = host.connect(SERVICE_PORT)
            service.close()
            pipe.inject(SERVICE_PORT, service.sourcePort, FakePipe.TCP_ACK, "SECRET_LATE_DATA".toByteArray())
            awaitState { diagnostics.any { it.startsWith("USBMUX_PROTOCOL_FAILURE") } }
            val failure = diagnostics.single { it.startsWith("USBMUX_PROTOCOL_FAILURE") }
            assertTrue(failure, failure.contains("reason=CLOSED_SOCKET_PAYLOAD"))
            assertTrue(failure, failure.contains("socketRole=RETIRED_SERVICE"))
            assertTrue(failure, failure.contains("tcpFlags=ACK|PAYLOAD"))
            assertTrue(failure, failure.contains("stopSessionStage=BEFORE_STOPSESSION"))
            assertFalse(failure.contains("SECRET_LATE_DATA"))
            host.close()
        }
        run {
            val pipe = FakePipe()
            val diagnostics = CopyOnWriteArrayList<String>()
            val host = Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe, onDiagnostic = { diagnostics.add(it); Unit })
            host.connect(Iap2UsbMuxHost.LOCKDOWN_PORT)
            host.authorizeDiagnosticServicePort(SERVICE_PORT)
            val service = host.connect(SERVICE_PORT)
            pipe.injectMalformedTcpHeader(SERVICE_PORT, service.sourcePort)
            awaitState { diagnostics.any { it.startsWith("USBMUX_PROTOCOL_FAILURE") } }
            val failure = diagnostics.single { it.startsWith("USBMUX_PROTOCOL_FAILURE") }
            assertTrue(failure, failure.contains("reason=TCP_HEADER_LENGTH_INVALID"))
            assertTrue(failure, failure.contains("socketRole=SERVICE"))
            assertTrue(failure, failure.contains("tcpFlags=ACK"))
            assertTrue(failure, failure.contains("payloadLength=UNKNOWN"))
            assertTrue(failure, failure.contains("localSocketMatched=true; remotePortMatched=true"))
            host.close()
        }
    }

    @Test fun unknownMuxProtocolUnknownSocketAndBadMuxLengthReportDistinctReasons() {
        val cases = listOf("protocol", "socket", "length")
        for (mode in cases) {
            val pipe = FakePipe()
            val diagnostics = CopyOnWriteArrayList<String>()
            val host = Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe, onDiagnostic = { diagnostics.add(it); Unit })
            host.connect(Iap2UsbMuxHost.LOCKDOWN_PORT)
            when (mode) {
                "protocol" -> pipe.injectUnknownProtocol(99)
                "socket" -> pipe.inject(SERVICE_PORT, UNKNOWN_CLIENT_PORT, FakePipe.TCP_ACK)
                "length" -> pipe.injectInvalidMuxLength()
            }
            awaitState { diagnostics.any { it.startsWith("USBMUX_PROTOCOL_FAILURE") } }
            val failure = diagnostics.single { it.startsWith("USBMUX_PROTOCOL_FAILURE") }
            val expected = when (mode) {
                "protocol" -> "UNEXPECTED_USBMUX_PROTOCOL"
                "socket" -> "UNKNOWN_TCP_DESTINATION"
                else -> "MUX_FRAME_LENGTH_INVALID"
            }
            assertTrue("$mode: $failure", failure.contains("reason=$expected"))
            assertTrue("$mode: $failure", failure.contains("stopSessionStage=BEFORE_STOPSESSION"))
            if (mode != "socket") {
                assertTrue(failure.contains("socketRole=UNKNOWN"))
                assertTrue(failure.contains("localSocketMatched=false"))
            }
            assertFalse(failure.contains(UNKNOWN_CLIENT_PORT.toString()))
            host.close()
        }
    }

    @Test fun usbReadProtocolFailureGetsSafeFallbackReasonAndHostSnapshot() {
        val pipe = FakePipe()
        val diagnostics = CopyOnWriteArrayList<String>()
        val host = Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe, onDiagnostic = { diagnostics.add(it); Unit })
        pipe.failNextReadWithProtocol()
        awaitState { diagnostics.any { it.startsWith("USBMUX_PROTOCOL_FAILURE") } }
        val failure = diagnostics.single { it.startsWith("USBMUX_PROTOCOL_FAILURE") }
        assertTrue(failure, failure.contains("reason=USB_PIPE_EMPTY_COMPLETION"))
        assertTrue(failure, failure.contains("socketRole=UNKNOWN"))
        assertTrue(failure, failure.contains("tcpFlags=UNKNOWN; payloadLength=UNKNOWN"))
        assertTrue(failure, failure.contains("hostState=ACTIVE; hostFailure=NONE"))
        host.close()
    }

    private fun awaitState(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.yield()
        assertTrue("Timed out waiting for USBMUX reader state", condition())
    }

    private companion object {
        const val SERVICE_PORT = 23456
        const val UNKNOWN_CLIENT_PORT = 23457
    }
}
