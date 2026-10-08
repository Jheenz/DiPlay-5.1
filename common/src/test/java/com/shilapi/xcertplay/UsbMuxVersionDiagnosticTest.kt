package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.UsbMuxVersionPacket
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class UsbMuxVersionDiagnosticTest {
    private class Fixture : UsbMuxVersionAccess, UsbMuxVersionConnection {
        var inventory = listOf(claimTestDevice())
        var config = 5
        var claim = true
        var release = true
        var written = 20
        var received = 20
        var response = UsbMuxVersionPacket.request()
        var throwsAt: String? = null
        var after: ((String) -> Unit)? = null
        val calls = mutableListOf<String>()
        val diagnostic = UsbMuxVersionDiagnostic(this)
        private fun call(name: String) {
            calls += name
            check(throwsAt != name) { "$name test failure" }
            after?.invoke(name)
        }
        override fun devices() = inventory
        override fun open(device: PassiveUsbDevice): UsbMuxVersionConnection { call("open"); return this }
        override fun configuration(report: (String) -> Unit): Int { call("get"); return config }
        override fun claimUsbMux(): Boolean { call("claim"); return claim }
        override fun writeVersion(packet: ByteArray): Int {
            call("out")
            assertArrayEquals(byteArrayOf(0,0,0,0,0,0,0,20,0,0,0,2,0,0,0,0,0,0,0,0), packet)
            return written
        }
        override fun readVersion(buffer: ByteArray): Int {
            call("in")
            assertEquals(1024, buffer.size)
            response.copyInto(buffer)
            return received
        }
        override fun releaseUsbMux(): Boolean { call("release"); return release }
        override fun close() { call("close") }
        fun failure(reason: String) = diagnostic.run().also {
            assertTrue(it, it.contains(reason))
            assertFalse(it, it.contains(UsbMuxVersionDiagnostic.PASS))
        }
    }
    private fun mutate5(transform: (PassiveUsbConfiguration) -> PassiveUsbConfiguration): PassiveUsbDevice =
        claimTestDevice().let { device -> device.copy(configurations = device.configurations.map {
            if (it.id == 5) transform(it) else it
        }) }
    private fun mutateMux(transform: (PassiveUsbInterface) -> PassiveUsbInterface) = mutate5 { config ->
        config.copy(interfaces = config.interfaces.map { if (it.id == 1) transform(it) else it })
    }

    @Test fun exactRequestValidReplyAndOneShotNoRetries() {
        val f = Fixture()
        val report = f.diagnostic.run()
        assertTrue(report, report.contains("${UsbMuxVersionDiagnostic.PASS}\n${UsbMuxVersionDiagnostic.NOT_STARTED}"))
        assertTrue(report.contains("force=false"))
        assertTrue(report.contains("setup07=0; TCP connects=0; Lockdown requests=0"))
        assertEquals(listOf("open","get","claim","out","in","release","close"), f.calls)
        f.failure("ALREADY_RUN")
        assertEquals(1, f.calls.count { it == "out" })
        assertEquals(1, f.calls.count { it == "in" })
    }
    @Test fun minorAndReservedAreNotEchoRequirements() {
        val f = Fixture()
        ByteBuffer.wrap(f.response).putInt(12, 42).putInt(16, 123)
        assertTrue(f.diagnostic.run().contains(UsbMuxVersionDiagnostic.PASS))
    }
    @Test fun identityPermissionCountsAndConfiguration5Preconditions() {
        val base = claimTestDevice()
        val cases = listOf(
            emptyList(), listOf(base,base.copy(name="second")), listOf(base.copy(vendorId=1)),
            listOf(base.copy(productId=1)), listOf(base.copy(hasPermission=false)),
            listOf(base.copy(configurations=base.configurations.take(4))),
            listOf(base.copy(configurations=base.configurations.map { if(it.id==5) it.copy(id=6) else it })),
            listOf(base.copy(configurations=base.configurations.map { if(it.id==4) it.copy(id=5) else it })),
        )
        cases.forEach { devices ->
            val f=Fixture().apply { inventory=devices }
            f.failure(if(devices.singleOrNull()?.hasPermission==false) "PERMISSION_UNAVAILABLE" else "PRECONDITION_FAILURE")
            assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun valeriaAndBothNcmDescriptorsRequired() {
        for(id in listOf(2,3,4)) {
            val f=Fixture().apply { inventory=listOf(mutate5 { it.copy(interfaces=it.interfaces.filter { i -> i.id!=id }) }) }
            f.failure("PRECONDITION_FAILURE"); assertTrue(f.calls.isEmpty())
        }
        for(name in listOf(null,"valeria","PTP")) {
            val f=Fixture().apply { inventory=listOf(mutate5 { it.copy(name="Valeria", interfaces=it.interfaces.map { i ->
                if(i.id==2) i.copy(name=name) else i
            }) }) }
            f.failure("PRECONDITION_FAILURE"); assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun missingAmbiguousWrongIdentityAndDescriptorMuxPreventOpen() {
        val cases = listOf(
            mutate5 { it.copy(interfaces=it.interfaces.filter { i -> i.id!=1 }) },
            mutate5 { it.copy(interfaces=it.interfaces+it.interfaces.first()) },
            mutateMux { it.copy(id=13) }, mutateMux { it.copy(alternateSetting=1) },
            mutateMux { it.copy(interfaceClass=254) }, mutateMux { it.copy(subclass=255) },
            mutateMux { it.copy(protocol=1) },
        )
        cases.forEach { device ->
            val f=Fixture().apply { inventory=listOf(device) }
            f.failure("SCOPED_INTERFACE_MISSING_OR_AMBIGUOUS"); assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun wrongEndpointsAndPacketSizesPreventOpen() {
        val out=claimTestDevice().configurations.last().interfaces.first().endpoints.first()
        val bad=listOf(out.copy(address=6),out.copy(number=6),out.copy(direction=128),out.copy(type=3),out.copy(maxPacketSize=0))
        bad.forEach { ep ->
            val f=Fixture().apply { inventory=listOf(mutateMux { it.copy(endpoints=listOf(ep,it.endpoints.last())) }) }
            f.failure("SCOPED_INTERFACE_MISSING_OR_AMBIGUOUS"); assertTrue(f.calls.isEmpty())
        }
        for(size in listOf(1,20,2048)) {
            val f=Fixture().apply { inventory=listOf(mutateMux { it.copy(endpoints=it.endpoints.map { ep ->
                if(ep.address==0x85) ep.copy(maxPacketSize=size) else ep
            }) }) }
            f.failure("PRECONDITION_FAILURE"); assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun activeNot5AndGetFailurePreventClaimAndTraffic() {
        for(config in listOf(0,1,4,6,255)) {
            val f=Fixture().apply { this.config=config }
            f.failure("ACTIVE_CONFIGURATION_NOT_5")
            assertEquals(listOf("open","get","close"),f.calls)
        }
        val f=Fixture().apply { throwsAt="get" }
        f.failure("GET_CONFIGURATION_FAILURE")
        assertEquals(listOf("open","get","close"),f.calls)
    }
    @Test fun claimFalseAndExceptionNeverTransferOrRelease() {
        for(throws in listOf(false,true)) {
            val f=Fixture().apply { claim=false; if(throws) throwsAt="claim" }
            f.failure(if(throws) "CLAIM_EXCEPTION" else "CLAIM_RETURNED_FALSE")
            assertEquals(listOf("open","get","claim","close"),f.calls)
        }
    }
    @Test fun shortNegativeAndImpossibleOutNeverRead() {
        for(count in listOf(-1,0,1,19,21)) {
            val f=Fixture().apply { written=count }
            f.failure(if(count<0) "BULK_OUT_FAILURE" else if(count<20) "SHORT_OUT" else "INVALID_OUT_RESULT")
            assertEquals(listOf("open","get","claim","out","release","close"),f.calls)
        }
    }
    @Test fun oneInTimeoutFragmentShortOrExtraNeverContinues() {
        for(count in listOf(-1,0,8,16,19,24,40)) {
            val f=Fixture().apply { received=count }
            val report=f.failure(if(count<0) "BULK_IN_TIMEOUT_OR_FAILURE" else if(count<20) "SHORT_RESPONSE" else "UNEXPECTED_PENDING_DATA")
            assertFalse(report.contains("Response hex="))
            assertEquals(listOf("open","get","claim","out","in","release","close"),f.calls)
        }
    }
    @Test fun malformedHeaderWrongProtocolMajorAndLengthStopWithoutRawPayload() {
        for((offset,value,reason) in listOf(Triple(0,6,"WRONG_PROTOCOL"),Triple(0,1,"WRONG_PROTOCOL"),
            Triple(4,0,"WRONG_DECLARED_LENGTH"),Triple(4,24,"WRONG_DECLARED_LENGTH"),
            Triple(8,1,"WRONG_MAJOR_VERSION"),Triple(8,3,"WRONG_MAJOR_VERSION"))) {
            val f=Fixture().apply { ByteBuffer.wrap(response).putInt(offset,value) }
            val report=f.failure(reason)
            assertFalse(report.contains("Response hex="))
            assertEquals(1,f.calls.count { it=="in" })
        }
    }
    @Test fun exceptionsAllCloseAndReleaseOnlyAfterClaimTrue() {
        for(stage in listOf("open","get","claim","out","in","release","close")) {
            val f=Fixture().apply { throwsAt=stage }
            val report=f.diagnostic.run()
            assertFalse(report.contains(UsbMuxVersionDiagnostic.PASS))
            assertEquals(if(stage=="open") 0 else 1,f.calls.count { it=="close" })
            assertEquals(if(stage in listOf("open","get","claim")) 0 else 1,f.calls.count { it=="release" })
        }
    }
    @Test fun releaseFalseSuppressesPassAndCloseStillRuns() {
        val f=Fixture().apply { release=false }
        f.failure("RELEASE_FAILURE")
        assertEquals(listOf("release","close"),f.calls.takeLast(2))
    }
    @Test fun detachAndCancellationAtEachStageStopLaterTrafficButCleanup() {
        for(stage in listOf("get","claim","out","in","release")) {
            val f=Fixture().apply { after={ if(it==stage) inventory=emptyList() } }
            f.failure("DEVICE_DISAPPEARED")
            assertEquals(1,f.calls.count { it=="close" })
            if(stage in listOf("get","claim")) assertFalse(f.calls.contains("out"))
        }
        for(stage in listOf("get","claim","out","in","release")) {
            val f=Fixture().apply { after={ if(it==stage) diagnostic.cancel() } }
            f.failure("CANCELLED")
            assertEquals(1,f.calls.count { it=="close" })
            if(stage in listOf("get","claim")) assertFalse(f.calls.contains("out"))
        }
    }
    @Test fun cancelledBeforeRunNeverOpens() {
        val f=Fixture()
        f.diagnostic.cancel()
        f.failure("CANCELLED")
        assertTrue(f.calls.isEmpty())
    }
    @Test fun transientDetachNotificationSuppressesPassEvenWhenInventoryReturns() {
        val f=Fixture().apply { after={ if(it=="in") diagnostic.deviceDetached() } }
        f.failure("DEVICE_DISAPPEARED")
        assertEquals(listOf("release","close"),f.calls.takeLast(2))
    }
}
