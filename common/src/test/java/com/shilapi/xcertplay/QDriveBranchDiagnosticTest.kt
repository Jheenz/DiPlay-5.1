package com.shilapi.xcertplay

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class QDriveBranchDiagnosticTest {
    @Test fun exactAndSubstringMatchesTakeConfigurationBranchCaseSensitiveNonmatchesTakeVendorBranch() {
        for (text in listOf("Valeria", "valeria", "Valeria extra", "prefix Valeria suffix", "Apple Mobile Device", "")) {
            val fixture = Fixture(text)
            val report = fixture.scan()
            assertTrue(report, report.contains("Valeria condition = ${if (text.contains("Valeria")) "MATCH" else "NO MATCH"}"))
            assertTrue(report.contains(if (text.contains("Valeria")) "strstr != NULL -> helper TRUE" else "vendor-request branch"))
            fixture.verifySafety()
        }
    }

    @Test fun cachedNullNameUsesIndexedFirstLanguageStringAndCloses() {
        val fixture = Fixture("Apple")
        assertTrue(fixture.scan().contains("name=unavailable"))
        assertTrue(fixture.lines.any { it.contains("iInterface=7") })
        assertTrue(fixture.lines.contains("First LANGID=1033 (GET_DESCRIPTOR string index 0)"))
        fixture.verifySafety()
    }

    @Test fun missingStringIndexContinuesWithEmptyClearedOutputAndNoStringRequest() {
        val fixture = Fixture("Valeria", stringIndex = 0)
        val report = fixture.scan()
        assertTrue(report.contains("Valeria condition = NO MATCH"))
        assertEquals(4, fixture.lines.count { it.contains("result=NO_STRING_INDEX") })
        assertEquals(4, fixture.lines.count { it.contains("QDrive decision=CONTINUE") })
        assertEquals(listOf(8), fixture.requests.map { it.first })
        fixture.verifySafety()
    }

    @Test fun malformedRawOrStringDescriptorsStopAndClose() {
        for (raw in listOf(byteArrayOf(), raw().copyOf(20), raw().also { it[18] = 0 }, raw().also { it[22] = 2 })) {
            val fixture = Fixture("Apple")
            `when`(fixture.connection.rawDescriptors).thenReturn(raw)
            assertTrue(fixture.scan().contains("UNAVAILABLE"))
            fixture.verifySafety()
        }
        val fixture = Fixture("Apple")
        fixture.malformedString = true
        assertTrue(fixture.scan().contains("result=MALFORMED"))
        assertTrue(fixture.lines.any { it.contains("type/length rejected by libusb before output conversion") })
        fixture.verifySafety()
    }

    @Test fun nativeReadFailureAndRejectedLanguageHeaderContinueButAndroidExceptionRemainsUncertain() {
        for (count in listOf(-1, 0)) {
            val fixture = Fixture("Apple")
            fixture.stringReadCount = count
            val report = fixture.scan()
            assertTrue(report, report.contains("Valeria condition = NO MATCH"))
            assertEquals(4, fixture.lines.count { it.contains("QDrive decision=CONTINUE") })
            fixture.verifySafety()
        }
        val fixture = Fixture("Apple")
        fixture.throwOnRead = true
        assertTrue(fixture.scan().contains("read failed"))
        fixture.verifySafety()
        val currentFailure = Fixture("Apple")
        currentFailure.activeCount = 0
        assertTrue(currentFailure.scan().contains("GET_CONFIGURATION failed"))
        currentFailure.verifySafety()
    }

    @Test fun permissionAbsentAndOpenFailureNeverTransfer() {
        val absent = Fixture("Apple", permission = false)
        assertTrue(absent.scan().contains("USB permission required"))
        verifyNoInteractions(absent.connection)
        verifyNoInteractions(absent.manager)
        val absentAtOpen = Fixture("Apple")
        `when`(absentAtOpen.manager.hasPermission(absentAtOpen.device)).thenReturn(false)
        assertTrue(absentAtOpen.scan().contains("USB permission required"))
        verifyNoInteractions(absentAtOpen.connection)
        val failed = Fixture("Apple")
        `when`(failed.manager.openDevice(failed.device)).thenReturn(null)
        assertTrue(failed.scan().contains("openDevice returned null"))
        verifyNoInteractions(failed.connection)
        val throwing = Fixture("Apple")
        `when`(throwing.manager.openDevice(throwing.device)).thenThrow(SecurityException("open denied"))
        assertTrue(throwing.scan().contains("open denied"))
        verifyNoInteractions(throwing.connection)
    }

    @Test fun cleanupFailurePreventsConfirmedVerdict() {
        val fixture = Fixture("Apple")
        doThrow(IllegalStateException("close failed")).`when`(fixture.connection).close()
        val report = fixture.scan()
        assertTrue(report.contains("cleanup connection close=FAIL"))
        assertTrue(report.contains("Valeria condition = UNAVAILABLE"))
    }

    @Test fun firstAlternatePerInterfaceAcrossConfigurationsNotJustUsbmux() {
        val entries = QDriveDescriptors.interfaces(raw())
        assertEquals(listOf(1, 2, 3, 4), entries.map { it.configurationId })
        assertEquals(listOf(0, 1, 2, 3), entries.map { it.configurationIndex })
        assertTrue(entries.all { it.interfaceId == 1 && it.alternate == 0 && it.stringIndex == 7 })
        val bytes = raw().toMutableList()
        // Add a second alternate in the last configuration; bNumInterfaces stays one.
        bytes.addAll(listOf(9, 4, 1, 1, 0, 255, 253, 1, 8).map { it.toByte() })
        bytes[18 + 3 * 18 + 2] = 27
        assertEquals(4, QDriveDescriptors.interfaces(bytes.toByteArray()).size)
    }

    @Test fun laterMatchControlsBranchWhenEarlierStringsDoNotContainValeria() {
        val fixture = Fixture("PTP")
        val descriptors = raw()
        descriptors[18 + 18 + 9 + 8] = 8
        `when`(fixture.connection.rawDescriptors).thenReturn(descriptors)
        fixture.additionalString = "Apple Valeria interface"
        val report = fixture.scan()
        assertTrue(report.contains("configuration index=1 ID=2"))
        assertTrue(report.contains("strstr != NULL -> helper TRUE"))
        assertEquals(listOf(8, 6, 6, 6, 6), fixture.requests.map { it.first })
        fixture.verifySafety()
    }

    @Test fun asciiConversionMatchesLibusbAndCNullTermination() {
        assertEquals("V?x", QDriveDescriptors.ascii(string("V\u00e9x")))
        assertEquals("Valeria", QDriveDescriptors.ascii(string("Valeria\u0000ignored")))
    }

    @Test fun realE01MissingConfigTwoStringDoesNotPreventConfigThreeAndFourInspection() {
        val fixture = realFixture()
        val report = fixture.scan()
        assertTrue(report, report.contains("VALERIA CONDITION DOES NOT MATCH — VENDOR-REQUEST BRANCH SUPPORTED"))
        assertEquals(listOf(1, 2, 3, 3, 4, 4, 4), fixture.lines.filter { it.startsWith("Checked configuration") }
            .map { Regex(" ID=(\\d+)").find(it)!!.groupValues[1].toInt() })
        assertTrue(fixture.lines.any { it.contains("result=NO_STRING_INDEX") })
        assertTrue(fixture.lines.any { it.contains("ASCII string=\"PTP\"") })
        assertTrue(fixture.lines.any { it.contains("ASCII string=\"Apple Mobile Device\"") })
        assertTrue(fixture.lines.any { it.contains("ASCII string=\"Apple USB Ethernet\"") })
        assertEquals(listOf(8 to 0, 6 to 0x300, 6 to 0x307, 6 to 0x300, 6 to 0x307,
            6 to 0x300, 6 to 0x308, 6 to 0x300, 6 to 0x307, 6 to 0x300, 6 to 0x308,
            6 to 0x300, 6 to 0x309), fixture.requests)
        fixture.verifySafety()
    }

    @Test fun laterValeriaAfterMissingStringReturnsImmediatelyWithoutInspectingFollowingEntry() {
        val fixture = realFixture()
        fixture.texts[8] = "Apple Valeria USB"
        val report = fixture.scan()
        assertTrue(report.contains("VALERIA CONDITION MATCHES — CONFIGURATION-SELECTION BRANCH SUPPORTED"))
        assertTrue(report.contains("QDrive decision=TERMINATE TRUE"))
        assertFalse(fixture.lines.any { it.contains("Checked configuration index=3") })
        assertFalse(fixture.requests.any { it.second == 0x309 })
        fixture.verifySafety()
    }

    @Test fun failedOrRejectedIndividualStringDoesNotBlockLaterMatchOrCompleteFalseResult() {
        for (match in listOf(false, true)) {
            for (failure in listOf("negative", "wrongType", "truncated")) {
                val fixture = realFixture()
                fixture.responses[8] = when (failure) {
                    "wrongType" -> byteArrayOf(4, 4, 65, 0)
                    "truncated" -> byteArrayOf(12, 3, 65, 0)
                    else -> byteArrayOf()
                }
                if (failure == "negative") fixture.counts[8] = -1
                if (match) fixture.texts[9] = "Valeria Ethernet"
                val report = fixture.scan()
                assertTrue(report, report.contains("Valeria condition = ${if (match) "MATCH" else "NO MATCH"}"))
                assertTrue(fixture.lines.any { it.contains("Checked configuration index=3") })
                assertTrue(fixture.lines.any { it.contains("result=${if (failure == "negative") "STRING_READ_FAILED" else "MALFORMED"}") })
                fixture.verifySafety()
            }
        }
    }

    @Test fun emptyStringContinuesAndNativeAcceptedMalformedHeaderDoesNotInventFalseEvidence() {
        val empty = realFixture()
        empty.texts[8] = ""
        assertTrue(empty.scan().contains("Valeria condition = NO MATCH"))
        assertTrue(empty.lines.any { it.contains("ASCII string=\"\"") })
        empty.verifySafety()
        for (response in listOf(byteArrayOf(), byteArrayOf(3, 3, 65))) {
            val fixture = realFixture()
            fixture.responses[8] = response
            val report = fixture.scan()
            assertTrue(report, report.contains("Valeria condition = UNAVAILABLE"))
            assertTrue(fixture.lines.any { it.contains("Checked configuration index=3") })
            fixture.verifySafety()
        }
        val invalidEmpty = realFixture()
        invalidEmpty.responses[8] = byteArrayOf(0, 3)
        assertTrue(invalidEmpty.scan().contains("Valeria condition = NO MATCH"))
        assertTrue(invalidEmpty.lines.any { it.contains("native conversion writes empty output") })
        invalidEmpty.verifySafety()
    }

    @Test fun descriptorZeroHeaderIsNotAProxyForQDriveFirstLanguageBytes() {
        val fixture = realFixture()
        fixture.responses[0] = byteArrayOf(0, 4, 9, 4)
        fixture.texts[9] = "Valeria"
        assertTrue(fixture.scan().contains("Valeria condition = MATCH"))
        assertTrue(fixture.requests.any { it.second == 0x309 })
        fixture.verifySafety()
    }

    @Test fun unresolvedEarlierStringCannotPreventLaterSuccessfulPredicateMatch() {
        val fixture = realFixture()
        fixture.responses[8] = byteArrayOf()
        fixture.texts[9] = "Valeria"
        val report = fixture.scan()
        assertTrue(report.contains("native header bytes may be uninitialized"))
        assertTrue(report.contains("Valeria condition = MATCH"))
        fixture.verifySafety()
    }

    private fun realFixture() = Fixture("PTP").also { fixture ->
        val descriptors = raw().take(18).toMutableList()
        val layouts = listOf(
            listOf(listOf(0, 0, 6, 1, 1, 7)),
            listOf(listOf(0, 0, 255, 255, 255, 0)),
            listOf(listOf(0, 0, 6, 1, 1, 7), listOf(1, 0, 255, 254, 2, 8)),
            listOf(listOf(0, 0, 6, 1, 1, 7), listOf(1, 0, 255, 254, 2, 8),
                listOf(2, 0, 255, 253, 1, 9), listOf(2, 1, 255, 253, 1, 10),
                listOf(2, 2, 255, 253, 1, 11)),
        )
        layouts.forEachIndexed { index, interfaces ->
            descriptors += listOf(9, 2, 9 + 9 * interfaces.size, 0,
                interfaces.map { it[0] }.distinct().size, index + 1, 0, 0x80, 250).map { it.toByte() }
            interfaces.forEach { intf ->
                descriptors += listOf(9, 4, intf[0], intf[1], 0, intf[2], intf[3], intf[4], intf[5]).map { it.toByte() }
            }
        }
        `when`(fixture.connection.rawDescriptors).thenReturn(descriptors.toByteArray())
        fixture.texts[8] = "Apple Mobile Device"
        fixture.texts[9] = "Apple USB Ethernet"
    }

    @Test fun realConfigurationLayoutChecksPtpAndIpodBeforeDuplicateMuxAndSkipsEthernetAlternates() {
        val device = raw().take(18).toMutableList()
        val layouts = listOf(
            listOf(listOf(0, 0, 6, 1, 1, 4)),
            listOf(listOf(0, 0, 255, 255, 255, 5)),
            listOf(listOf(0, 0, 6, 1, 1, 4), listOf(1, 0, 255, 254, 2, 7)),
            listOf(listOf(0, 0, 6, 1, 1, 4), listOf(1, 0, 255, 254, 2, 7),
                listOf(2, 0, 255, 253, 1, 8), listOf(2, 1, 255, 253, 1, 9),
                listOf(2, 2, 255, 253, 1, 10)),
        )
        layouts.forEachIndexed { index, interfaces ->
            val length = 9 + 9 * interfaces.size
            device += listOf(9, 2, length, 0, interfaces.map { it[0] }.distinct().size, index + 1, 0, 0x80, 250).map { it.toByte() }
            interfaces.forEach { intf ->
                device += listOf(9, 4, intf[0], intf[1], 0, intf[2], intf[3], intf[4], intf[5]).map { it.toByte() }
            }
        }
        val entries = QDriveDescriptors.interfaces(device.toByteArray())
        assertEquals(listOf(1, 2, 3, 3, 4, 4, 4), entries.map { it.configurationId })
        assertEquals(listOf(4, 5, 4, 7, 4, 7, 8), entries.map { it.stringIndex })
        assertEquals(2, entries.count { it.interfaceClass == 255 && it.subclass == 254 && it.protocol == 2 })
        assertTrue(entries.all { it.alternate == 0 })
    }

    @Test fun compiledBoundaryCannotReferenceMutationOrTransport() {
        val forbidden = listOf("setConfiguration", "setInterface", "claimInterface", "requestPermission",
            "bulkTransfer", "UsbRequest", "com/shilapi/xcertplay/transport/", "com/shilapi/xcertplay/mfi/",
            "android/bluetooth/", "loadLibrary", "sendBroadcast", "StartSession", "StartService")
        for (clazz in listOf(QDriveDescriptorAccess::class.java, QDriveBranchDiagnostic::class.java,
            QDriveDescriptors::class.java, AndroidQDriveDescriptorAccess::class.java,
            Class.forName("com.shilapi.xcertplay.AndroidActiveUsbConfigurationAccessKt"))) {
            val bytes = requireNotNull(clazz.getResourceAsStream("/${clazz.name.replace('.', '/')}.class"))
                .use { it.readBytes().toString(Charsets.ISO_8859_1) }
            forbidden.forEach { assertFalse("${clazz.simpleName}: $it", bytes.contains(it)) }
        }
    }

    private class Fixture(val text: String, permission: Boolean = true, stringIndex: Int = 7) {
        val manager = mock(UsbManager::class.java)
        val device = mock(UsbDevice::class.java)
        val connection = mock(UsbDeviceConnection::class.java)
        val lines = mutableListOf<String>()
        val requests = mutableListOf<Pair<Int, Int>>()
        var stringReadCount: Int? = null
        var activeCount = 1
        var malformedString = false
        var throwOnRead = false
        var additionalString = text
        val texts = mutableMapOf<Int, String>()
        val responses = mutableMapOf<Int, ByteArray>()
        val counts = mutableMapOf<Int, Int>()
        val snapshot = PassiveUsbDevice("apple", 0x05ac, 0x12a8, 0, 0, 0, permission, emptyList(),
            (1..4).map { PassiveUsbConfiguration(it, "config $it", false, false, 500,
                listOf(PassiveUsbInterface(1, 255, 254, 2, emptyList(), 0))) })
        val adapter = AndroidQDriveDescriptorAccess(manager)
        init {
            `when`(device.vendorId).thenReturn(0x05ac)
            `when`(device.productId).thenReturn(0x12a8)
            `when`(manager.deviceList).thenReturn(hashMapOf("apple" to device))
            `when`(manager.hasPermission(device)).thenReturn(permission)
            `when`(manager.openDevice(device)).thenReturn(connection)
            `when`(connection.rawDescriptors).thenReturn(raw(stringIndex))
            doAnswer { call ->
                val request = call.getArgument<Int>(1)
                val value = call.getArgument<Int>(2)
                val index = call.getArgument<Int>(3)
                val buffer = call.getArgument<ByteArray>(4)
                assertEquals(0x80, call.getArgument<Int>(0))
                assertEquals(buffer.size, call.getArgument<Int>(5))
                assertEquals(1_000, call.getArgument<Int>(6))
                requests += request to value
                if (request == 8) {
                    assertEquals(0, value)
                    assertEquals(0, index)
                    assertEquals(1, buffer.size)
                    buffer[0] = 1
                    activeCount
                } else {
                    assertEquals(6, request)
                    assertEquals(255, buffer.size)
                    assertTrue(value in listOf(0x300, 0x307, 0x308, 0x309))
                    assertEquals(if (value == 0x300) 0 else 1033, index)
                    if (throwOnRead) throw IllegalStateException("read failed")
                    val stringIndex = value and 255
                    val response = responses[stringIndex] ?: if (value == 0x300) byteArrayOf(4, 3, 9, 4)
                    else string(texts[stringIndex] ?: if (value == 0x308) additionalString else text)
                    response.copyInto(buffer)
                    if (malformedString) buffer[1] = 4
                    counts[stringIndex] ?: stringReadCount ?: response.size
                }
            }.`when`(connection).controlTransfer(anyInt(), anyInt(), anyInt(), anyInt(), any(ByteArray::class.java), anyInt(), anyInt())
        }
        fun scan() = QDriveBranchDiagnostic(object : QDriveDescriptorAccess {
            override fun devices() = listOf(snapshot)
            override fun inspect(device: PassiveUsbDevice, report: (String) -> Unit) =
                adapter.inspect(device) { lines += it; report(it) }
        }).scan()
        fun verifySafety() {
            verify(manager).deviceList
            verify(manager).hasPermission(device)
            verify(manager).openDevice(device)
            verifyNoMoreInteractions(manager)
            verify(connection, times(requests.size)).controlTransfer(anyInt(), anyInt(), anyInt(), anyInt(), any(ByteArray::class.java), anyInt(), anyInt())
            if (activeCount == 1) verify(connection).rawDescriptors
            verify(connection).close()
            verifyNoMoreInteractions(connection)
        }
    }

    companion object {
        private fun raw(stringIndex: Int = 7): ByteArray {
            val device = mutableListOf(18, 1, 0, 2, 0, 0, 0, 64, 0xac, 5, 0xa8, 0x12, 0, 1, 0, 0, 0, 4)
            for (id in 1..4) {
                device += listOf(9, 2, 18, 0, 1, id, 0, 0x80, 250,
                    9, 4, 1, 0, 0, 255, 254, 2, stringIndex)
            }
            return device.map { it.toByte() }.toByteArray()
        }
        private fun string(text: String): ByteArray {
            val encoded = text.toByteArray(Charsets.UTF_16LE)
            return byteArrayOf((encoded.size + 2).toByte(), 3) + encoded
        }
    }
}
