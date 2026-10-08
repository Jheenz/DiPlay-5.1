package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.orchestration.LegacyLaunchBuild
import com.shilapi.xcertplay.transport.DiagnosticPairCandidate
import com.shilapi.xcertplay.transport.DiagnosticPairState
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "en")
class LocalPairMetadataDiagnosticTest {
    private val app = RuntimeEnvironment.getApplication()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val storageKeys = object : DiagnosticPairStorageKeys {
        override fun publicKey() = keys.public
        override fun privateKey() = keys.private
    }
    private fun store() = AndroidDiagnosticPairStore(app, storageKeys)
    private fun candidate(state: DiagnosticPairState, device: String = DEVICE): DiagnosticPairCandidate {
        val record = AndroidControlledPairAccessTest.record()
        return DiagnosticPairCandidate(device, record.hostId, record.systemBuid, record, state)
    }

    @Test fun acceptedStatesDiscoverableAfterStoreReopenWithoutLookupOrWrites() {
        val store = store()
        for (state in listOf(DiagnosticPairState.PAIRED, DiagnosticPairState.VALIDATED)) {
            val candidate = candidate(state, if (state == DiagnosticPairState.PAIRED) DEVICE else OTHER)
            store.save(candidate)
        }
        val prefs = app.getSharedPreferences("diplay_controlled_pair", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        val reopened = store()
        val metadata = reopened.inspect()
        assertEquals(setOf(DiagnosticPairState.PAIRED, DiagnosticPairState.VALIDATED), metadata.records.map { it.state }.toSet())
        assertTrue(metadata.records.all { it.status == PairMetadataStatus.READABLE && it.associationMatches == true })
        assertEquals(2, reopened.acceptedRecords().size)
        val report = LocalPairMetadataDiagnostic(reopened).run()
        assertTrue(report.contains("Accepted readable record exists=true"))
        assertFalse(report.contains(DEVICE))
        assertFalse(report.contains(OTHER))
        assertFalse(report.contains("CERTIFICATE"))
        assertFalse(report.contains("PRIVATE KEY"))
        assertEquals(before, prefs.all)
    }

    @Test fun missingAssociationIsNotMissingEntryAndCorruptionDoesNotHideReadableRecord() {
        val store = store()
        store.save(candidate(DiagnosticPairState.PAIRED))
        val malformed = ByteArrayOutputStream().apply {
            DataOutputStream(this).use {
                it.writeInt(1); it.writeUTF(""); it.writeUTF("not-exported"); it.writeUTF("not-exported")
                it.writeInt(DiagnosticPairState.PAIRED.ordinal)
            }
        }.toByteArray()
        val encrypted = ReflectionHelpers.callInstanceMethod<String>(store, "encrypt",
            ReflectionHelpers.ClassParameter.from(ByteArray::class.java, malformed),
            ReflectionHelpers.ClassParameter.from(String::class.java, "device_fixture"))
        val prefs = app.getSharedPreferences("diplay_controlled_pair", Context.MODE_PRIVATE)
        prefs.edit().putString("device_fixture", encrypted).putInt("device_wrong_type", 1)
            .putString("device_corrupt", "AAAA").putBoolean("unknown", true).commit()
        val before = prefs.all.toMap()
        val metadata = store().inspect()
        assertEquals(4, metadata.records.size)
        assertEquals(1, metadata.unknownEntryCount)
        assertTrue(metadata.records.any { it.status == PairMetadataStatus.ASSOCIATION_INVALID && it.associationPresent == false })
        assertTrue(metadata.records.any { it.status == PairMetadataStatus.READABLE })
        assertTrue(metadata.records.any { it.status == PairMetadataStatus.WRONG_VALUE_TYPE })
        assertTrue(metadata.records.any { it.status == PairMetadataStatus.ENVELOPE_OR_DECRYPT_FAILED })
        val report = LocalPairMetadataDiagnostic(store()).run()
        assertTrue(report.contains("Inspection incomplete=true"))
        assertFalse(report.contains("not-exported"))
        assertFalse(report.contains("device_fixture"))
        assertEquals(before, prefs.all)
    }

    @Test fun preparedAndMismatchedAssociationRemainExplicitPhysicalEntries() {
        val store = store()
        val prepared = candidate(DiagnosticPairState.PREPARED)
        store.save(prepared)
        val entry = "device_wrong_association"
        val plain = DiagnosticPairCodec.encode(prepared)
        val encrypted = ReflectionHelpers.callInstanceMethod<String>(store, "encrypt",
            ReflectionHelpers.ClassParameter.from(ByteArray::class.java, plain),
            ReflectionHelpers.ClassParameter.from(String::class.java, entry))
        app.getSharedPreferences("diplay_controlled_pair", Context.MODE_PRIVATE).edit().putString(entry, encrypted).commit()
        val metadata = store().inspect()
        assertEquals(2, metadata.records.size)
        assertTrue(metadata.records.any { it.state == DiagnosticPairState.PREPARED && it.status == PairMetadataStatus.READABLE })
        assertTrue(metadata.records.any { it.associationPresent == true && it.associationMatches == false &&
            it.status == PairMetadataStatus.ASSOCIATION_MISMATCH })
        assertTrue(LocalPairMetadataDiagnostic(store()).run().contains("Accepted readable record exists=false"))
    }

    @Test fun wrongWrappingKeyRetainsEntryAndReportsFailureNotEmptyInventory() {
        store().save(candidate(DiagnosticPairState.PAIRED))
        val prefs = app.getSharedPreferences("diplay_controlled_pair", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        val wrongKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val wrong = AndroidDiagnosticPairStore(app, object : DiagnosticPairStorageKeys {
            override fun publicKey() = wrongKey.public
            override fun privateKey() = wrongKey.private
        })
        val metadata = wrong.inspect()
        assertEquals(1, metadata.records.size)
        assertEquals(PairMetadataStatus.ENVELOPE_OR_DECRYPT_FAILED, metadata.records.single().status)
        assertNull(metadata.records.single().state)
        assertEquals(before, prefs.all)
    }

    @Test fun emptyInventoryDoesNotFetchKeysOrGenerateIdentityAndReportsOrphans() {
        val unused = AndroidDiagnosticPairStore(app, object : DiagnosticPairStorageKeys {
            override fun publicKey(): java.security.PublicKey = error("Key generation/access forbidden")
            override fun privateKey(): java.security.Key = error("Key generation/access forbidden")
        })
        val prefs = app.getSharedPreferences("diplay_controlled_pair", Context.MODE_PRIVATE)
        prefs.edit().putString("system_buid", "not-exported").commit()
        val before = prefs.all.toMap()
        val report = LocalPairMetadataDiagnostic(unused).run()
        assertTrue(report.contains("Record entries=0"))
        assertTrue(report.contains("Orphaned alias=true"))
        assertTrue(report.contains("Orphaned identity metadata=true"))
        assertFalse(report.contains("not-exported"))
        assertEquals(before, prefs.all)
    }

    @Test fun pairSuccessSavedBeforeResetAndRetainedAfterRealDiagnosticCleanup() {
        val store = store()
        val prepared = candidate(DiagnosticPairState.PREPARED)
        store.save(prepared)
        val fixture = AndroidReadOnlyLockdownAccessTest.Fixture().apply {
            validateControl = 4
            controlledReply = { xml ->
                val operation = Regex("<key>Request</key>\\s*<string>([^<]+)</string>").find(xml)!!.groupValues[1]
                val fields = when (operation) {
                    "QueryType" -> "<key>Type</key><string>com.apple.mobile.lockdown</string>"
                    "GetValue" -> "<key>Value</key><string>$DEVICE</string>"
                    else -> ""
                }
                "<plist version=\"1.0\"><dict><key>Request</key><string>$operation</string>$fields</dict></plist>"
            }
        }
        val guarded = object : DiagnosticPairStore by store {
            override fun systemBuid(): String = error("Identity regeneration forbidden")
        }
        val report = ControlledPairDiagnostic(fixture.access, guarded).run()
        assertTrue(report, report.contains("Pair success record stored=verified"))
        assertTrue(report, report.contains("USBMUX_TCP_RESET"))
        fixture.verifyCleanup()
        assertEquals(1, fixture.requests.count { it.contains("<string>Pair</string>") })
        assertEquals(1, fixture.requests.count { it.contains("<string>ValidatePair</string>") })
        val restored = store().load(DEVICE)!!
        assertEquals(DiagnosticPairState.PAIRED, restored.state)
        assertEquals(prepared.hostId, restored.hostId)
        assertArrayEquals(prepared.record!!.hostPrivateKeyPem, restored.record!!.hostPrivateKeyPem)
        assertEquals(DiagnosticPairState.PAIRED, store().acceptedRecords().single().state)
        assertTrue(LocalPairMetadataDiagnostic(store()).run().contains("Accepted readable record exists=true"))
    }

    @Test fun metadataFailureReportsUnknownCountsAndDoesNotExposeMessage() {
        val report = LocalPairMetadataDiagnostic(LocalPairMetadataAccess { error("SECRET pairing bytes") }).run()
        assertTrue(report.contains("counts=UNKNOWN"))
        assertFalse(report.contains("SECRET"))
        assertTrue(report.contains("Outcome=STOP"))
    }

    @Test fun uiActionIsManualLocalOnlyAndDoesNotUseUsbOrGenerateIdentity() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val forbidden = mock(ReadOnlyLockdownAccess::class.java)
        ReflectionHelpers.setField(activity, "controlledPairAccess", forbidden)
        var scans = 0
        ReflectionHelpers.setField(activity, "localPairMetadataAccess", LocalPairMetadataAccess {
            scans++
            LocalPairMetadata(emptyList(), 0, false, false, false, false, false)
        })
        try {
            assertEquals(0, scans)
            assertFalse(LegacyLaunchBuild.CONNECTIONS_ENABLED)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "inspectLocalPairMetadata")
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ReflectionHelpers.getField<Boolean>(activity, "localPairMetadataRunning") && System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertFalse(ReflectionHelpers.getField(activity, "localPairMetadataRunning"))
            assertEquals(1, scans)
            verifyNoInteractions(forbidden)
            val report = ReflectionHelpers.getField<String>(activity, "localPairMetadataReport")
            assertTrue(report.contains("USB=0; USBMUX=0; Lockdown=0; Pair=0; ValidatePair=0; network=0; identity generation=0; writes=0"))
            assertEquals(ControlledPairDiagnostic.NOT_RUN, ReflectionHelpers.getField<String>(activity, "controlledPairReport"))
            assertEquals(ExistingPairValidationDiagnostic.NOT_RUN, ReflectionHelpers.getField<String>(activity, "existingPairReport"))
        } finally { controller.pause().stop().destroy() }
    }

    companion object {
        private const val DEVICE = "TESTDEVICE0001"
        private const val OTHER = "TESTDEVICE0002"
    }
}
