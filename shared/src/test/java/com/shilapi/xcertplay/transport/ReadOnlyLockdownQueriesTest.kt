package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class ReadOnlyLockdownQueriesTest {
    private fun reply(request: String, key: String, value: String) = LockdownPlistValue.Dictionary(
        mapOf("Request" to LockdownPlistValue.Text(request), key to LockdownPlistValue.Text(value)))

    @Test fun exactTwoReadOnlyRequestsAndNoRetry() {
        val requests = mutableListOf<LockdownPlistValue.Dictionary>()
        val queries = ReadOnlyLockdownQueries {
            requests += it
            if (requests.size == 1) reply("QueryType","Type","com.apple.mobile.lockdown")
            else reply("GetValue","Value","iPhone15,2")
        }
        assertThrows(IllegalStateException::class.java) { queries.productType() }
        assertEquals("com.apple.mobile.lockdown",queries.queryType())
        assertEquals("iPhone15,2",queries.productType())
        assertEquals(setOf("Label","Request"),requests[0].entries.keys)
        assertEquals(setOf("Label","Request","Key"),requests[1].entries.keys)
        assertEquals(LockdownPlistValue.Text("ProductType"),requests[1].entries["Key"])
        assertThrows(IllegalStateException::class.java) { queries.queryType() }
        assertThrows(IllegalStateException::class.java) { queries.productType() }
        assertEquals(2,requests.size)
    }
    @Test fun queryErrorMismatchAndWrongTypePreventGetValue() {
        val cases = listOf(
            LockdownPlistValue.Dictionary(mapOf("Error" to LockdownPlistValue.Text("PairingDialogResponsePending"))),
            reply("GetValue","Type","com.apple.mobile.lockdown"),
            reply("QueryType","Type","different"),
            LockdownPlistValue.Dictionary(emptyMap()),
        )
        cases.forEach { response ->
            var calls=0
            val queries=ReadOnlyLockdownQueries { calls++; response }
            assertThrows(IphoneUsbException.Protocol::class.java) { queries.queryType() }
            assertThrows(IllegalStateException::class.java) { queries.queryType() }
            assertThrows(IllegalStateException::class.java) { queries.productType() }
            assertEquals(1,calls)
        }
    }
    @Test fun productErrorsAndUnsafeMetadataStopWithoutRetry() {
        for(value in listOf("", "private\nvalue","x".repeat(65))) {
            var calls=0
            val queries=ReadOnlyLockdownQueries {
                calls++
                if(calls==1) reply("QueryType","Type","com.apple.mobile.lockdown") else reply("GetValue","Value",value)
            }
            queries.queryType()
            assertThrows(IphoneUsbException.Protocol::class.java) { queries.productType() }
            assertThrows(IllegalStateException::class.java) { queries.productType() }
            assertEquals(2,calls)
        }
    }
    @Test fun productRemoteErrorNeverFallsBackOrRetries() {
        var calls=0
        val queries=ReadOnlyLockdownQueries {
            if(++calls==1) reply("QueryType","Type","com.apple.mobile.lockdown")
            else LockdownPlistValue.Dictionary(mapOf("Error" to LockdownPlistValue.Text("PasswordProtected")))
        }
        queries.queryType()
        val error=assertThrows(IphoneUsbException.Protocol::class.java) { queries.productType() }
        assertTrue(error.message.orEmpty().contains("PasswordProtected"))
        assertThrows(IllegalStateException::class.java) { queries.productType() }
        assertEquals(2,calls)
    }
}
