package com.shilapi.xcertplay.transport

import android.util.Log
import java.io.Closeable
import java.util.ArrayDeque

/**
 * USBMUX version-2 host over an already claimed iPhone bulk pipe.
 *
 * This is deliberately limited to USBMUX framing and one minimal outbound TCP connection. It
 * does not parse lockdown messages, iAP2, TLS, NCM, AirPlay, or any media protocol. All calls
 * may block and must run away from Android's main thread.
 */
class Iap2UsbMuxHost private constructor(
    private val pipe: UsbMuxBulkPipe,
    private val readTimeoutMillis: Long,
    private val onDiagnostic: (String) -> Unit,
    private val handshakeTimeoutMillis: Long,
    private val diagnosticStage: () -> String = { "NOT_APPLICABLE" },
) : Closeable {
    private var strictDiagnostic = false
    private val stateLock = Any()
    private val writeLock = Any()
    private val connections = mutableMapOf<Int, Iap2UsbMuxTcpConnection>()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var nextMuxSequence = 0
    private var nextMuxAcknowledgement = 0
    private var nextSourcePort = FIRST_SOURCE_PORT
    @Volatile private var diagnosticServicePort: Int? = null
    private var diagnosticServiceConnectAttempted = false
    private var closingSocketAckFrames = 0
    private var closingSocketFinFrames = 0
    private var closingSocketRstFrames = 0
    private var closingSocketRstPayloadFrames = 0
    private var closingSocketRstPayloadBytes = 0
    private lateinit var readerThread: Thread
    private val receiveFrames = UsbMuxFrameBuffer { line ->
        Log.w("xcertplay-usb", line)
        emitDiagnostic(line)
    }

    /** Opens a TCP byte stream to the iPhone service on [destinationPort]. */
    fun connect(
        destinationPort: Int = LOCKDOWN_PORT,
        timeoutMillis: Long = CONNECT_TIMEOUT_MILLIS,
    ): Iap2UsbMuxTcpConnection {
        require(destinationPort in 1..0xffff) { "destinationPort must be a valid TCP port" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val connection = synchronized(stateLock) {
            checkOpenLocked()
            if (strictDiagnostic) {
                if (destinationPort == LOCKDOWN_PORT) {
                    check(connections.values.none { it.destinationPort == LOCKDOWN_PORT }) {
                        "Only one Lockdown TCP connection is permitted"
                    }
                } else {
                    check(destinationPort == diagnosticServicePort && !diagnosticServiceConnectAttempted) {
                        "Only the authorized one-shot diagnostic service port is permitted"
                    }
                    diagnosticServiceConnectAttempted = true
                }
            }
            val sourcePort = allocateSourcePortLocked()
            Iap2UsbMuxTcpConnection(this, sourcePort, destinationPort).also {
                connections[sourcePort] = it
            }
        }
        try {
            connection.beginConnect()
            if (!connection.awaitConnected(timeoutMillis)) {
                connection.abort()
                throw IphoneUsbException.TimedOut("USBMUX TCP connection to port $destinationPort timed out")
            }
            return connection
        } catch (error: IphoneUsbException) {
            removeConnection(connection)
            throw error
        }
    }

    /** Grants one additional strict-diagnostic connection after its Lockdown StartService response. */
    fun authorizeDiagnosticServicePort(destinationPort: Int) = synchronized(stateLock) {
        check(strictDiagnostic) { "Service-port authorization is diagnostic-only" }
        checkOpenLocked()
        require(destinationPort in 1..0xffff && destinationPort != LOCKDOWN_PORT) {
            "Invalid diagnostic service port"
        }
        check(diagnosticServicePort == null && !diagnosticServiceConnectAttempted) {
            "Only one service port can be authorized"
        }
        check(connections.values.any { it.destinationPort == LOCKDOWN_PORT }) {
            "An active Lockdown connection is required before service authorization"
        }
        diagnosticServicePort = destinationPort
    }

    override fun close() {
        val activeConnections = synchronized(stateLock) {
            if (closed) return
            closed = true
            connections.values.toList().also { connections.clear() }
        }
        activeConnections.forEach(Iap2UsbMuxTcpConnection::closeFromHost)
        pipe.close()
        if (::readerThread.isInitialized && Thread.currentThread() !== readerThread) {
            try {
                readerThread.join(readTimeoutMillis + CLOSE_JOIN_MARGIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    internal fun sendTcp(
        sourcePort: Int,
        destinationPort: Int,
        sequence: Int,
        acknowledgement: Int,
        flags: Int,
        payload: ByteArray,
    ) {
        if (payload.size > 512) {
            Log.i("xcertplay-usb", "usbmux TCP TX bytes=${payload.size}; flags=${classifyTcpFlags(flags, payload.size)}; payload=REDACTED")
        }
        val tcp = ByteArray(TCP_HEADER_BYTES + payload.size)
        putU16(tcp, 0, sourcePort)
        putU16(tcp, 2, destinationPort)
        putU32(tcp, 4, sequence)
        putU32(tcp, 8, acknowledgement)
        tcp[12] = (TCP_HEADER_BYTES / 4 shl 4).toByte()
        tcp[13] = flags.toByte()
        putU16(tcp, 14, TCP_WINDOW_FIELD)
        payload.copyInto(tcp, TCP_HEADER_BYTES)
        sendFrame(PROTOCOL_TCP, tcp)
    }

    internal fun removeConnection(connection: Iap2UsbMuxTcpConnection) {
        synchronized(stateLock) {
            if (connections[connection.sourcePort] === connection) {
                if (strictDiagnostic && connection.isClosedForHost()) return
                connections.remove(connection.sourcePort)
            }
        }
    }

    internal fun isStrictDiagnostic(): Boolean = synchronized(stateLock) { strictDiagnostic && !closed }

    internal fun recordClosingSocketFrame(flags: Int) = synchronized(stateLock) {
        when (flags) {
            Iap2UsbMuxTcpConnection.TCP_ACK -> closingSocketAckFrames++
            Iap2UsbMuxTcpConnection.TCP_FIN, Iap2UsbMuxTcpConnection.TCP_FIN or Iap2UsbMuxTcpConnection.TCP_ACK ->
                closingSocketFinFrames++
            Iap2UsbMuxTcpConnection.TCP_RST, Iap2UsbMuxTcpConnection.TCP_RST or Iap2UsbMuxTcpConnection.TCP_ACK ->
                closingSocketRstFrames++
        }
    }

    internal fun isRetiredServiceConnection(connection: Iap2UsbMuxTcpConnection): Boolean = synchronized(stateLock) {
        strictDiagnostic && !closed && diagnosticServicePort == connection.destinationPort && connection.isClosedForHost()
    }

    internal fun recordClosingSocketRstPayload(payloadLength: Int) = synchronized(stateLock) {
        closingSocketRstPayloadFrames++
        closingSocketRstPayloadBytes += payloadLength
    }

    internal fun requiresStrictCleanup(): Boolean = strictDiagnostic

    fun verifyReadOnlyDiagnostic() = synchronized(stateLock) {
        check(strictDiagnostic) { "Only the isolated diagnostic host exposes this check" }
        checkOpenLocked()
    }

    fun diagnosticState(): String = synchronized(stateLock) {
        "USBMUX_HOST_ACTIVE=${!closed && failure == null}; hostFailure=${failure?.javaClass?.simpleName ?: "none"}; " +
            "closingSocketACK=$closingSocketAckFrames; closingSocketFIN=$closingSocketFinFrames; " +
            "closingSocketRST=$closingSocketRstFrames; closingSocketRSTPayloadFrames=$closingSocketRstPayloadFrames; " +
            "closingSocketRSTPayloadBytes=$closingSocketRstPayloadBytes"
    }

    internal fun closedSocketProtocolFailure(
        connection: Iap2UsbMuxTcpConnection,
        reasonCode: String,
        flags: Int,
        sequence: Int,
        acknowledgement: Int,
        payloadLength: Int,
    ): Nothing {
        reportProtocolFailure(reasonCode, socketRole(connection), classifyTcpFlags(flags, payloadLength), payloadLength,
            true, true, connection.sequenceIsExpected(sequence), connection.acknowledgementIsInRange(acknowledgement))
        throw IphoneUsbException.Protocol("$PROTOCOL_DIAGNOSTIC_PREFIX$reasonCode")
    }

    private fun protocolFailure(
        reasonCode: String,
        socketRole: String,
        tcpFlags: String,
        payloadLength: Int?,
        localSocketMatched: Boolean,
        remotePortMatched: Boolean,
        sequenceExpected: Boolean,
        acknowledgementInRange: Boolean,
    ): Nothing {
        reportProtocolFailure(reasonCode, socketRole, tcpFlags, payloadLength, localSocketMatched,
            remotePortMatched, sequenceExpected, acknowledgementInRange)
        throw IphoneUsbException.Protocol("$PROTOCOL_DIAGNOSTIC_PREFIX$reasonCode")
    }

    private fun reportProtocolFailure(
        reasonCode: String,
        socketRole: String,
        tcpFlags: String,
        payloadLength: Int?,
        localSocketMatched: Boolean,
        remotePortMatched: Boolean,
        sequenceExpected: Boolean,
        acknowledgementInRange: Boolean,
    ) {
        val host = synchronized(stateLock) {
            "hostState=${if (closed) "CLOSED" else if (failure == null) "ACTIVE" else "FAILED"}; " +
                "hostFailure=${failure?.javaClass?.simpleName ?: "NONE"}; mappedSockets=${connections.size}; " +
                "closingSocketACK=$closingSocketAckFrames; closingSocketFIN=$closingSocketFinFrames; " +
                "closingSocketRST=$closingSocketRstFrames; closingSocketRSTPayloadFrames=$closingSocketRstPayloadFrames; " +
                "closingSocketRSTPayloadBytes=$closingSocketRstPayloadBytes"
        }
        val stage = runCatching(diagnosticStage).getOrDefault("UNKNOWN")
        emitDiagnostic("USBMUX_PROTOCOL_FAILURE reason=$reasonCode; socketRole=$socketRole; tcpFlags=$tcpFlags; " +
            "payloadLength=${payloadLength?.toString() ?: "UNKNOWN"}; localSocketMatched=$localSocketMatched; " +
            "remotePortMatched=$remotePortMatched; tcpSequenceExpectedMatch=$sequenceExpected; " +
            "tcpAcknowledgementWithinSentRange=$acknowledgementInRange; stopSessionStage=$stage; $host")
    }

    private fun socketRole(connection: Iap2UsbMuxTcpConnection?): String = when {
        connection == null -> "UNKNOWN"
        connection.destinationPort == LOCKDOWN_PORT -> if (connection.isClosedForHost()) "RETIRED_LOCKDOWN" else "LOCKDOWN"
        connection.destinationPort == diagnosticServicePort -> if (connection.isClosedForHost()) "RETIRED_SERVICE" else "SERVICE"
        else -> "UNKNOWN"
    }

    private fun classifyTcpFlags(flags: Int, payloadLength: Int?): String {
        val names = buildList {
            if (flags and Iap2UsbMuxTcpConnection.TCP_SYN != 0) add("SYN")
            if (flags and Iap2UsbMuxTcpConnection.TCP_ACK != 0) add("ACK")
            if (flags and Iap2UsbMuxTcpConnection.TCP_FIN != 0) add("FIN")
            if (flags and Iap2UsbMuxTcpConnection.TCP_RST != 0) add("RST")
            if (flags and TCP_PSH != 0) add("PSH")
            if (flags and TCP_URG != 0) add("URG")
            if (flags and TCP_KNOWN_FLAGS.inv() != 0) add("OTHER")
            if (payloadLength != null && payloadLength > 0) add("PAYLOAD")
        }
        return names.ifEmpty { listOf(if (payloadLength == null) "UNKNOWN" else "NONE") }.joinToString("|")
    }

    private fun protocolReason(message: String?): String = when {
        message?.startsWith("Invalid USBMUX frame length") == true -> "MUX_FRAME_LENGTH_INVALID"
        message?.startsWith("USBMUX TCP frame is shorter") == true -> "TCP_HEADER_TOO_SHORT"
        message?.startsWith("Invalid USBMUX TCP header length") == true -> "TCP_HEADER_LENGTH_INVALID"
        message?.startsWith("Unexpected USBMUX protocol") == true -> "UNEXPECTED_USBMUX_PROTOCOL"
        message?.startsWith("Unexpected TCP connection destination") == true -> "UNKNOWN_TCP_DESTINATION"
        message?.startsWith("USBMUX TCP peer port did not match") == true -> "TCP_REMOTE_PORT_MISMATCH"
        message?.startsWith("TCP payload received after") == true -> "CLOSED_SOCKET_PAYLOAD"
        message?.startsWith("Invalid TCP control flags") == true -> "CLOSED_SOCKET_FLAGS_INVALID"
        message?.startsWith("Empty USBMUX completion") == true -> "USB_PIPE_EMPTY_COMPLETION"
        message?.startsWith("Version response length") == true -> "MUX_VERSION_REPLY_LENGTH_INVALID"
        message?.startsWith("Invalid version fields") == true -> "MUX_VERSION_REPLY_FIELDS_INVALID"
        message?.startsWith("Invalid USBMUX version reply") == true -> "MUX_VERSION_REPLY_INVALID"
        else -> "USBMUX_PROTOCOL_FAILURE_UNCLASSIFIED"
    }

    private fun emitDiagnostic(line: String) {
        runCatching { onDiagnostic(line) }
    }

    private fun begin() {
        val version = UsbMuxVersionPacket.request()
        pipe.write(version, handshakeTimeoutMillis.toInt())
        // The phone replies with the same proto=0, length=20, version=2 packet. Protocol 1 is not
        // a distinct "version reply" here; waiting for it discards the valid reply and times out.
        val deadline = System.nanoTime() + handshakeTimeoutMillis * NANOS_PER_MILLISECOND
        var staleFrames = 0
        var reply: UsbMuxFrame
        while (true) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) {
                throw IphoneUsbException.TimedOut("Timed out waiting for the USBMUX version reply")
            }
            val remainingMillis = (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
            reply = takeFrame(remainingMillis)
                ?: throw IphoneUsbException.TimedOut("Timed out waiting for the USBMUX version reply")
            if (
                reply.protocol == PROTOCOL_VERSION &&
                reply.length == VERSION_MESSAGE_BYTES &&
                reply.word8 == USBMUX_VERSION
            ) {
                break
            }
            // Android can leave already-received TCP payloads queued in the bulk endpoint when
            // an app process is replaced. They belong to the previous host instance and must not
            // be mistaken for the version reply sent in response to the new handshake.
            if (reply.protocol != PROTOCOL_TCP || ++staleFrames > MAX_STALE_HANDSHAKE_FRAMES) {
                throw IphoneUsbException.Protocol(
                    "Invalid USBMUX version reply: proto=${reply.protocol} " +
                        "length=${reply.length} version=${reply.word8}",
                )
            }
            Log.i("xcertplay-usb", "discarding stale usbmux TCP frame before version reply")
        }
        Log.i("xcertplay-usb", "usbmux version accepted: ${reply.word8}")
        // Optional reply padding is handled by the incremental framer. Retain a possible
        // fragmented/coalesced next frame instead of discarding the entire remainder.
        startNegotiatedReader()
    }

    private fun beginReadOnlyDiagnostic() {
        pipe.write(UsbMuxVersionPacket.request(), handshakeTimeoutMillis.toInt())
        val reply = pipe.read(handshakeTimeoutMillis)
            ?: throw IphoneUsbException.TimedOut("Version response timeout")
        if (reply.size != VERSION_MESSAGE_BYTES) {
            throw IphoneUsbException.Protocol("Version response length=${reply.size}; expected20; no drain or continuation")
        }
        if (readU32(reply, 0) != PROTOCOL_VERSION || readU32(reply, 4) != VERSION_MESSAGE_BYTES ||
            readU32(reply, 8) != USBMUX_VERSION) {
            throw IphoneUsbException.Protocol("Invalid version fields protocol=${readU32(reply, 0)} length=${readU32(reply, 4)} major=${readU32(reply, 8)}")
        }
        nextMuxAcknowledgement = readU16(reply, 12)
        startNegotiatedReader()
    }

    private fun startNegotiatedReader() {
        sendFrame(PROTOCOL_SETUP, byteArrayOf(SETUP_VALUE.toByte()))
        readerThread = Thread(::readerLoop, "iap2-usbmux-reader").apply {
            isDaemon = true
            start()
        }
    }

    /** Reads one complete USBMUX frame, keeping partial data buffered across reads. */
    private fun takeFrame(timeoutMillis: Long): UsbMuxFrame? {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        while (true) {
            synchronized(stateLock) {
                if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX host is closed")
                receiveFrames.takeFrame()?.let { frame ->
                    // LIVI only trusts the length field on receive: iPhone replies do not
                    // carry the 0xFEEDFACE word in the header's fourth field.
                    Log.i(
                        "xcertplay-usb",
                        "usbmux rx proto=${frame.protocol} length=${frame.length} word8=0x" +
                            frame.word8.toUInt().toString(16),
                    )
                    nextMuxAcknowledgement = frame.sequence
                    return frame
                }
            }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return null
            val remainingMillis = (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
            val bytes = pipe.read(remainingMillis) ?: continue
            synchronized(stateLock) {
                receiveFrames.append(bytes)
            }
        }
    }

    private fun sendFrame(protocol: Int, payload: ByteArray) = synchronized(writeLock) {
        val sequenceAndAcknowledgement = synchronized(stateLock) {
            checkOpenLocked()
            nextMuxSequence to nextMuxAcknowledgement
        }
        val frame = ByteArray(MUX_HEADER_BYTES + payload.size)
        putU32(frame, 0, protocol)
        putU32(frame, 4, frame.size)
        putU32(frame, 8, MUX_MAGIC)
        putU16(frame, 12, sequenceAndAcknowledgement.first)
        putU16(frame, 14, sequenceAndAcknowledgement.second)
        payload.copyInto(frame, MUX_HEADER_BYTES)
        try {
            pipe.write(frame, WRITE_TIMEOUT_MILLIS)
        } catch (error: IphoneUsbException) {
            val failure = if (error is IphoneUsbException.Protocol) {
                val tcp = payload.takeIf { protocol == PROTOCOL_TCP && it.size >= TCP_HEADER_BYTES }
                val sourcePort = tcp?.let { readU16(it, 0) }
                val destinationPort = tcp?.let { readU16(it, 2) }
                val connection = sourcePort?.let { source -> synchronized(stateLock) { connections[source] } }
                reportProtocolFailure(
                    "USBMUX_PIPE_WRITE_PROTOCOL_FAILURE",
                    socketRole(connection),
                    tcp?.let { classifyTcpFlags(it[13].toInt() and 0xff, it.size - TCP_HEADER_BYTES) } ?: "UNKNOWN",
                    tcp?.size?.minus(TCP_HEADER_BYTES),
                    connection != null,
                    connection != null && destinationPort == connection.destinationPort,
                    connection?.sequenceIsExpected(tcp?.let { readU32(it, 4) } ?: 0) ?: false,
                    connection?.acknowledgementIsInRange(tcp?.let { readU32(it, 8) } ?: 0) ?: false,
                )
                IphoneUsbException.Protocol("$PROTOCOL_DIAGNOSTIC_PREFIX" + "USBMUX_PIPE_WRITE_PROTOCOL_FAILURE")
            } else error
            fail(failure)
            throw failure
        }
        synchronized(stateLock) {
            nextMuxSequence = (nextMuxSequence + 1) and 0xffff
        }
    }

    private fun readerLoop() {
        try {
            while (true) {
                synchronized(stateLock) {
                    if (closed) return
                }
                val frame = takeFrame(readTimeoutMillis) ?: continue
                if (frame.protocol == PROTOCOL_TCP) dispatchTcp(frame.payload)
                else if (strictDiagnostic) {
                    protocolFailure("UNEXPECTED_USBMUX_PROTOCOL", socketRole = "UNKNOWN", tcpFlags = "UNKNOWN",
                        payloadLength = null, localSocketMatched = false, remotePortMatched = false,
                        sequenceExpected = false, acknowledgementInRange = false)
                }
            }
        } catch (error: IphoneUsbException) {
            if (error is IphoneUsbException.Protocol && error.message?.startsWith(PROTOCOL_DIAGNOSTIC_PREFIX) != true) {
                reportProtocolFailure(
                    protocolReason(error.message),
                    socketRole = "UNKNOWN",
                    tcpFlags = "UNKNOWN",
                    payloadLength = null,
                    localSocketMatched = false,
                    remotePortMatched = false,
                    sequenceExpected = false,
                    acknowledgementInRange = false,
                )
            }
            fail(error)
        } catch (error: RuntimeException) {
            fail(IphoneUsbException.DeviceUnavailable("USBMUX reader failed", error))
        }
    }

    private fun dispatchTcp(frame: ByteArray) {
        val offset = 0
        val length = frame.size
        if (length < TCP_HEADER_BYTES) {
            protocolFailure("TCP_HEADER_TOO_SHORT", socketRole = "UNKNOWN", tcpFlags = "UNKNOWN",
                payloadLength = null, localSocketMatched = false, remotePortMatched = false,
                sequenceExpected = false, acknowledgementInRange = false)
        }
        val remotePort = readU16(frame, offset)
        val destinationPort = readU16(frame, offset + 2)
        val flags = frame[offset + 13].toInt() and 0xff
        val sequence = readU32(frame, offset + 4)
        val acknowledgement = readU32(frame, offset + 8)
        val connection = synchronized(stateLock) { connections[destinationPort] }
        val payloadLength: Int? = null
        val role = socketRole(connection)
        val remotePortMatches = connection != null && remotePort == connection.destinationPort
        val sequenceExpected = connection?.sequenceIsExpected(sequence) ?: false
        val acknowledgementInRange = connection?.acknowledgementIsInRange(acknowledgement) ?: false
        val tcpHeaderBytes = ((frame[offset + 12].toInt() ushr 4) and 0x0f) * 4
        if (tcpHeaderBytes < TCP_HEADER_BYTES || tcpHeaderBytes > length) {
            protocolFailure("TCP_HEADER_LENGTH_INVALID", role, classifyTcpFlags(flags, null), payloadLength,
                connection != null, remotePortMatches, sequenceExpected, acknowledgementInRange)
        }
        val payloadBytes = length - tcpHeaderBytes
        if (length == tcpHeaderBytes) {
            Log.i("xcertplay-usb", "usbmux TCP control socketRole=$role flags=${classifyTcpFlags(flags, 0)}; " +
                "localSocketMatched=${connection != null}; remotePortMatched=$remotePortMatches; " +
                "tcpSequenceExpectedMatch=$sequenceExpected; tcpAcknowledgementWithinSentRange=$acknowledgementInRange")
        }
        if (connection == null) {
            if (strictDiagnostic) protocolFailure("UNKNOWN_TCP_DESTINATION", "UNKNOWN", classifyTcpFlags(flags, payloadBytes),
                payloadBytes, false, false, false, false)
            return
        }
        if (strictDiagnostic && remotePort != connection.destinationPort) {
            protocolFailure("TCP_REMOTE_PORT_MISMATCH", role, classifyTcpFlags(flags, payloadBytes), payloadBytes,
                true, false, sequenceExpected, acknowledgementInRange)
        }
        connection.onPacket(
            flags = flags,
            sequence = sequence,
            acknowledgement = acknowledgement,
            payload = frame.copyOfRange(offset + tcpHeaderBytes, offset + length),
        )
    }

    private fun fail(error: IphoneUsbException) {
        val activeConnections = synchronized(stateLock) {
            if (closed) return
            failure = failure ?: error
            closed = true
            connections.values.toList().also { connections.clear() }
        }
        activeConnections.forEach { it.closeFromHost(error) }
        pipe.close()
    }

    private fun allocateSourcePortLocked(): Int {
        repeat(0xffff) {
            val candidate = nextSourcePort
            nextSourcePort = if (candidate == 0xffff) FIRST_SOURCE_PORT else candidate + 1
            if (candidate !in connections) return candidate
        }
        throw IphoneUsbException.DeviceUnavailable("USBMUX has no free TCP source ports")
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX host is closed")
    }

    companion object {
        const val LOCKDOWN_PORT = 62078
        private const val PROTOCOL_DIAGNOSTIC_PREFIX = "USBMUX_DIAGNOSTIC:"
        private const val TCP_PSH = 0x08
        private const val TCP_URG = 0x20
        private const val TCP_KNOWN_FLAGS = 0x3f

        private const val PROTOCOL_VERSION = 0
        private const val PROTOCOL_SETUP = 2
        private const val PROTOCOL_TCP = 6
        private const val USBMUX_VERSION = 2
        private const val SETUP_VALUE = 0x07
        private const val MUX_MAGIC = 0xfeedface.toInt()
        private const val VERSION_MESSAGE_BYTES = 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MUX_HEADER_BYTES = 16
        private const val TCP_HEADER_BYTES = 20
        private const val TCP_WINDOW_FIELD = 512
        private const val FIRST_SOURCE_PORT = 1
        private const val HANDSHAKE_TIMEOUT_MILLIS = 60_000L
        private const val MAX_STALE_HANDSHAKE_FRAMES = 32
        private const val CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val WRITE_TIMEOUT_MILLIS = 2_000
        private const val CLOSE_JOIN_MARGIN_MILLIS = 100L

        /** Strict one-completion version exchange, one setup, then TCP reader; no Lockdown calls. */
        fun openReadOnlyDiagnostic(
            pipe: UsbMuxBulkPipe,
            onDiagnostic: (String) -> Unit = {},
            diagnosticStage: () -> String = { "BEFORE_STOPSESSION" },
        ): Iap2UsbMuxHost =
            Iap2UsbMuxHost(pipe, 500, onDiagnostic, 1_000, diagnosticStage).also {
                try {
                    it.strictDiagnostic = true
                    it.beginReadOnlyDiagnostic()
                } catch (error: Throwable) {
                    if (error is IphoneUsbException.Protocol) {
                        it.reportProtocolFailure(it.protocolReason(error.message), "UNKNOWN", "UNKNOWN", null,
                            false, false, false, false)
                    }
                    try { it.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                    throw error
                }
            }

        /** Performs the USBMUX v2 handshake and starts the framed reader. */
        fun open(
            pipe: UsbMuxBulkPipe,
            readTimeoutMillis: Long = 1_000,
            onDiagnostic: (String) -> Unit = {},
            handshakeTimeoutMillis: Long = HANDSHAKE_TIMEOUT_MILLIS,
        ): Iap2UsbMuxHost {
            require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
            require(handshakeTimeoutMillis in 1..Int.MAX_VALUE.toLong()) { "Invalid handshake timeout" }
            return Iap2UsbMuxHost(pipe, readTimeoutMillis, onDiagnostic, handshakeTimeoutMillis).also {
                try {
                    it.begin()
                } catch (error: Throwable) {
                    try {
                        it.close()
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                    throw error
                }
            }
        }

        private fun putU16(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value ushr 8).toByte()
            target[offset + 1] = value.toByte()
        }

        private fun putU32(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value ushr 24).toByte()
            target[offset + 1] = (value ushr 16).toByte()
            target[offset + 2] = (value ushr 8).toByte()
            target[offset + 3] = value.toByte()
        }

        private fun readU16(source: ByteArray, offset: Int): Int =
            ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

        private fun readU32(source: ByteArray, offset: Int): Int =
            ((source[offset].toInt() and 0xff) shl 24) or
                ((source[offset + 1].toInt() and 0xff) shl 16) or
                ((source[offset + 2].toInt() and 0xff) shl 8) or
                (source[offset + 3].toInt() and 0xff)
    }
}

/** A blocking TCP byte stream carried by [Iap2UsbMuxHost]. */
class Iap2UsbMuxTcpConnection internal constructor(
    private val host: Iap2UsbMuxHost,
    internal val sourcePort: Int,
    internal val destinationPort: Int,
) : BlockingDuplexByteStream {
    private val stateLock = Object()
    private val writeLock = Any()
    private val received = ArrayDeque<ByteArray>()
    private var nextSequence = 0
    private var nextAcknowledgement = 0
    private var connected = false
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var finObserved = false
    private var rstObserved = false
    private var eofObserved = false
    private var timeoutObserved = false

    fun diagnosticState(): String = synchronized(stateLock) {
        "TCP_OPEN=${connected && !closed && failure == null}; FIN=$finObserved; RST=$rstObserved; " +
            "EOF=$eofObserved; TCP_RECEIVE_TIMEOUT=$timeoutObserved"
    }

    internal fun sequenceIsExpected(observed: Int): Boolean = synchronized(stateLock) {
        observed == nextAcknowledgement
    }

    internal fun acknowledgementIsInRange(observed: Int): Boolean = synchronized(stateLock) {
        java.lang.Integer.compareUnsigned(observed, nextSequence) <= 0
    }

    internal fun isClosedForHost(): Boolean = synchronized(stateLock) { closed }

    /** Sends [data] as an ordered byte stream, split into USBMUX TCP payloads of at most 16 KiB. */
    override fun send(data: ByteArray) {
        synchronized(stateLock) { checkConnectedLocked() }
        var offset = 0
        while (offset < data.size) {
            val count = minOf(MAX_SEND_PAYLOAD_BYTES, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + count)
            synchronized(writeLock) {
                val sequenceAndAck = synchronized(stateLock) {
                    checkConnectedLocked()
                    nextSequence to nextAcknowledgement
                }
                host.sendTcp(
                    sourcePort,
                    destinationPort,
                    sequenceAndAck.first,
                    sequenceAndAck.second,
                    TCP_ACK,
                    chunk,
                )
                synchronized(stateLock) { nextSequence += count }
            }
            offset += count
        }
    }

    /**
     * Receives up to [maxBytes] from the byte stream. Returns null on timeout and an empty array
     * after FIN or local close; a peer RST is reported as a transport failure.
     */
    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        synchronized(stateLock) {
            while (received.isEmpty() && !closed) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) { timeoutObserved = true; return null }
                try {
                    stateLock.wait(remainingNanos / NANOS_PER_MILLISECOND, (remainingNanos % NANOS_PER_MILLISECOND).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IphoneUsbException.DeviceUnavailable("Interrupted while waiting for USBMUX TCP data")
                }
            }
            failure?.let { throw it }
            if (received.isEmpty()) {
                eofObserved = true
                return ByteArray(0)
            }
            val packet = received.removeFirst()
            return if (packet.size <= maxBytes) {
                packet
            } else {
                received.addFirst(packet.copyOfRange(maxBytes, packet.size))
                packet.copyOf(maxBytes)
            }
        }
    }

    override fun close() {
        var cleanupFailure: IphoneUsbException? = null
        val fin = synchronized(stateLock) {
            if (closed) return
            closed = true
            stateLock.notifyAll()
            if (connected) {
                val sequence = nextSequence
                nextSequence += 1
                sequence to nextAcknowledgement
            } else null
        }
        if (fin != null) {
            try {
                synchronized(writeLock) {
                    host.sendTcp(
                        sourcePort,
                        destinationPort,
                        fin.first,
                        fin.second,
                        TCP_FIN or TCP_ACK,
                        ByteArray(0),
                    )
                }
            } catch (error: IphoneUsbException) {
                if (host.requiresStrictCleanup()) cleanupFailure = error
                // The host may have already closed its USB pipe; the closed state remains final.
            }
        }
        host.removeConnection(this)
        cleanupFailure?.let { throw it }
    }

    internal fun beginConnect() {
        sendControl(TCP_SYN)
    }

    internal fun awaitConnected(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        synchronized(stateLock) {
            while (!connected && !closed) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return false
                try {
                    stateLock.wait(remainingNanos / NANOS_PER_MILLISECOND, (remainingNanos % NANOS_PER_MILLISECOND).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IphoneUsbException.DeviceUnavailable("Interrupted while connecting USBMUX TCP")
                }
            }
            failure?.let { throw it }
            return connected && !closed
        }
    }

    internal fun abort() {
        val shouldSendReset = synchronized(stateLock) {
            if (closed) return
            closed = true
            stateLock.notifyAll()
            true
        }
        if (shouldSendReset) {
            try {
                sendControl(TCP_RST or TCP_ACK)
            } catch (_: IphoneUsbException) {
                // A failed reset cannot make the local connection usable again.
            }
        }
        host.removeConnection(this)
    }

    internal fun closeFromHost(error: IphoneUsbException? = null) = synchronized(stateLock) {
        if (closed) return@synchronized
        failure = error ?: failure
        closed = true
        stateLock.notifyAll()
    }

    internal fun onPacket(flags: Int, sequence: Int, acknowledgement: Int, payload: ByteArray) {
        if (isClosedForHost()) {
            handleClosingPacket(flags, sequence, acknowledgement, payload)
            return
        }
        if ((flags and TCP_RST) != 0) {
            synchronized(stateLock) {
                rstObserved = true
                failure = IphoneUsbException.DeviceUnavailable("USBMUX TCP connection was reset by the peer")
                closed = true
                stateLock.notifyAll()
            }
            host.removeConnection(this)
            return
        }
        if ((flags and TCP_SYN) != 0 && (flags and TCP_ACK) != 0) {
            synchronized(stateLock) {
                if (closed) return
                nextSequence += 1
                nextAcknowledgement = sequence + 1
            }
            sendControl(TCP_ACK)
            synchronized(stateLock) {
                if (!closed) {
                    connected = true
                    stateLock.notifyAll()
                }
            }
            return
        }
        if (payload.isNotEmpty()) {
            synchronized(stateLock) {
                if (closed) return
                nextAcknowledgement += payload.size
                received.addLast(payload)
                stateLock.notifyAll()
            }
            sendControl(TCP_ACK)
        }
        if ((flags and TCP_FIN) != 0) {
            var alreadyClosed = false
            synchronized(stateLock) {
                finObserved = true
                if (closed) alreadyClosed = true
                else nextAcknowledgement += 1
            }
            if (alreadyClosed) {
                handleClosingPacket(flags, sequence, acknowledgement, payload)
                return
            }
            sendControl(TCP_ACK)
            synchronized(stateLock) {
                closed = true
                stateLock.notifyAll()
            }
            host.removeConnection(this)
        }
    }

    private fun handleClosingPacket(flags: Int, sequence: Int, acknowledgement: Int, payload: ByteArray) {
        if (!host.isStrictDiagnostic()) return
        if (payload.isNotEmpty()) {
            val sequenceExpected = sequenceIsExpected(sequence)
            val acknowledgementInRange = acknowledgementIsInRange(acknowledgement)
            if (flags == TCP_RST && host.isRetiredServiceConnection(this) && sequenceExpected && acknowledgementInRange) {
                synchronized(stateLock) { rstObserved = true }
                host.recordClosingSocketFrame(flags)
                host.recordClosingSocketRstPayload(payload.size)
                return
            }
            val reason = when {
                flags != TCP_RST -> "CLOSED_SOCKET_PAYLOAD"
                !sequenceExpected -> "CLOSED_SOCKET_RST_SEQUENCE_INVALID"
                !acknowledgementInRange -> "CLOSED_SOCKET_RST_ACKNOWLEDGEMENT_INVALID"
                else -> "CLOSED_SOCKET_RST_PAYLOAD_UNAUTHORIZED"
            }
            host.closedSocketProtocolFailure(this, reason, flags, sequence, acknowledgement, payload.size)
        }
        when (flags) {
            TCP_ACK -> host.recordClosingSocketFrame(flags)
            TCP_RST, TCP_RST or TCP_ACK -> {
                synchronized(stateLock) { rstObserved = true }
                host.recordClosingSocketFrame(flags)
            }
            TCP_FIN, TCP_FIN or TCP_ACK -> {
                synchronized(stateLock) {
                    finObserved = true
                    nextAcknowledgement = sequence + 1
                }
                host.sendTcp(sourcePort, destinationPort, nextSequence, sequence + 1, TCP_ACK, ByteArray(0))
                host.recordClosingSocketFrame(flags)
            }
            else -> host.closedSocketProtocolFailure(this, "CLOSED_SOCKET_FLAGS_INVALID", flags, sequence, acknowledgement, 0)
        }
    }

    private fun sendControl(flags: Int) = synchronized(writeLock) {
        val sequenceAndAck = synchronized(stateLock) { nextSequence to nextAcknowledgement }
        host.sendTcp(
            sourcePort,
            destinationPort,
            sequenceAndAck.first,
            sequenceAndAck.second,
            flags,
            ByteArray(0),
        )
    }

    private fun checkConnectedLocked() {
        failure?.let { throw it }
        if (!connected || closed) throw IphoneUsbException.DeviceUnavailable("USBMUX TCP connection is not open")
    }

    companion object {
        internal const val TCP_FIN = 0x01
        internal const val TCP_SYN = 0x02
        internal const val TCP_RST = 0x04
        internal const val TCP_ACK = 0x10
        const val MAX_SEND_PAYLOAD_BYTES = 16 * 1024
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
