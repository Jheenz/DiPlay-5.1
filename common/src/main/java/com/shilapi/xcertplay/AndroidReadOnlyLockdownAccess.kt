package com.shilapi.xcertplay

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.ControlledLockdownPairing
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.LockdownPeerValidation
import com.shilapi.xcertplay.transport.LockdownPlistChannel
import com.shilapi.xcertplay.transport.LockdownPlistValue
import com.shilapi.xcertplay.transport.LockdownTlsUpgrade
import com.shilapi.xcertplay.transport.CarKitServiceTlsConnection
import com.shilapi.xcertplay.transport.TlsDuplexChannel
import com.shilapi.xcertplay.transport.Iap2UsbMuxTcpConnection
import com.shilapi.xcertplay.transport.ReadOnlyLockdownQueries
import com.shilapi.xcertplay.transport.UsbMuxBulkPipe
import com.shilapi.xcertplay.transport.UsbMuxVersionPacket
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class AndroidReadOnlyLockdownAccess(manager: UsbManager) : ReadOnlyLockdownAccess {
    private val scoped = AndroidActiveConfig5UsbMuxClaimAccess(manager)
    override fun devices() = scoped.devices()
    override fun open(device: PassiveUsbDevice): ReadOnlyLockdownConnection {
        versionInterface(device)
        return scoped.openScoped(device) { connection, target, unchanged -> Session(connection, target, unchanged) }
    }

    private class Session(
        private val connection: UsbDeviceConnection,
        private val target: UsbInterface,
        private val unchanged: () -> Unit,
    ) : ReadOnlyLockdownConnection, ControlledPairConnection, StartSessionConnection, LockdownTlsUpgrade, CarKitServiceTlsConnection {
        private var closed = false
        private var readAttempted = false
        private var active5 = false
        private var claimAttempted = false
        private var claimed = false
        private var initAttempted = false
        private var connectAttempted = false
        private var releaseAttempted = false
        private var transportStopped = false
        private var mux: Iap2UsbMuxHost? = null
        private val muxDiagnostics = ConcurrentLinkedQueue<String>()
        @Volatile private var stopSessionInProgress = false
        @Volatile private var stopSessionResponseReceived = false
        private var channel: LockdownPlistChannel? = null
        private var queries: ReadOnlyLockdownQueries? = null
        private var pipe: Pipe? = null
        private var queryConfirmed = false
        private var pairAttempted = false
        private var tcp: Iap2UsbMuxTcpConnection? = null
        private var serviceTcp: Iap2UsbMuxTcpConnection? = null
        private var serviceTls: TlsDuplexChannel? = null
        private var authorizedServicePort: Int? = null
        private var serviceConnectAttempted = false
        private var lockdownTlsClose = "NOT_OPEN"
        private var lockdownTcpClose = "NOT_OPEN"
        private var sessionRequests = 0
        private var sessionStopRequests = 0
        private var sessionIdentityRequests = 0
        private var plaintextSessionId: String? = null
        private var tlsSessionId: String? = null
        private var tlsAttempted = false
        private var tlsChannel: LockdownPlistChannel? = null
        private var tlsQueryRequests = 0
        private var tlsStartServiceRequests = 0
        private var tlsStopRequests = 0
        override fun configuration(report: (String) -> Unit): Int {
            check(!closed && !readAttempted) { "One pre-claim configuration read only" }
            readAttempted = true
            return readOnlyUsbConfiguration(connection, report).also { active5 = it == 5 }
        }
        override fun claimUsbMux(): Boolean {
            check(!closed && active5 && !claimAttempted) { "Active5 and one claim required" }
            claimAttempted = true
            unchanged()
            return connection.claimInterface(target, false).also { claimed = it }
        }
        override fun initialize(report: (String) -> Unit) {
            check(!closed && claimed && !releaseAttempted && !initAttempted) { "Initialize once after claim" }
            initAttempted = true
            unchanged()
            val opened = Pipe(connection, target, unchanged, report)
            pipe = opened
            mux = Iap2UsbMuxHost.openReadOnlyDiagnostic(
                opened,
                onDiagnostic = muxDiagnostics::add,
                diagnosticStage = ::lockdownStopSessionStage,
            )
        }
        override fun connectLockdown() {
            check(!closed && !releaseAttempted && !connectAttempted) { "One TCP connect only" }
            val host = checkNotNull(mux) { "Initialization required" }
            connectAttempted = true
            unchanged()
            val stream = host.connect(Iap2UsbMuxHost.LOCKDOWN_PORT, 5_000)
            tcp = stream
            try {
                val opened = LockdownPlistChannel(stream, maximumMessageBytes = 64 * 1024, defaultTimeoutMillis = 5_000)
                channel = opened
                queries = ReadOnlyLockdownQueries { request ->
                    check(!closed && !releaseAttempted) { "Discovery closed" }
                    unchanged()
                    opened.request(request, 5_000).also { host.verifyReadOnlyDiagnostic() }
                }
            } catch (error: Throwable) {
                try { stream.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
        override fun queryType() = checkNotNull(queries) { "Connect first" }.queryType().also {
            queryConfirmed = it == "com.apple.mobile.lockdown"
        }
        override fun productType() = checkNotNull(queries) { "QueryType first" }.productType()

        override fun sessionRequest(message: LockdownPlistValue.Dictionary, report: (String) -> Unit): LockdownPlistValue.Dictionary {
            check(!closed && !releaseAttempted && queryConfirmed && !pairAttempted && !tlsAttempted)
            val entries = message.entries
            val operation = (entries["Request"] as? LockdownPlistValue.Text)?.value
                ?: error("Request must be text")
            when (operation) {
                "GetValue" -> {
                    check(sessionIdentityRequests++ == 0 && sessionRequests == 0)
                    check(entries.keys == setOf("Label", "Request", "Key"))
                    check((entries["Key"] as? LockdownPlistValue.Text)?.value == "UniqueDeviceID")
                }
                "StartSession" -> {
                    check(sessionRequests++ == 0 && sessionIdentityRequests == 1)
                    check(entries.keys == setOf("Label", "Request", "HostID", "SystemBUID"))
                    check(entries["HostID"] is LockdownPlistValue.Text && entries["SystemBUID"] is LockdownPlistValue.Text)
                }
                "StopSession" -> {
                    check(sessionRequests == 1 && sessionStopRequests++ == 0)
                    val id = checkNotNull(plaintextSessionId)
                    check(entries.keys == setOf("Label", "Request", "SessionID"))
                    check((entries["SessionID"] as? LockdownPlistValue.Text)?.value == id)
                }
                else -> error("StartSession request boundary violation")
            }
            unchanged()
            val reply = checkNotNull(channel).request(message, 5_000) { frameBytes ->
                if (operation == "StartSession") report("StartSession request XML byte length=${frameBytes - 4}; framed byte length=$frameBytes")
            }
            checkNotNull(mux).verifyReadOnlyDiagnostic()
            if (operation == "StartSession" &&
                (reply.entries["Request"] as? LockdownPlistValue.Text)?.value == operation &&
                !reply.entries.containsKey("Error") &&
                (reply.entries["Result"] == null ||
                    (reply.entries["Result"] as? LockdownPlistValue.Text)?.value == "Success")) {
                val id = (reply.entries["SessionID"] as? LockdownPlistValue.Text)
                    ?.value?.takeIf { it.isNotBlank() && it.length <= 1024 }
                when ((reply.entries["EnableSessionSSL"] as? LockdownPlistValue.Boolean)?.value) {
                    false -> plaintextSessionId = id
                    true -> tlsSessionId = id
                    null -> Unit
                }
            }
            return reply
        }

        override fun startSessionTls(record: LockdownPairRecord, peerReport: (LockdownPeerValidation) -> Unit) {
            check(!closed && !releaseAttempted && !tlsAttempted && sessionRequests == 1 && sessionStopRequests == 0)
            checkNotNull(tlsSessionId) { "Valid SSL StartSession reply required before TLS" }
            tlsAttempted = true
            unchanged()
            // Same TCP62078 stream that carried StartSession: detach transfers ownership, never reconnects.
            val stream = checkNotNull(channel).detach()
            check(stream === tcp) { "TLS must use the StartSession TCP stream" }
            val tls = TlsDuplexChannel.open(stream, record, 5_000, peerReport)
            checkNotNull(mux).verifyReadOnlyDiagnostic()
            tlsChannel = LockdownPlistChannel(tls, maximumMessageBytes = 64 * 1024, defaultTimeoutMillis = 5_000)
            lockdownTlsClose = "OPEN"
        }

        override fun tlsSessionRequest(message: LockdownPlistValue.Dictionary): LockdownPlistValue.Dictionary {
            check(!closed && !releaseAttempted && tlsAttempted)
            val opened = checkNotNull(tlsChannel) { "TLS not established" }
            val entries = message.entries
            val operation = (entries["Request"] as? LockdownPlistValue.Text)?.value
                ?: error("TLS Request must be text")
            when (operation) {
                "QueryType" -> {
                    check(tlsQueryRequests++ == 0 && tlsStartServiceRequests == 0 && tlsStopRequests == 0)
                    check(entries.keys == setOf("Label", "Request"))
                }
                "StartService" -> {
                    check(tlsStartServiceRequests++ == 0 && tlsQueryRequests == 0 && tlsStopRequests == 0)
                    check(entries.keys == setOf("Label", "Request", "Service"))
                    check((entries["Service"] as? LockdownPlistValue.Text)?.value ==
                        com.shilapi.xcertplay.transport.ControlledLockdownTlsSession.CARKIT_SERVICE)
                }
                "StopSession" -> {
                    check(tlsStopRequests++ == 0)
                    check(tlsQueryRequests + tlsStartServiceRequests == 1)
                    check(entries.keys == setOf("Label", "Request", "SessionID"))
                    check((entries["SessionID"] as? LockdownPlistValue.Text)?.value == checkNotNull(tlsSessionId))
                }
                else -> error("TLS request boundary violation")
            }
            unchanged()
            val stoppingSession = operation == "StopSession"
            if (stoppingSession) {
                stopSessionResponseReceived = false
                stopSessionInProgress = true
            }
            val reply = try {
                opened.request(message, 5_000).also {
                    if (stoppingSession) stopSessionResponseReceived = true
                }
            } finally {
                if (stoppingSession) stopSessionInProgress = false
            }
            val host = checkNotNull(mux)
            host.verifyReadOnlyDiagnostic()
            if (operation == "StartService" && isValidCarKitServiceReply(reply)) {
                val port = (reply.entries["Port"] as LockdownPlistValue.Integer).value.toInt()
                host.authorizeDiagnosticServicePort(port)
                authorizedServicePort = port
            }
            return reply
        }

        override fun connectTcp(port: Int) {
            check(!closed && !releaseAttempted && tlsAttempted && tlsChannel != null && tlsStopRequests == 0)
            check(tlsStartServiceRequests == 1 && !serviceConnectAttempted && authorizedServicePort == port)
            serviceConnectAttempted = true
            unchanged()
            serviceTcp = checkNotNull(mux).connect(port, 5_000)
            checkNotNull(mux).verifyReadOnlyDiagnostic()
        }

        override fun startTls(record: LockdownPairRecord, peerReport: (LockdownPeerValidation) -> Unit) {
            check(!closed && !releaseAttempted && serviceConnectAttempted && serviceTls == null)
            check(authorizedServicePort != null && tlsStopRequests == 0)
            unchanged()
            serviceTls = TlsDuplexChannel.open(checkNotNull(serviceTcp), record, 5_000, peerReport)
            checkNotNull(mux).verifyReadOnlyDiagnostic()
        }

        override fun closeTls(): Boolean {
            val opened = serviceTls ?: return true
            serviceTls = null
            opened.close()
            return true
        }

        override fun closeTcp(): Boolean {
            val opened = serviceTcp ?: return true
            serviceTcp = null
            opened.close()
            return !opened.diagnosticState().contains("TCP_OPEN=true")
        }

        private fun isValidCarKitServiceReply(reply: LockdownPlistValue.Dictionary): Boolean {
            val entries = reply.entries
            if ((entries["Request"] as? LockdownPlistValue.Text)?.value != "StartService" ||
                entries.containsKey("Error") ||
                (entries["Result"] != null && (entries["Result"] as? LockdownPlistValue.Text)?.value != "Success") ||
                (entries["Service"] as? LockdownPlistValue.Text)?.value !=
                com.shilapi.xcertplay.transport.ControlledLockdownTlsSession.CARKIT_SERVICE ||
                (entries["EnableServiceSSL"] as? LockdownPlistValue.Boolean)?.value != true ||
                entries.keys.any { it !in setOf("Request", "Result", "Service", "Port", "EnableServiceSSL") }) return false
            val port = (entries["Port"] as? LockdownPlistValue.Integer)?.value ?: return false
            return port in 1..65535 && port != Iap2UsbMuxHost.LOCKDOWN_PORT.toLong()
        }

        private fun lockdownStopSessionStage(): String = when {
            stopSessionInProgress -> "DURING_STOPSESSION"
            stopSessionResponseReceived -> "AFTER_STOPSESSION_RESPONSE"
            else -> "BEFORE_STOPSESSION"
        }

        override fun sessionTransportState() = transportState()

        override fun prepareAndPair(store: DiagnosticPairStore, checkActive: () -> Unit, report: (String) -> Unit) {
            pairOperation(store, checkActive, report, false)
        }

        override fun validateExisting(store: DiagnosticPairStore, checkActive: () -> Unit, report: (String) -> Unit) {
            pairOperation(store, checkActive, report, true)
        }

        override fun transportState(): String {
            val muxEvidence = generateSequence { muxDiagnostics.poll() }.joinToString("\n")
            return "USB_HANDLE_OPEN=${!closed} (local ownership; not physical-handle liveness); " +
                (mux?.diagnosticState() ?: "USBMUX_HOST_ACTIVE=false (not initialized)") + "; " +
                "LOCKDOWN_TLS_CLOSE=$lockdownTlsClose; LOCKDOWN_TCP_CLOSE=$lockdownTcpClose; " +
                "LOCKDOWN_${tcp?.diagnosticState() ?: "TCP_OPEN=false (not connected)"}; " +
                "SERVICE_${serviceTcp?.diagnosticState() ?: "TCP_OPEN=false (not connected)"}; " +
                (pipe?.diagnosticState() ?: "USB_SHORT_WRITE=false; USB_FAILED_WRITE=false") +
                if (muxEvidence.isEmpty()) "" else "\n$muxEvidence"
        }

        private fun pairOperation(store: DiagnosticPairStore, checkActive: () -> Unit, report: (String) -> Unit,
            existingOnly: Boolean) {
            check(!closed && !releaseAttempted && queryConfirmed && !pairAttempted && sessionIdentityRequests == 0) { "One controlled attempt after QueryType" }
            pairAttempted = true
            val opened = checkNotNull(channel)
            val host = checkNotNull(mux)
            ControlledLockdownPairing(
                request = { request ->
                    check(!closed && !releaseAttempted)
                    checkActive()
                    unchanged()
                    opened.request(request, 5_000).also { host.verifyReadOnlyDiagnostic() }
                },
                store = store,
                active = { checkActive(); unchanged() },
                report = report,
                existingOnly = existingOnly,
            ).run()
        }

        private fun stopTransport() {
            if (transportStopped) return
            transportStopped = true
            plaintextSessionId = null
            tlsSessionId = null
            var failure: Exception? = null
            fun close(action: () -> Unit) {
                try { action() } catch (error: Exception) {
                    val prior = failure
                    if (prior == null) failure = error else prior.addSuppressed(error)
                }
            }
            close { mux?.verifyReadOnlyDiagnostic() }
            close { serviceTls?.close(); serviceTls = null }
            close { serviceTcp?.close(); serviceTcp = null }
            close {
                val opened = tlsChannel
                opened?.close()
                lockdownTlsClose = if (opened == null) "NOT_OPEN" else "PASS"
                tlsChannel = null
            }
            close { channel?.close(); channel = null }
            close {
                val opened = tcp
                opened?.close()
                lockdownTcpClose = if (opened == null) "NOT_OPEN"
                    else if (opened.diagnosticState().contains("TCP_OPEN=true")) "FAIL" else "PASS"
                tcp = null
            }
            close { mux?.close() }
            close { pipe?.close() }
            failure?.let { throw it }
        }
        override fun releaseUsbMux(): Boolean {
            check(!closed && claimed && !releaseAttempted) { "Release once only after claimtrue" }
            releaseAttempted = true
            var transportFailure: Exception? = null
            try { stopTransport() } catch (error: Exception) { transportFailure = error }
            val released = try { connection.releaseInterface(target) }
            catch (error: Exception) {
                transportFailure?.let { error.addSuppressed(it) }
                throw error
            }
            transportFailure?.let {
                if (!released) it.addSuppressed(IllegalStateException("releaseInterface returned false"))
                throw it
            }
            return released
        }
        override fun close() {
            if (closed) return
            closed = true
            var failure: Exception? = null
            try { stopTransport() } catch (error: Exception) { failure = error }
            try { connection.close() } catch (error: Exception) {
                val prior = failure
                if (prior == null) failure = error else prior.addSuppressed(error)
            }
            failure?.let { throw it }
        }
    }

    private class Pipe(
        private val connection: UsbDeviceConnection,
        target: UsbInterface,
        private val unchanged: () -> Unit,
        private val report: (String) -> Unit,
    ) : UsbMuxBulkPipe {
        private val output = (0 until target.endpointCount).map(target::getEndpoint).single { it.address == 4 }
        private val input = (0 until target.endpointCount).map(target::getEndpoint).single { it.address == 0x85 }
        private val closed = AtomicBoolean()
        private val readLock = Any()
        private val writeLock = Any()
        private var writes = 0
        private var reads = 0
        private var versionReply: ByteArray? = null
        @Volatile private var shortWrite = false
        @Volatile private var failedWrite = false

        fun diagnosticState() = "USB_SHORT_WRITE=$shortWrite; USB_FAILED_WRITE=$failedWrite; PIPE_OPEN=${!closed.get()}"

        override fun write(data: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
            check(!closed.get()) { "USB pipe closed" }
            unchanged()
            val operation = when (writes) {
                0 -> {
                    check(data.contentEquals(UsbMuxVersionPacket.request())) { "Audited version request required" }
                    "VERSION_OUT"
                }
                1 -> {
                    val reply = checkNotNull(versionReply) { "Valid version required before setup" }
                    val expected = byteArrayOf(0,0,0,2,0,0,0,17,0xfe.toByte(),0xed.toByte(),0xfa.toByte(),0xce.toByte(),
                        0,0,reply[12],reply[13],7)
                    check(data.contentEquals(expected)) { "Exactly audited setup17 required" }
                    "SETUP07_OUT"
                }
                else -> {
                    check(data.size >= 36 && data[3] == 6.toByte()) { "Only negotiated TCP frames after setup" }
                    "TCP_OUT"
                }
            }
            writes++
            report("$operation endpoint=0x04 length=${data.size} timeoutMs=$timeoutMillis" +
                if (writes <= 2) " hex=${hex(data)}" else "")
            val transferred = connection.bulkTransfer(output, data, data.size, timeoutMillis)
            report("$operation result=$transferred")
            if (transferred < 0) failedWrite = true
            else if (transferred != data.size) shortWrite = true
            if (transferred != data.size) throw IphoneUsbException.DeviceUnavailable("$operation short/failure=$transferred expected=${data.size}; no retry")
        }
        override fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
            check(!closed.get()) { "USB pipe closed" }
            unchanged()
            val buffer = ByteArray(if (reads == 0) 1024 else 16384)
            val count = connection.bulkTransfer(input, buffer, buffer.size, timeoutMillis.toInt())
            if (reads++ == 0) {
                report("VERSION_IN endpoint=0x85 capacity=1024 timeoutMs=$timeoutMillis result=$count")
                if (count != 20) throw IphoneUsbException.Protocol("VERSION_IN short/extra/timeout/failure=$count expected20; no continuation")
                val reply = buffer.copyOf(count)
                if (reply[0] == 0.toByte() && reply[1] == 0.toByte() && reply[2] == 0.toByte() && reply[3] == 0.toByte()) {
                    versionReply = reply
                }
                reply
            } else {
                if (count < 0) {
                    if (closed.get()) throw IphoneUsbException.DeviceUnavailable("Pipe closed during read")
                    null
                } else if (count == 0) throw IphoneUsbException.Protocol("Empty USBMUX completion")
                else buffer.copyOf(count)
            }
        }
        override fun close() {
            closed.set(true)
            // Wait for bounded in-flight transfers before releasing the interface.
            synchronized(readLock) { synchronized(writeLock) {} }
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(Locale.US, it.toInt() and 255) }
    }
}
