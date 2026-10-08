package com.shilapi.xcertplay

import android.app.PendingIntent
import android.hardware.usb.UsbDevice
import com.shilapi.xcertplay.transport.UsbMuxVersionPacket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidUsbMuxVersionAccessTest {
    private class Fixture {
        val base = AndroidActiveConfig5UsbMuxClaimAccessTest.Fixture()
        val connection = base.connection
        val access = AndroidUsbMuxVersionAccess(base.manager)
        val target = base.interfaces.getValue(5).first()
        val output = target.getEndpoint(0)
        val input = target.getEndpoint(1)
        init {
            out(20)
            read(20)
        }
        fun out(count: Int) {
            `when`(connection.bulkTransfer(eq(output), any(ByteArray::class.java), eq(20), eq(1000))).thenReturn(count)
        }
        fun read(count: Int) {
            doAnswer { invocation ->
                UsbMuxVersionPacket.request().copyInto(invocation.getArgument(1))
                count
            }.`when`(connection).bulkTransfer(eq(input), any(ByteArray::class.java), eq(1024), eq(1000))
        }
        fun session() = access.open(access.devices().single())
        fun verifyGet() = verify(connection).controlTransfer(eq(0x80), eq(8), eq(0), eq(0),
            any(ByteArray::class.java), eq(1), eq(1000))
        fun verifyClaim() = verify(connection).claimInterface(target, false)
        fun verifyOut() = verify(connection).bulkTransfer(eq(output), any(ByteArray::class.java), eq(20), eq(1000))
        fun verifyIn() = verify(connection).bulkTransfer(eq(input), any(ByteArray::class.java), eq(1024), eq(1000))
    }

    @Test fun exactOrderedPublicCallsScopedObjectAndNoOtherConnectionOrPermissionOperations() {
        val f=Fixture()
        val report=UsbMuxVersionDiagnostic(f.access).run()
        assertTrue(report,report.contains(UsbMuxVersionDiagnostic.PASS))
        val ordered=inOrder(f.connection)
        ordered.verify(f.connection).controlTransfer(eq(0x80),eq(8),eq(0),eq(0),any(ByteArray::class.java),eq(1),eq(1000))
        ordered.verify(f.connection).claimInterface(f.target,false)
        ordered.verify(f.connection).bulkTransfer(eq(f.output),argThat<ByteArray> { it.contentEquals(UsbMuxVersionPacket.request()) },eq(20),eq(1000))
        ordered.verify(f.connection).bulkTransfer(eq(f.input),any(ByteArray::class.java),eq(1024),eq(1000))
        ordered.verify(f.connection).releaseInterface(f.target)
        ordered.verify(f.connection).close()
        verifyNoMoreInteractions(f.connection)
        verify(f.base.manager).openDevice(f.base.device)
        verify(f.base.manager,never()).requestPermission(any(UsbDevice::class.java),any(PendingIntent::class.java))
        verify(f.base.device,never()).getInterface(anyInt())
        verify(f.base.device,never()).interfaceCount
    }
    @Test fun adapterRejectsTrafficBeforeVerificationOrClaimAndAfterReleaseOrClose() {
        val f=Fixture()
        val s=f.session()
        val packet=UsbMuxVersionPacket.request()
        assertThrows(IllegalStateException::class.java) { s.claimUsbMux() }
        assertThrows(IllegalStateException::class.java) { s.writeVersion(packet) }
        assertThrows(IllegalStateException::class.java) { s.readVersion(ByteArray(1024)) }
        assertEquals(5,s.configuration {})
        assertThrows(IllegalStateException::class.java) { s.configuration {} }
        assertTrue(s.claimUsbMux())
        assertTrue(s.releaseUsbMux())
        assertThrows(IllegalStateException::class.java) { s.writeVersion(packet) }
        s.close()
        assertThrows(IllegalStateException::class.java) { s.readVersion(ByteArray(1024)) }
        f.verifyGet();f.verifyClaim()
        verify(f.connection).releaseInterface(f.target);verify(f.connection).close()
        verifyNoMoreInteractions(f.connection)
    }
    @Test fun requestAndCapacityAreGuardedAndEachOperationIsOneShot() {
        val f=Fixture()
        val s=f.session()
        s.configuration {}
        assertTrue(s.claimUsbMux())
        assertThrows(IllegalStateException::class.java) { s.writeVersion(ByteArray(17)) }
        assertThrows(IllegalStateException::class.java) { s.writeVersion(ByteArray(20)) }
        assertEquals(20,s.writeVersion(UsbMuxVersionPacket.request()))
        assertThrows(IllegalStateException::class.java) { s.writeVersion(UsbMuxVersionPacket.request()) }
        assertThrows(IllegalStateException::class.java) { s.readVersion(ByteArray(20)) }
        assertEquals(20,s.readVersion(ByteArray(1024)))
        assertThrows(IllegalStateException::class.java) { s.readVersion(ByteArray(1024)) }
        assertTrue(s.releaseUsbMux())
        assertThrows(IllegalStateException::class.java) { s.releaseUsbMux() }
        s.close()
        f.verifyGet();f.verifyClaim();f.verifyOut();f.verifyIn()
        verify(f.connection).releaseInterface(f.target);verify(f.connection).close()
        verifyNoMoreInteractions(f.connection)
    }
    @Test fun falseAndThrowingOutAreNeverRetriedAndCannotRead() {
        for(throws in listOf(false,true)) {
            val f=Fixture()
            if(throws) `when`(f.connection.bulkTransfer(eq(f.output),any(ByteArray::class.java),eq(20),eq(1000)))
                .thenThrow(IllegalStateException("OUT failure"))
            else f.out(19)
            val s=f.session()
            s.configuration {};s.claimUsbMux()
            if(throws) assertThrows(IllegalStateException::class.java) { s.writeVersion(UsbMuxVersionPacket.request()) }
            else assertEquals(19,s.writeVersion(UsbMuxVersionPacket.request()))
            assertThrows(IllegalStateException::class.java) { s.writeVersion(UsbMuxVersionPacket.request()) }
            assertThrows(IllegalStateException::class.java) { s.readVersion(ByteArray(1024)) }
            s.releaseUsbMux();s.close()
            f.verifyGet();f.verifyClaim();f.verifyOut()
            verify(f.connection).releaseInterface(f.target);verify(f.connection).close()
            verifyNoMoreInteractions(f.connection)
        }
    }
    @Test fun negativeAndThrowingInConsumeAttemptWithoutRetry() {
        for(throws in listOf(false,true)) {
            val f=Fixture()
            if(throws) `when`(f.connection.bulkTransfer(eq(f.input),any(ByteArray::class.java),eq(1024),eq(1000)))
                .thenThrow(IllegalStateException("IN failure"))
            else f.read(-1)
            val s=f.session()
            s.configuration {};s.claimUsbMux();s.writeVersion(UsbMuxVersionPacket.request())
            if(throws) assertThrows(IllegalStateException::class.java) { s.readVersion(ByteArray(1024)) }
            else assertEquals(-1,s.readVersion(ByteArray(1024)))
            assertThrows(IllegalStateException::class.java) { s.readVersion(ByteArray(1024)) }
            s.releaseUsbMux();s.close()
            f.verifyGet();f.verifyClaim();f.verifyOut();f.verifyIn()
            verify(f.connection).releaseInterface(f.target);verify(f.connection).close()
            verifyNoMoreInteractions(f.connection)
        }
    }
    @Test fun falseClaimNeverTransfersAndNegativeShortGetNeverClaims() {
        val f=Fixture()
        `when`(f.connection.claimInterface(f.target,false)).thenReturn(false)
        assertTrue(UsbMuxVersionDiagnostic(f.access).run().contains("CLAIM_RETURNED_FALSE"))
        f.verifyGet();f.verifyClaim();verify(f.connection).close();verifyNoMoreInteractions(f.connection)
        for(count in listOf(-1,0,2)) {
            val failed=Fixture()
            failed.base.read(5,count)
            assertTrue(UsbMuxVersionDiagnostic(failed.access).run().contains("GET_CONFIGURATION_FAILURE"))
            failed.verifyGet();verify(failed.connection).close();verifyNoMoreInteractions(failed.connection)
        }
    }
    @Test fun wrongActiveValueNeverClaimsAndDetachBetweenTransfersNeverReads() {
        val f=Fixture()
        f.base.read(1)
        assertTrue(UsbMuxVersionDiagnostic(f.access).run().contains("ACTIVE_CONFIGURATION_NOT_5"))
        f.verifyGet();verify(f.connection).close();verifyNoMoreInteractions(f.connection)
        val detached=Fixture()
        doAnswer {
            `when`(detached.base.manager.deviceList).thenReturn(hashMapOf())
            20
        }.`when`(detached.connection).bulkTransfer(eq(detached.output),any(ByteArray::class.java),eq(20),eq(1000))
        assertTrue(UsbMuxVersionDiagnostic(detached.access).run().contains("DEVICE_DISAPPEARED"))
        detached.verifyGet();detached.verifyClaim();detached.verifyOut()
        verify(detached.connection).releaseInterface(detached.target);verify(detached.connection).close()
        verifyNoMoreInteractions(detached.connection)
    }
    @Test fun scopedSessionConstructionFailureClosesAndPreservesCleanupError() {
        val f=Fixture()
        doThrow(IllegalStateException("close failure")).`when`(f.connection).close()
        val error=assertThrows(IllegalStateException::class.java) {
            f.base.access.openScoped(f.access.devices().single()) { _, _, _ ->
                throw IllegalStateException("construction failed")
            }
        }
        assertEquals("construction failed",error.message)
        assertEquals("close failure",error.suppressed.single().message)
        verify(f.connection).close()
        verifyNoMoreInteractions(f.connection)
    }
}
