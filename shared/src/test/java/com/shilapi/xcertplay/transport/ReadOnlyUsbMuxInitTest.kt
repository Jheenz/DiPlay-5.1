package com.shilapi.xcertplay.transport

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class ReadOnlyUsbMuxInitTest {
    private class Pipe(val reply:ByteArray?) : UsbMuxBulkPipe {
        val writes=CopyOnWriteArrayList<ByteArray>()
        var readCount=0
        var closed=false
        var failSetup=false
        override fun write(data:ByteArray,timeoutMillis:Int) {
            writes+=data.copyOf()
            if(writes.size==1) assertEquals(1000,timeoutMillis)
            else { assertEquals(2000,timeoutMillis);if(failSetup) throw IphoneUsbException.Protocol("setup failed") }
        }
        override fun read(timeoutMillis:Long):ByteArray? {
            if(readCount++==0) return reply
            Thread.sleep(1)
            return null
        }
        override fun close() { closed=true }
    }
    @Test fun oneVersionOneAuditedSetupWithReplyDerivedAckAndNoTcp() {
        val reply=UsbMuxVersionPacket.request().also { it[12]=0x12;it[13]=0x34 }
        val pipe=Pipe(reply)
        val host=Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe)
        host.close()
        assertEquals(2,pipe.writes.size)
        assertArrayEquals(UsbMuxVersionPacket.request(),pipe.writes[0])
        assertArrayEquals(byteArrayOf(0,0,0,2,0,0,0,17,0xfe.toByte(),0xed.toByte(),0xfa.toByte(),0xce.toByte(),0,0,0x12,0x34,7),pipe.writes[1])
        assertTrue(pipe.closed)
    }
    @Test fun shortExtraWrongOrTimeoutVersionNeverSendsSetup() {
        val wrong=UsbMuxVersionPacket.request().also { it[11]=1 }
        for(reply in listOf(null,ByteArray(8),UsbMuxVersionPacket.request()+ByteArray(4),wrong)) {
            val pipe=Pipe(reply)
            assertThrows(IphoneUsbException::class.java) { Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe) }
            assertEquals(1,pipe.writes.size)
            assertEquals(1,pipe.readCount)
            assertTrue(pipe.closed)
        }
    }
    @Test fun failedSetupIsNotRetriedAndClosesPipe() {
        val pipe=Pipe(UsbMuxVersionPacket.request()).apply { failSetup=true }
        assertThrows(IphoneUsbException::class.java) { Iap2UsbMuxHost.openReadOnlyDiagnostic(pipe) }
        assertEquals(2,pipe.writes.size)
        assertEquals(1,pipe.readCount)
        assertTrue(pipe.closed)
    }
}
