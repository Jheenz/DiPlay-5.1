package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import java.io.IOException
import java.io.InterruptedIOException

data class Iap2PreAuthResult(val linkReady: Boolean, val firstMessageId: Int?, val reason: String)

/** Only marker/SYN/ACK output: never queues a CSM message, identification or authentication. */
class Iap2PreAuthProbe(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    fun run(
        stream: BlockingDuplexByteStream,
        timeoutMillis: Long = 10_000,
        cancelled: () -> Boolean = { false },
        onEvent: (String) -> Unit = {},
    ): Iap2PreAuthResult {
        require(timeoutMillis in 1..30_000)
        val engine = Iap2LinkEngine(Iap2LinkConfig(maxOutgoing = 4, controlSessionVersion = 2))
        val framer = Iap2CsmFramer()
        val deadline = nowMillis() + timeoutMillis
        var ready = false
        var previousState = Iap2LinkEngine.State.IDLE
        engine.start(wiredInitiator = false, nowMillis = nowMillis())
        onEvent("first iAP2 framing event: transmitting detection marker; no CSM output")
        while (nowMillis() < deadline) {
            if (cancelled()) throw InterruptedIOException("Pre-auth probe cancelled")
            engine.advanceTime(nowMillis())
            val output = engine.takeOutput()
            if (output.isNotEmpty()) stream.send(output)
            if (engine.state() != previousState) {
                previousState = engine.state()
                onEvent("iAP2 link state=$previousState")
            }
            while (true) {
                when (val event = engine.pollEvent() ?: break) {
                    is Iap2LinkEngine.Event.Writable -> {
                        ready = event.value
                        if (ready) onEvent("iAP2 control session writable; pre-auth link established")
                    }
                    is Iap2LinkEngine.Event.Control -> {
                        val frame = framer.offer(event.bytes).firstOrNull() ?: continue
                        val reason = preAuthBoundary(frame.messageId)
                        onEvent("first iAP2 control frame id=0x${frame.messageId.toString(16)}; $reason")
                        return Iap2PreAuthResult(ready, frame.messageId, reason)
                    }
                    is Iap2LinkEngine.Event.Dead -> throw IOException(event.reason ?: "Peer closed RFCOMM before first control frame")
                    is Iap2LinkEngine.Event.Session -> onEvent("iAP2 non-control session=${event.sessionId}; ignored (no response)")
                }
            }
            val chunk = stream.recv(8192, minOf(100, (deadline - nowMillis()).coerceAtLeast(1)))
            if (chunk != null) {
                if (chunk.isEmpty()) engine.feedEof() else engine.feed(chunk, nowMillis())
            }
        }
        val reason = if (ready) "Pre-auth link established; no control frame within ${timeoutMillis}ms; identification/authentication not attempted"
            else "Timed out waiting for iAP2 detection/synchronization (${timeoutMillis}ms); RFCOMM alone does not prove iAP2"
        onEvent(reason)
        return Iap2PreAuthResult(ready, null, reason)
    }
}

internal fun preAuthBoundary(messageId: Int): String = when (messageId) {
    0x1d00 -> "Stopped at StartIdentification (0x1d00); no IdentificationInformation (0x1d01) sent; MFi boundary follows identification acceptance"
    0xaa00 -> "Stopped at RequestAuthenticationCertificate (0xaa00); no certificate (0xaa01) sent"
    0xaa02 -> "Stopped at RequestAuthenticationChallenge (0xaa02); no response (0xaa03) sent"
    in 0xaa00..0xaa05 -> "Stopped at authentication message; no authentication response sent"
    else -> "Stopped at first control message; no CSM response, identification or authentication sent"
}
