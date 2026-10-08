package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.transport.DiagnosticPairCandidate
import com.shilapi.xcertplay.transport.DiagnosticPairState
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.security.KeyPairGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidDiagnosticPairStoreTest {
    private val app = RuntimeEnvironment.getApplication()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val access = object : DiagnosticPairStorageKeys {
        override fun publicKey() = keys.public
        override fun privateKey() = keys.private
    }
    private fun candidate(state: DiagnosticPairState = DiagnosticPairState.PREPARED, device: String = "TESTDEVICE0001"): DiagnosticPairCandidate {
        val secret = "SECRET fixture only".toByteArray()
        val record = LockdownPairRecord.restore(HOST, BUID, "aa:bb:cc:dd:ee:ff",
            secret, secret, secret, secret, secret, secret)
        return DiagnosticPairCandidate(device, HOST, BUID, record, state, secret)
    }
    @Test fun encryptedRoundtripRestartReadbackAndPerDeviceAssociation() {
        val store = AndroidDiagnosticPairStore(app, access)
        val initial = candidate()
        store.save(initial)
        val restored = AndroidDiagnosticPairStore(app, access).load(initial.deviceId)!!
        assertEquals(initial.hostId, restored.hostId)
        assertArrayEquals(initial.record!!.rootPrivateKeyPem, restored.record!!.rootPrivateKeyPem)
        assertArrayEquals(initial.escrowBag, restored.escrowBag)
        assertNull(store.load("TESTDEVICE0002"))
        val prefs = app.getSharedPreferences("diplay_controlled_pair", Context.MODE_PRIVATE)
        assertFalse(prefs.all.toString().contains("SECRET"))
        assertFalse(prefs.all.toString().contains(initial.deviceId))
        assertFalse(prefs.all.toString().contains(HOST))
        assertEquals(store.systemBuid(), AndroidDiagnosticPairStore(app, access).systemBuid())
    }
    @Test fun confirmedRecordNotReplacedOrDowngraded() {
        val store = AndroidDiagnosticPairStore(app, access)
        store.save(candidate(DiagnosticPairState.VALIDATED))
        val prefs = app.getSharedPreferences("diplay_controlled_pair", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        store.save(candidate(DiagnosticPairState.VALIDATED))
        assertEquals(before, prefs.all)
        try { store.save(candidate()); fail("Downgrade") } catch (_: IllegalStateException) {}
        val old = candidate(DiagnosticPairState.VALIDATED)
        try {
            store.save(DiagnosticPairCandidate(old.deviceId, BUID, old.systemBuid, old.record, old.state))
            fail("Identity replacement")
        } catch (_: IllegalStateException) {}
        assertEquals(DiagnosticPairState.VALIDATED, store.load(old.deviceId)!!.state)
    }
    @Test fun corruptRecordOrLostKeyStopsNoRegeneration() {
        val store = AndroidDiagnosticPairStore(app, access)
        store.save(candidate())
        val prefs = app.getSharedPreferences("diplay_controlled_pair", Context.MODE_PRIVATE)
        val name = prefs.all.keys.single { it.startsWith("device_") }
        prefs.edit().putString(name, "AAAA").commit()
        try { store.load("TESTDEVICE0001"); fail("Corruption") } catch (_: Exception) {}
        assertEquals("AAAA", prefs.getString(name, null))
        store.save(candidate(device = "TESTDEVICE0002"))
        val wrong = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val other = object : DiagnosticPairStorageKeys {
            override fun publicKey() = wrong.public
            override fun privateKey() = wrong.private
        }
        try { AndroidDiagnosticPairStore(app, other).load("TESTDEVICE0002"); fail("Lost wrapping key") }
        catch (_: Exception) {}
    }
    @Test fun existingLegacyRecordBlocksNewIdentityWithoutAccessingMaterial() {
        val prefs = app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().putString("lockdown_host_id", HOST).commit()
        val store = AndroidDiagnosticPairStore(app, access)
        try { store.load("TESTDEVICE0001"); fail("Legacy record") } catch (_: IllegalStateException) {}
        assertEquals(HOST, prefs.getString("lockdown_host_id", null))
        assertTrue(app.getSharedPreferences("diplay_controlled_pair", Context.MODE_PRIVATE).all.isEmpty())
    }
    @Test fun codecRejectsMissingMaterialStateAndOversizeWithoutPrintingSecrets() {
        val identity = DiagnosticPairCandidate("TESTDEVICE0001", HOST, BUID)
        val bytes = DiagnosticPairCodec.encode(identity)
        assertNull(DiagnosticPairCodec.decode(bytes).record)
        try {
            DiagnosticPairCodec.encode(DiagnosticPairCandidate(identity.deviceId, HOST, BUID, state = DiagnosticPairState.VALIDATED))
            fail("Missing material")
        } catch (_: IllegalStateException) {}
        try { DiagnosticPairCodec.decode(bytes + byteArrayOf(1)); fail("Trailing data") } catch (_: IllegalStateException) {}
        try { DiagnosticPairCodec.decode(ByteArray(128 * 1024 + 1)); fail("Oversize") } catch (_: IllegalStateException) {}
    }
    @Test fun preflightRequiresPersistedAcceptedVerifiedMaterialAndSurvivesStoreRestart() {
        val store = AndroidDiagnosticPairStore(app, access)
        assertTrue(store.acceptedRecords().isEmpty())
        val record = AndroidControlledPairAccessTest.record()
        val prepared = DiagnosticPairCandidate("TESTDEVICE0001", record.hostId, record.systemBuid, record)
        store.save(prepared)
        try { store.acceptedRecords(); fail("PREPARED must block") }
        catch (error: com.shilapi.xcertplay.transport.ControlledPairFailure) {
            assertEquals("PAIR_RECORD_PREPARED", error.reason)
        }
        store.save(DiagnosticPairCandidate(prepared.deviceId, prepared.hostId, prepared.systemBuid,
            record, DiagnosticPairState.PAIRED))
        val accepted = AndroidDiagnosticPairStore(app, access).acceptedRecords().single()
        assertEquals(DiagnosticPairState.PAIRED, accepted.state)
        assertEquals(prepared.deviceId, accepted.deviceId)
        assertArrayEquals(record.hostPrivateKeyPem, accepted.record!!.hostPrivateKeyPem)
    }

    companion object {
        private const val HOST = "00000000-0000-0000-0000-000000000001"
        private const val BUID = "00000000-0000-0000-0000-000000000002"
    }
}
