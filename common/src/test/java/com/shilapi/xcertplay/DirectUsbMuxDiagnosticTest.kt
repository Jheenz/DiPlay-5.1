package com.shilapi.xcertplay

import android.hardware.usb.*
import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DirectUsbMuxDiagnosticTest {
    @Test fun realTwelveInterfaceLayoutSelectsMultiplexorRatherThanEthernetAtAnyIndex() {
        for (index in listOf(1, 6, 10)) {
            val device = apple(index)
            val selection = requireNotNull(DirectUsbMuxSelection.find(device))
            assertEquals(index, selection.index)
            assertEquals(255, selection.usbInterface.interfaceClass)
            assertEquals(254, selection.usbInterface.interfaceSubclass)
            assertEquals(2, selection.usbInterface.interfaceProtocol)
            assertEquals(0x04, selection.out.address)
            assertEquals(0x85, selection.input.address)
            assertEquals(512, selection.input.maxPacketSize)
        }
    }

    @Test fun rejectsWrongSubclassProtocolClassMissingBulkPairAndAmbiguousMux() {
        for (intf in listOf(
            intf(255, 253, 1), intf(255, 254, 1), intf(254, 254, 2),
            intf(255, 254, 2, bulk = false),
        )) {
            assertNull(DirectUsbMuxSelection.find(device(listOf(intf))))
        }
        assertNull(DirectUsbMuxSelection.find(device(listOf(intf(), intf()))))
    }

    @Test fun absentPermissionStopsBeforeOpenAndDoesNotRequestPermission() {
        val manager = mock(UsbManager::class.java)
        val phone = apple()
        `when`(manager.deviceList).thenReturn(hashMapOf("phone" to phone))
        `when`(manager.hasPermission(phone)).thenReturn(false)
        val report = DirectUsbMuxDiagnostic(AndroidDirectUsbMuxAccess(manager)).run()
        assertTrue(report.contains("USB permission required — STOP"))
        assertTrue(report.contains("USB INTERFACE ACCESS FAILED"))
        verify(manager).deviceList
        verify(manager).hasPermission(phone)
        verifyNoMoreInteractions(manager)
    }

    @Test fun openFailureIsExplicitAndClaimIsNeverAttempted() {
        val manager = mock(UsbManager::class.java)
        val phone = apple()
        `when`(manager.deviceList).thenReturn(hashMapOf("phone" to phone))
        `when`(manager.hasPermission(phone)).thenReturn(true)
        `when`(manager.openDevice(phone)).thenReturn(null)
        val report = DirectUsbMuxDiagnostic(AndroidDirectUsbMuxAccess(manager)).run()
        assertTrue(report.contains("Failure stage=USB open/claim"))
        assertTrue(report.contains("openDevice returned null"))
        assertTrue(report.contains("USB INTERFACE ACCESS FAILED"))
        verify(manager).deviceList
        verify(manager).hasPermission(phone)
        verify(manager).openDevice(phone)
        verifyNoMoreInteractions(manager)
    }

    @Test fun claimFailureClosesDeviceWithoutTransfersOrReleaseOfUnclaimedInterface() {
        val manager = mock(UsbManager::class.java)
        val connection = mock(UsbDeviceConnection::class.java)
        val phone = apple()
        val selected = requireNotNull(DirectUsbMuxSelection.find(phone))
        `when`(manager.deviceList).thenReturn(hashMapOf("phone" to phone))
        `when`(manager.hasPermission(phone)).thenReturn(true)
        `when`(manager.openDevice(phone)).thenReturn(connection)
        `when`(connection.claimInterface(selected.usbInterface, false)).thenReturn(false)
        val report = DirectUsbMuxDiagnostic(AndroidDirectUsbMuxAccess(manager)).run()
        assertTrue(report.contains("open result=PASS"))
        assertTrue(report.contains("claim result=FAIL"))
        assertTrue(report.contains("cleanup device close=PASS"))
        verify(connection).claimInterface(selected.usbInterface, false)
        verify(connection).close()
        verifyNoMoreInteractions(connection)
    }

    @Test fun androidPipeReassemblesPartialBulkWritesAndReleasesOnlySelectedInterfaceOnce() {
        val manager = mock(UsbManager::class.java)
        val connection = mock(UsbDeviceConnection::class.java)
        val phone = apple()
        val selected = requireNotNull(DirectUsbMuxSelection.find(phone))
        `when`(manager.openDevice(phone)).thenReturn(connection)
        `when`(connection.claimInterface(selected.usbInterface, false)).thenReturn(true)
        `when`(connection.releaseInterface(selected.usbInterface)).thenReturn(true)
        val written = java.io.ByteArrayOutputStream()
        doAnswer { call ->
            val bytes = call.getArgument<ByteArray>(1)
            val count = minOf(3, call.getArgument<Int>(2))
            assertTrue(call.getArgument<Int>(3) in 1..100)
            written.write(bytes, 0, count)
            count
        }.`when`(connection).bulkTransfer(eq(selected.out), any(ByteArray::class.java), anyInt(), anyInt())
        val lines = mutableListOf<String>()
        val pipe = AndroidDirectUsbMuxAccess(manager).open(phone, selected, lines::add)
        val data = ByteArray(23) { it.toByte() }
        pipe.write(data, 100)
        pipe.close()
        pipe.close()
        assertArrayEquals(data, written.toByteArray())
        assertTrue(lines.contains("cleanup releaseInterface=PASS"))
        assertTrue(lines.contains("cleanup device close=PASS"))
        val order = inOrder(connection)
        order.verify(connection).claimInterface(selected.usbInterface, false)
        order.verify(connection, times(8)).bulkTransfer(eq(selected.out), any(ByteArray::class.java), anyInt(), anyInt())
        order.verify(connection).releaseInterface(selected.usbInterface)
        order.verify(connection).close()
        verifyNoMoreInteractions(connection)
        verify(manager).openDevice(phone)
        verifyNoMoreInteractions(manager)
    }

    @Test fun releaseFailureIsReportedAndDeviceIsStillClosed() {
        val manager = mock(UsbManager::class.java)
        val connection = mock(UsbDeviceConnection::class.java)
        val phone = apple()
        val selected = requireNotNull(DirectUsbMuxSelection.find(phone))
        `when`(manager.openDevice(phone)).thenReturn(connection)
        `when`(connection.claimInterface(selected.usbInterface, false)).thenReturn(true)
        val lines = mutableListOf<String>()
        AndroidDirectUsbMuxAccess(manager).open(phone, selected, lines::add).close()
        assertTrue(lines.contains("cleanup releaseInterface=FAIL"))
        verify(connection).close()
    }

    @Test fun cleanupExceptionStillClosesTheDeviceAndIsVisibleInDiagnosticReport() {
        val manager = mock(UsbManager::class.java)
        val connection = mock(UsbDeviceConnection::class.java)
        val phone = apple()
        val selected = requireNotNull(DirectUsbMuxSelection.find(phone))
        `when`(manager.deviceList).thenReturn(hashMapOf("phone" to phone))
        `when`(manager.hasPermission(phone)).thenReturn(true)
        `when`(manager.openDevice(phone)).thenReturn(connection)
        `when`(connection.claimInterface(selected.usbInterface, false)).thenReturn(true)
        `when`(connection.releaseInterface(selected.usbInterface)).thenThrow(IllegalStateException("release failed"))
        `when`(connection.bulkTransfer(eq(selected.out), any(ByteArray::class.java), anyInt(), anyInt())).thenReturn(-1)
        val report = DirectUsbMuxDiagnostic(AndroidDirectUsbMuxAccess(manager), 40).run()
        assertTrue(report.contains("USB TRANSPORT FAILED"))
        assertTrue(report.contains("cleanup overall=FAIL"))
        assertTrue(report.contains("release failed"))
        verify(connection).close()
    }

    @Test fun noOrMultipleAppleDevicesStopWithoutOpeningAnything() {
        for (devices in listOf(emptyList(), listOf(apple(), apple()))) {
            val access = object : DirectUsbMuxAccess {
                override fun devices() = devices
                override fun hasPermission(device: UsbDevice): Boolean = error("Must stop at selection")
                override fun open(device: UsbDevice, selection: DirectUsbMuxSelection, report: (String) -> Unit): UsbMuxBulkPipe =
                    error("Must not open")
            }
            val report = DirectUsbMuxDiagnostic(access).run()
            assertTrue(report.contains("INSUFFICIENT EVIDENCE"))
            assertTrue(report.contains("Failure stage=Apple device selection"))
        }
    }

    @Test fun trustObservationAfterCompletionIsRecordedAndInvalidatesUnqualifiedConfirmation() {
        val diagnostic = DirectUsbMuxDiagnostic(FakeAccess(ReplayPipe()), 1_000)
        assertTrue(diagnostic.run().contains("DIRECT USBMUX + LOCKDOWN CONFIRMED"))
        diagnostic.cancel("Trust prompt observed by user — STOP")
        assertTrue(diagnostic.report().contains("Trust prompt observed"))
        assertTrue(diagnostic.report().contains("Verdict: INSUFFICIENT EVIDENCE"))
    }

    @Test fun realMuxAndPlistCodeAcceptFragmentedHandshakeTcpAndNonMutatingGetValue() {
        val replay = ReplayPipe()
        val access = FakeAccess(replay)
        val report = DirectUsbMuxDiagnostic(access, 1_000).run()
        assertTrue(report, report.contains("DIRECT USBMUX + LOCKDOWN CONFIRMED"))
        assertTrue(report.contains("USBMUX handshake=PASS"))
        assertTrue(report.contains("Lockdown connection=PASS (port 62078)"))
        assertTrue(report.contains("ValueType=Text ProductType=iPhone14,5"))
        assertTrue(report.contains(DirectUsbMuxDiagnostic.SAFETY))
        assertEquals(listOf(62078), replay.connectedPorts)
        assertEquals(1, replay.queries.size)
        assertTrue(replay.queries.single().contains("<string>GetValue</string>"))
        assertTrue(replay.queries.single().contains("<string>ProductType</string>"))
        assertFalse(report.contains("private-identifier"))
        assertEquals(1, access.opens)
        assertEquals(1, replay.closes.get())
    }

    @Test fun missingOrInvalidVersionResponseFailsTransportAndCleansUp() {
        for (mode in listOf("timeout", "badVersion", "disconnect")) {
            val pipe = ReplayPipe(mode)
            val report = DirectUsbMuxDiagnostic(FakeAccess(pipe), 40).run()
            assertTrue(report, report.contains("USB TRANSPORT FAILED"))
            assertTrue(report.contains("Failure stage=USBMUX v2 handshake"))
            assertEquals(1, pipe.closes.get())
            assertTrue(pipe.queries.isEmpty())
        }
    }

    @Test fun lockdownTimeoutDisconnectOrRemoteTrustErrorNeverPairsOrRetries() {
        for (mode in listOf("queryTimeout", "queryDisconnect", "trustError", "badQuery")) {
            val pipe = ReplayPipe(mode)
            val report = DirectUsbMuxDiagnostic(FakeAccess(pipe), 80).run()
            assertTrue(report, report.contains("DIRECT USBMUX CONFIRMED — LOCKDOWN NOT CONFIRMED"))
            assertTrue(report.contains("Failure stage=Lockdown GetValue(ProductType)"))
            assertEquals(1, pipe.queries.size)
            assertEquals(1, pipe.closes.get())
            assertFalse(report.contains("private-identifier"))
        }
    }

    @Test fun tcpConnectTimeoutReportsOnlyMuxConfirmationAndCleansUp() {
        val pipe = ReplayPipe("connectTimeout")
        val report = DirectUsbMuxDiagnostic(FakeAccess(pipe), 40).run()
        assertTrue(report, report.contains("DIRECT USBMUX CONFIRMED — LOCKDOWN NOT CONFIRMED"))
        assertTrue(report.contains("Failure stage=Lockdown port 62078 connect"))
        assertTrue(pipe.queries.isEmpty())
        assertEquals(1, pipe.closes.get())
    }

    @Test fun cancellationAndOverallDeadlineCloseAnActivePipeAndStopBeforeQuery() {
        for (trust in listOf(false, true)) {
            val pipe = ReplayPipe("timeout")
            val diagnostic = DirectUsbMuxDiagnostic(FakeAccess(pipe), 5_000, if (trust) 5_000 else 200)
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<String> { diagnostic.run() }
                assertTrue(pipe.readStarted.await(2, TimeUnit.SECONDS))
                if (trust) diagnostic.cancel("Trust prompt observed by user — STOP")
                val report = result.get(2, TimeUnit.SECONDS)
                assertTrue(report.contains(if (trust) "Trust prompt observed" else "Overall transport timeout"))
                assertFalse(report.contains("DIRECT USBMUX + LOCKDOWN CONFIRMED"))
                assertTrue(pipe.queries.isEmpty())
                assertEquals(1, pipe.closes.get())
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test fun scopedClassesCannotReachPairingSessionsVendorControlOrProjection() {
        val forbidden = listOf(
            "requestPermission", "controlTransfer", "setConfiguration", "setInterface",
            "LockdownPairingClient", "LockdownPairRecord", "LockdownCarKitClient", "StartSession",
            "StartService", "ValidatePair", "SetValue", "com.apple.carkit.service", "Iap2Session",
            "MfiAuthenticator", "NcmUsbBridge", "AirPlay", "CarPlayController", "loadLibrary",
            "sendBroadcast", "android/bluetooth/", "QDrive", "AutoKit",
        )
        for (clazz in listOf(
            DirectUsbMuxDiagnostic::class.java, AndroidDirectUsbMuxAccess::class.java,
            DirectUsbMuxSelection::class.java, DirectUsbMuxSelection.Companion::class.java,
        )) {
            val bytes = requireNotNull(clazz.getResourceAsStream("/${clazz.name.replace('.', '/')}.class"))
                .use { it.readBytes().toString(Charsets.ISO_8859_1) }
            forbidden.forEach { symbol ->
                assertFalse("${clazz.simpleName} references $symbol", bytes.contains(symbol))
            }
        }
    }

    private class FakeAccess(val pipe: ReplayPipe) : DirectUsbMuxAccess {
        var opens = 0
        private val phone = apple()
        override fun devices() = listOf(phone)
        override fun hasPermission(device: UsbDevice) = true
        override fun open(device: UsbDevice, selection: DirectUsbMuxSelection, report: (String) -> Unit): UsbMuxBulkPipe {
            opens++
            report("open result=PASS")
            report("claim result=PASS")
            return pipe
        }
    }

    /** Responds to the real host's writes, then fragments captured-shaped response frames. */
    private class ReplayPipe(private val mode: String = "valid") : UsbMuxBulkPipe {
        val queries = java.util.concurrent.CopyOnWriteArrayList<String>()
        val connectedPorts = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val closes = AtomicInteger()
        val readStarted = CountDownLatch(1)
        private val closed = AtomicBoolean()
        private val chunks = LinkedBlockingQueue<ByteArray>()
        private var tcpSequence = 1

        override fun write(data: ByteArray, timeoutMillis: Int) {
            if (closed.get()) throw IphoneUsbException.DeviceUnavailable("fake disconnected")
            val protocol = ByteBuffer.wrap(data).int
            if (protocol == 0) {
                if (mode !in listOf("timeout", "disconnect")) {
                    val version = ByteBuffer.allocate(20).putInt(0).putInt(20)
                        .putInt(if (mode == "badVersion") 3 else 2).putInt(0).putInt(0).array()
                    enqueue(version)
                }
            } else if (protocol == 6) {
                val tcp = data.copyOfRange(16, data.size)
                val flags = tcp[13].toInt() and 0xff
                if (flags and 2 != 0) {
                    connectedPorts += ByteBuffer.wrap(tcp, 2, 2).short.toInt() and 0xffff
                    if (mode != "connectTimeout") reply(tcp, 0x12, ByteArray(0))
                } else if (tcp.size > 20) {
                    val payload = tcp.copyOfRange(20, tcp.size)
                    val xml = payload.copyOfRange(4, payload.size).toString(Charsets.UTF_8)
                    assertEquals(payload.size - 4, ByteBuffer.wrap(payload).int)
                    assertTrue(xml.contains("<string>GetValue</string>"))
                    assertTrue(xml.contains("<string>ProductType</string>"))
                    queries += xml
                    when (mode) {
                        "queryTimeout" -> Unit
                        "queryDisconnect" -> reply(tcp, 0x11, ByteArray(0))
                        else -> {
                            val dict = when (mode) {
                                "trustError" -> "<key>Error</key><string>PairingDialogResponsePending</string>"
                                "badQuery" -> "<key>Request</key><string>StartSession</string>"
                                else -> "<key>Request</key><string>GetValue</string><key>Value</key><string>iPhone14,5</string><key>UniqueDeviceID</key><string>private-identifier</string>"
                            }
                            val bytes = "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict>$dict</dict></plist>".toByteArray()
                            reply(tcp, 0x10, ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array())
                        }
                    }
                }
            }
        }

        private fun reply(request: ByteArray, flags: Int, payload: ByteArray) {
            val tcp = ByteBuffer.allocate(20 + payload.size)
                .put(request, 2, 2).put(request, 0, 2)
                .putInt(tcpSequence).putInt(1).put(0x50.toByte()).put(flags.toByte())
                .putShort(512).putInt(0).put(payload).array()
            tcpSequence += payload.size
            enqueue(ByteBuffer.allocate(16 + tcp.size).putInt(6).putInt(16 + tcp.size)
                .putInt(0).putInt(0).put(tcp).array())
        }

        private fun enqueue(frame: ByteArray) {
            frame.asList().chunked(7).forEach { chunks.add(it.toByteArray()) }
        }

        override fun read(timeoutMillis: Long): ByteArray? {
            readStarted.countDown()
            if (closed.get() || mode == "disconnect") throw IphoneUsbException.DeviceUnavailable("fake disconnected")
            return chunks.poll(timeoutMillis, TimeUnit.MILLISECONDS)?.also {
                if (closed.get()) throw IphoneUsbException.DeviceUnavailable("fake closed")
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                closes.incrementAndGet()
                chunks.offer(ByteArray(0))
            }
        }
    }

    companion object {
        private fun endpoint(address: Int, bulk: Boolean): UsbEndpoint = mock(UsbEndpoint::class.java).also {
            `when`(it.address).thenReturn(address)
            `when`(it.endpointNumber).thenReturn(address and 15)
            `when`(it.direction).thenReturn(address and 0x80)
            `when`(it.type).thenReturn(if (bulk) 2 else 3)
            `when`(it.maxPacketSize).thenReturn(512)
        }

        private fun intf(cls: Int = 255, sub: Int = 254, protocol: Int = 2, bulk: Boolean = true): UsbInterface =
            mock(UsbInterface::class.java).also {
                `when`(it.interfaceClass).thenReturn(cls)
                `when`(it.interfaceSubclass).thenReturn(sub)
                `when`(it.interfaceProtocol).thenReturn(protocol)
                `when`(it.endpointCount).thenReturn(2)
                val out = endpoint(0x04, bulk)
                val input = endpoint(0x85, bulk)
                `when`(it.getEndpoint(0)).thenReturn(out)
                `when`(it.getEndpoint(1)).thenReturn(input)
            }

        private fun device(interfaces: List<UsbInterface>): UsbDevice = mock(UsbDevice::class.java).also {
            `when`(it.vendorId).thenReturn(0x05ac)
            `when`(it.productId).thenReturn(0x12a8)
            `when`(it.interfaceCount).thenReturn(interfaces.size)
            interfaces.forEachIndexed { index, intf -> `when`(it.getInterface(index)).thenReturn(intf) }
        }

        private fun apple(index: Int = 6): UsbDevice = device((0 until 12).map {
            if (it == index) intf() else if (it > 7) intf(255, 253, 1) else intf(3, 0, 0)
        })
    }
}
