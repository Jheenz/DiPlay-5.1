package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ControlledLockdownPairingTest {
    private class Store : DiagnosticPairStore {
        var candidate: DiagnosticPairCandidate? = null
        val saves = mutableListOf<DiagnosticPairCandidate>()
        var failSave = -1
        override fun load(deviceId: String) = candidate
        override fun systemBuid() = BUID
        override fun save(candidate: DiagnosticPairCandidate) {
            check(saves.size != failSave) { "SECRET fake storage error" }
            saves += candidate
            this.candidate = candidate
        }
    }
    private class Fixture {
        val store = Store()
        val operations = mutableListOf<String>()
        val requests = mutableListOf<LockdownPlistValue.Dictionary>()
        val logs = mutableListOf<String>()
        var errorAt: String? = null
        var errorCode = "PairingDialogResponsePending"
        var malformedAt: String? = null
        var cancelled = false
        var cancelAfterPair = false
        var generation = 0
        var materialValid = true
        var materialLinkageError: LinkageError? = null
        var transportError: Exception? = null
        var transportErrorAt = "Pair"
        var existingOnly = false
        fun run() = ControlledLockdownPairing(
            request = { req ->
                val op = (req.entries.getValue("Request") as LockdownPlistValue.Text).value
                val key = (req.entries["Key"] as? LockdownPlistValue.Text)?.value
                val name = key ?: op
                requests += req; operations += name
                if (name == transportErrorAt) transportError?.let { throw it }
                assertTrue(name, name in listOf("UniqueDeviceID", "UntrustedHostBUID", "DevicePublicKey",
                    "WiFiAddress", "Pair", "ValidatePair"))
                if (name == "Pair") {
                    assertNotNull(store.candidate?.record)
                    assertEquals(DiagnosticPairState.PREPARED, store.candidate?.state)
                    if (cancelAfterPair) cancelled = true
                }
                LockdownPlistValue.Dictionary(linkedMapOf<String, LockdownPlistValue>(
                    "Request" to LockdownPlistValue.Text(if (malformedAt == name) "StartSession" else op),
                ).apply {
                    if (errorAt == name) put("Error", LockdownPlistValue.Text(errorCode))
                    else when (key) {
                        "UniqueDeviceID" -> put("Value", LockdownPlistValue.Text(DEVICE))
                        "DevicePublicKey" -> put("Value", LockdownPlistValue.Data(byteArrayOf(1)))
                        "WiFiAddress" -> put("Value", LockdownPlistValue.Text("aa:bb:cc:dd:ee:ff"))
                    }
                    if (name == "Pair" && errorAt == null) put("EscrowBag", LockdownPlistValue.Data(byteArrayOf(7, 8)))
                })
            }, store = store, active = { check(!cancelled) }, report = logs::add,
            generate = { _, wifi, host, buid ->
                generation++
                LockdownPairRecord.restore(host, buid, wifi, SECRET, SECRET, SECRET, SECRET, SECRET, SECRET)
            }, verifyMaterial = {
                materialLinkageError?.let { throw it }
                check(materialValid) { "SECRET certificate failure" }
            },
            existingOnly = existingOnly,
        ).run()
        fun failure(): ControlledPairFailure {
            try { run(); fail("Expected STOP") } catch (error: ControlledPairFailure) { return error }
            error("unreachable")
        }
    }
    @Test fun exactPreparationPairValidationAndPersistenceOrderNoSessionOrTls() {
        val f = Fixture(); f.run()
        assertEquals(listOf("UniqueDeviceID", "UntrustedHostBUID", "DevicePublicKey", "WiFiAddress", "Pair", "ValidatePair"), f.operations)
        assertEquals(4, f.store.saves.size)
        assertNull(f.store.saves.first().record)
        assertEquals(DiagnosticPairState.VALIDATED, f.store.candidate?.state)
        assertArrayEquals(byteArrayOf(7, 8), f.store.candidate?.escrowBag)
        val pair = f.requests.single { (it.entries["Request"] as LockdownPlistValue.Text).value == "Pair" }
        val fields = (pair.entries.getValue("PairRecord") as LockdownPlistValue.Dictionary).entries.keys
        assertEquals(setOf("DeviceCertificate", "HostCertificate", "HostID", "RootCertificate", "SystemBUID"), fields)
        assertEquals(LockdownPlistValue.Text("2"), pair.entries["ProtocolVersion"])
        assertTrue(pair.entries.containsKey("PairingOptions"))
        assertFalse(f.requests.last().entries.containsKey("PairingOptions"))
        assertFalse(f.logs.joinToString().contains("SECRET"))
    }
    @Test fun pendingStopsAndNewManualRunReusesIdentityAndMaterialWithoutReadRetries() {
        val f = Fixture().apply { errorAt = "Pair" }
        assertEquals("TRUST_PENDING", f.failure().reason)
        assertFalse(f.operations.contains("ValidatePair"))
        val record = f.store.candidate?.record
        val host = f.store.candidate?.hostId
        f.operations.clear(); f.errorAt = null
        f.run()
        assertEquals(listOf("UniqueDeviceID", "UntrustedHostBUID", "Pair", "ValidatePair"), f.operations)
        assertSame(record, f.store.candidate?.record)
        assertEquals(host, f.store.candidate?.hostId)
        assertEquals(1, f.generation)
    }
    @Test fun denialLockedUnknownAndMalformedNeverValidateOrLeakRemoteText() {
        for (code in listOf("UserDeniedPairing", "PasswordProtected", "SECRET remote credential")) {
            val f = Fixture().apply { errorAt = "Pair"; errorCode = code }
            val failure = f.failure()
            assertFalse(failure.message!!.contains("SECRET"))
            assertFalse(f.operations.contains("ValidatePair"))
            assertEquals(1, f.operations.count { it == "Pair" })
        }
        val malformed = Fixture().apply { malformedAt = "Pair" }
        assertEquals("RESPONSE_REQUEST_MISMATCH", malformed.failure().reason)
        assertFalse(malformed.operations.contains("ValidatePair"))
    }
    @Test fun acceptedOrValidatedRecordOnlyValidatesNeverPairsOrWritesDeviceValues() {
        for (state in listOf(DiagnosticPairState.PAIRED, DiagnosticPairState.VALIDATED)) {
            val f = Fixture()
            val record = LockdownPairRecord.restore(HOST, BUID, "aa:bb:cc:dd:ee:ff", SECRET, SECRET, SECRET, SECRET, SECRET, SECRET)
            f.store.candidate = DiagnosticPairCandidate(DEVICE, HOST, BUID, record, state, byteArrayOf(8))
            f.run()
            assertEquals(listOf("UniqueDeviceID", "ValidatePair"), f.operations)
            assertEquals(0, f.generation)
        }
    }
    @Test fun allPreparationStorageAndValidationFailuresStopWithoutFallback() {
        for (op in listOf("UniqueDeviceID", "UntrustedHostBUID", "DevicePublicKey", "WiFiAddress", "ValidatePair")) {
            val f = Fixture().apply { errorAt = op }
            f.failure()
            assertTrue(f.operations.count { it == "Pair" } <= 1)
            assertTrue(f.operations.count { it == "ValidatePair" } <= 1)
            if (op != "ValidatePair") assertFalse(f.operations.contains("Pair"))
        }
        for (save in 0..3) {
            val f = Fixture().apply { store.failSave = save }
            val failure = f.failure()
            assertFalse(failure.message!!.contains("SECRET"))
            if (save < 2) assertFalse(f.operations.contains("Pair"))
            if (save == 2) assertFalse(f.operations.contains("ValidatePair"))
        }
    }
    @Test fun cancellationAfterPairKeepsAcceptedRecordBeforeStoppingValidation() {
        val f = Fixture().apply { cancelAfterPair = true }
        f.failure()
        assertEquals(DiagnosticPairState.PAIRED, f.store.candidate?.state)
        assertFalse(f.operations.contains("ValidatePair"))
    }

    @Test fun invalidMaterialStopsBeforePairAndTimeoutNeverRetriesOrValidates() {
        val invalid = Fixture().apply { materialValid = false }
        assertEquals("GENERATE_MATERIAL", invalid.failure().stage)
        assertFalse(invalid.operations.contains("Pair"))
        val timeout = Fixture().apply { transportError = IphoneUsbException.TimedOut("SECRET timeout detail") }
        val failure = timeout.failure()
        assertEquals("PAIR", failure.stage)
        assertEquals("LOCKDOWN_TIMEOUT", failure.reason)
        assertEquals(1, timeout.operations.count { it == "Pair" })
        assertFalse(timeout.operations.contains("ValidatePair"))
        assertFalse(failure.message!!.contains("SECRET"))
        assertNotNull(timeout.store.candidate?.record)
    }

    @Test fun linkageFailureRetainsExactPreparationStageWithoutPairOrSecretText() {
        val f = Fixture().apply {
            materialLinkageError = NoSuchMethodError("No virtual method verify(Ljava/security/PublicKey;Ljava/security/Provider;)V " +
                "in class Ljava/security/cert/X509Certificate; SECRET")
        }
        val failure = f.failure()
        assertEquals("GENERATE_MATERIAL", failure.stage)
        assertEquals("API_LINKAGE_FAILURE", failure.reason)
        assertTrue(failure.linkageDetails!!.contains("->verify("))
        assertFalse(failure.linkageDetails!!.contains("SECRET"))
        assertFalse(f.operations.contains("Pair"))
        assertFalse(f.operations.contains("ValidatePair"))
    }

    @Test fun existingOnlyRefusesMissingOrPreparedWithoutPairOrGeneration() {
        for (state in listOf(null, DiagnosticPairState.PREPARED)) {
            val f = Fixture().apply { existingOnly = true }
            if (state != null) f.store.candidate = DiagnosticPairCandidate(DEVICE, HOST, BUID)
            assertTrue(f.failure().reason.startsWith("PAIR_RECORD_"))
            assertEquals(listOf("UniqueDeviceID"), f.operations)
            assertEquals(0, f.generation)
            assertTrue(f.store.saves.isEmpty())
        }
    }

    @Test fun existingOnlyAcceptedStatesValidateExactlyOnceAndPreserveOriginalTransportFailure() {
        for (state in listOf(DiagnosticPairState.PAIRED, DiagnosticPairState.VALIDATED)) {
            val f = Fixture().apply { existingOnly = true }
            val record = LockdownPairRecord.restore(HOST, BUID, "aa:bb:cc:dd:ee:ff",
                SECRET, SECRET, SECRET, SECRET, SECRET, SECRET)
            f.store.candidate = DiagnosticPairCandidate(DEVICE, HOST, BUID, record, state)
            f.run()
            assertEquals(listOf("UniqueDeviceID", "ValidatePair"), f.operations)
            assertEquals(0, f.generation)
        }
        val original = IphoneUsbException.DeviceUnavailable("USBMUX TCP connection was reset by the peer")
        val f = Fixture().apply {
            existingOnly = true
            store.candidate = DiagnosticPairCandidate(DEVICE, HOST, BUID,
                LockdownPairRecord.restore(HOST, BUID, "aa:bb:cc:dd:ee:ff",
                    SECRET, SECRET, SECRET, SECRET, SECRET, SECRET), DiagnosticPairState.PAIRED)
            transportErrorAt = "ValidatePair"; transportError = original
        }
        val failure = f.failure()
        assertSame(original, failure.cause)
        assertEquals("USBMUX_TCP_RESET", failure.reason)
        assertEquals(listOf("UniqueDeviceID", "ValidatePair"), f.operations)
        assertTrue(f.store.saves.isEmpty())
        assertEquals(DiagnosticPairState.PAIRED, f.store.candidate!!.state)
    }

    companion object {
        private const val DEVICE = "TESTDEVICE0001"
        private const val HOST = "00000000-0000-0000-0000-000000000001"
        private const val BUID = "00000000-0000-0000-0000-000000000002"
        private val SECRET = "SECRET fixture only".toByteArray()
    }
}
