package com.shilapi.xcertplay

import android.hardware.usb.UsbEndpoint
import com.shilapi.xcertplay.transport.UsbMuxVersionPacket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class AndroidReadOnlyLockdownAccessTest {
    internal class Fixture {
        val base=AndroidActiveConfig5UsbMuxClaimAccessTest.Fixture()
        val access=AndroidReadOnlyLockdownAccess(base.manager)
        val target=base.interfaces.getValue(5).first()
        val output=target.getEndpoint(0)
        val input=target.getEndpoint(1)
        val incoming=LinkedBlockingQueue<ByteArray>()
        val writes=CopyOnWriteArrayList<ByteArray>()
        val requests=CopyOnWriteArrayList<String>()
        var shortVersion=false
        var badReply=false
        var shortSetup=false
        var queryError=false
        var finError=false
        var refuseTcp=false
        var controlledReply: ((String) -> String)? = null
        val tlsRecords=CopyOnWriteArrayList<ByteArray>()
        var tlsReply: ((ByteArray) -> ByteArray?)? = null
        var validateControl: Int? = null
        var validateShortWrite = false
        var validateNoReply = false
        init {
            doAnswer { invocation ->
                val packet=invocation.getArgument<ByteArray>(1).copyOf(invocation.getArgument<Int>(2))
                writes+=packet
                if(packet.size==20 && packet[3]==0.toByte()) {
                    if(!shortVersion) incoming.add(UsbMuxVersionPacket.request().also { if(badReply) it[11]=1 })
                    if(shortVersion) 19 else packet.size
                } else if(packet.size==17 && packet[3]==2.toByte()) {
                    if(shortSetup) 16 else 17
                } else {
                    assertEquals(6,packet[3].toInt())
                    assertEquals(62078,ByteBuffer.wrap(packet).getShort(18).toInt() and 65535)
                    val flags=packet[29].toInt() and 255
                    if(flags==2) incoming.add(tcp(if(refuseTcp)0x14 else 0x12,ByteArray(0)))
                    else if(packet.size>36) {
                        val body=packet.copyOfRange(36,packet.size)
                        if (tlsReply != null && (body[0]==0x16.toByte() || tlsRecords.isNotEmpty())) {
                            tlsRecords+=body
                            checkNotNull(tlsReply).invoke(body)?.let { incoming.add(it) }
                            return@doAnswer packet.size
                        }
                        val length=ByteBuffer.wrap(body).int
                        assertEquals(length,body.size-4)
                        val xml=body.copyOfRange(4,body.size).toString(Charsets.UTF_8)
                        requests+=xml
                        if (xml.contains("<string>ValidatePair</string>") && validateShortWrite) {
                            return@doAnswer packet.size - 1
                        }
                        if (xml.contains("<string>ValidatePair</string>") && validateNoReply) return@doAnswer packet.size
                        if (xml.contains("<string>ValidatePair</string>") && validateControl != null) {
                            incoming.add(tcp(checkNotNull(validateControl), ByteArray(0)))
                            return@doAnswer packet.size
                        }
                        val query=xml.contains("<string>QueryType</string>")
                        val operation=if(query) "QueryType" else "GetValue"
                        if (controlledReply == null) {
                            if(!query) assertTrue(xml.contains("<string>ProductType</string>"))
                            for(forbidden in listOf("<string>Pair</string>","StartSession","ValidatePair","StartService","HostID","SystemBUID")) {
                                assertFalse(xml,xml.contains(forbidden))
                            }
                        }
                        val value=if(query && queryError) "<key>Error</key><string>PairingDialogResponsePending</string>"
                        else if(query) "<key>Type</key><string>com.apple.mobile.lockdown</string>"
                        else "<key>Value</key><string>iPhone15,2</string>"
                        val reply=(controlledReply?.invoke(xml) ?:
                            "<plist version=\"1.0\"><dict><key>Request</key><string>$operation</string>$value</dict></plist>").toByteArray()
                        val frame=ByteBuffer.allocate(reply.size+4).putInt(reply.size).put(reply).array()
                        incoming.add(tcp(0x10,frame))
                    }
                    if(finError && (flags and 1)!=0) -1 else packet.size
                }
            }.`when`(base.connection).bulkTransfer(eq(output),any(ByteArray::class.java),anyInt(),anyInt())
            doAnswer { invocation ->
                val packet=incoming.poll(invocation.getArgument<Int>(3).toLong(),TimeUnit.MILLISECONDS)
                if(packet==null) -1 else { packet.copyInto(invocation.getArgument(1));packet.size }
            }.`when`(base.connection).bulkTransfer(eq(input),any(ByteArray::class.java),anyInt(),anyInt())
        }
        fun tcp(flags:Int,payload:ByteArray):ByteArray {
            val packet=ByteBuffer.allocate(36+payload.size)
            packet.putInt(6).putInt(packet.capacity()).putInt(0xfaceface.toInt()).putShort(0).putShort(0)
                .putShort(62078.toShort()).putShort(1).putInt(1).putInt(1)
                .put(0x50.toByte()).put(flags.toByte()).putShort(512).putInt(0).put(payload)
            return packet.array()
        }
        fun run()=ReadOnlyLockdownDiagnostic(access).run()
        fun verifyCleanup() {
            verify(base.connection).controlTransfer(eq(0x80),eq(8),eq(0),eq(0),any(ByteArray::class.java),eq(1),eq(1000))
            verify(base.connection).claimInterface(target,false)
            verify(base.connection).releaseInterface(target)
            verify(base.connection).close()
            verify(base.device,never()).getInterface(anyInt())
            verify(base.connection,never()).setConfiguration(any())
            verify(base.connection,never()).setInterface(any())
        }
    }
    @Test fun realFramingOneVersionOneSetupOneTcpTwoQueriesAndFinallyReleaseClose() {
        val f=Fixture()
        val report=f.run()
        assertTrue(report,report.contains("Outcome=PASS"))
        assertEquals(1,f.writes.count { it.size==20 && it[3]==0.toByte() })
        assertEquals(1,f.writes.count { it.size==17 && it[3]==2.toByte() })
        assertEquals(1,f.writes.count { it.size==36 && it[29]==2.toByte() })
        assertEquals(2,f.requests.size)
        assertTrue(f.requests[0].contains("QueryType"))
        assertTrue(f.requests[1].contains("ProductType"))
        assertArrayEquals(byteArrayOf(0,0,0,2,0,0,0,17,0xfe.toByte(),0xed.toByte(),0xfa.toByte(),0xce.toByte(),0,0,0,0,7),
            f.writes.single { it.size==17 })
        f.verifyCleanup()
    }
    @Test fun shortOrWrongVersionOrSetupStopsBeforeTcpAndNoRetry() {
        for(stage in listOf("out","in","setup")) {
            val f=Fixture().apply { shortVersion=stage=="out";badReply=stage=="in";shortSetup=stage=="setup" }
            val report=f.run()
            assertFalse(report,report.contains("Outcome=PASS"))
            assertTrue(report.contains("USBMUX_INIT"))
            assertEquals(if(stage=="setup")2 else 1,f.writes.size)
            assertTrue(f.requests.isEmpty())
            f.verifyCleanup()
        }
    }
    @Test fun trustPendingRemoteErrorStopsAtQueryWithoutProductOrPairFallback() {
        val f=Fixture().apply { queryError=true }
        val report=f.run()
        assertFalse(report.contains("Outcome=PASS"))
        assertTrue(report,report.contains("QUERY_TYPE"))
        assertTrue(report.contains("PairingDialogResponsePending"))
        assertEquals(1,f.requests.size)
        f.verifyCleanup()
    }
    @Test fun failedTcpFinIsCleanupFailureNotOverallPass() {
        val f=Fixture().apply { finError=true }
        val report=f.run()
        assertFalse(report,report.contains("Outcome=PASS"))
        assertFalse(report.contains(ReadOnlyLockdownDiagnostic.PASS))
        assertTrue(report.contains("Failure stage=RELEASE"))
        f.verifyCleanup()
    }
    @Test fun refusedTcpStopsWithoutQueriesOrReconnect() {
        val f=Fixture().apply { refuseTcp=true }
        val report=f.run()
        assertFalse(report,report.contains("Outcome=PASS"))
        assertTrue(report.contains("LOCKDOWN_TCP_CONNECT"))
        assertEquals(1,f.writes.count { it.size==36 && it[29]==2.toByte() })
        assertTrue(f.requests.isEmpty())
        f.verifyCleanup()
    }
    @Test fun wrongActiveConfigurationNeverClaimsOrWrites() {
        val f=Fixture()
        f.base.read(1)
        assertTrue(f.run().contains("ACTIVE_CONFIGURATION_NOT_5"))
        verify(f.base.connection,never()).claimInterface(any(),anyBoolean())
        assertTrue(f.writes.isEmpty())
        verify(f.base.connection).close()
    }
}
