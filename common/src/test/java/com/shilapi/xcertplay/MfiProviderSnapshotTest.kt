package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MfiProviderSnapshotTest {
    private class FakeSource : MfiProviderSnapshotSource {
        var selection = resolveMfiTargetSelection(null)
        var preferences = MfiProviderPreferenceMetadata(
            i2cPath = "/dev/i2c-1",
            i2cPathSource = "ACTIVITY_DEFAULT",
            remoteEndpointConfigured = false,
            remoteUsesHttps = false,
            remoteTokenConfigured = false,
        )
        var assets: Set<String>? = emptySet()
        var localFiles = mapOf<String, MfiCredentialFileMetadata>()
        var usb = emptyList<MfiUsbDescriptorMetadata>()
        var i2c = MfiNodeMetadata(false, null, false, false)
        val calls = mutableListOf<String>()

        override fun targetSelection() = selection.also { calls += "preferences:target" }
        override fun providerPreferences() = preferences.also { calls += "preferences:provider" }
        override fun packagedCredentialNames() = assets.also { calls += "assets:list" }
        override fun credentialFile(name: String) = localFiles[name] ?: MfiCredentialFileMetadata(false, false, false, null)
            .also { calls += "file:metadata:$name" }
        override fun usbDescriptors() = usb.also { calls += "usb:device-list-and-permission" }
        override fun i2cNode(path: String) = i2c.also { calls += "i2c:stat:$path" }
    }

    @Test fun targetSelectionHonorsSavedValidValueAndUsesActivityLocalFallbackForMissingOrInvalid() {
        val missing = resolveMfiTargetSelection(null)
        assertNull(missing.persistedTarget)
        assertEquals("NOT_SET", missing.persistedState)
        assertEquals(MfiTarget.LOCAL, missing.effectiveTarget)
        assertEquals("ACTIVITY_LOCAL_DEFAULT_NO_SAVED_TARGET", missing.fallbackSource)

        val invalid = resolveMfiTargetSelection("SECRET_UNKNOWN_TARGET")
        assertNull(invalid.persistedTarget)
        assertEquals("INVALID_REDACTED", invalid.persistedState)
        assertEquals(MfiTarget.LOCAL, invalid.effectiveTarget)
        assertEquals("ACTIVITY_LOCAL_DEFAULT_UNRECOGNIZED_TARGET", invalid.fallbackSource)
        assertFalse(invalid.toString().contains("SECRET_UNKNOWN_TARGET"))

        for (target in MfiTarget.entries) {
            val saved = resolveMfiTargetSelection(target.name)
            assertEquals(target, saved.persistedTarget)
            assertEquals("PERSISTED", saved.persistedState)
            assertEquals(target, saved.effectiveTarget)
            assertEquals("NONE", saved.fallbackSource)
        }
    }

    @Test fun persistedTargetResolverUsesActualActivityPreferenceFallback() {
        val context: Context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        try {
            preferences.edit().clear().commit()
            val missing = AirPlayPersistence.loadMfiTargetSelection(context)
            assertNull(missing.persistedTarget)
            assertEquals(MfiTarget.LOCAL, missing.effectiveTarget)
            assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(context))
            assertEquals("ACTIVITY_LOCAL_DEFAULT_NO_SAVED_TARGET", missing.fallbackSource)

            preferences.edit().putString("mfi_target", MfiTarget.USB_CH341.name).commit()
            val saved = AirPlayPersistence.loadMfiTargetSelection(context)
            assertEquals(MfiTarget.USB_CH341, saved.persistedTarget)
            assertEquals(MfiTarget.USB_CH341, saved.effectiveTarget)
            assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(context))
            assertEquals("NONE", saved.fallbackSource)
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test fun missingProviderMetadataIsNotConfiguredAndNeverReportsReady() {
        val source = FakeSource()
        val report = MfiProviderSnapshot(source).report()
        assertTrue(report.contains("Version=${MfiProviderSnapshot.VERSION}"))
        assertTrue(report.contains("Persisted MfiTarget=NOT_SET; selectionStatus=NOT_CONFIGURED; effectiveCarPlayHostTarget=LOCAL"))
        assertTrue(report.contains("LOCAL selected=true; configurationStatus=NOT_CONFIGURED; credentialResourceStatus=NOT_CONFIGURED"))
        assertTrue(report.contains("USB_CH341 selected=false; configurationStatus=CONFIGURED; configuredVidPid=1A86:5512; matchingDescriptorCount=0; existingPermission=false; pathStatus=NOT_CONFIGURED"))
        assertTrue(report.contains("I2C selected=false; configurationStatus=CONFIGURED; pathSource=ACTIVITY_DEFAULT; deviceNode=/dev/i2c-1; exists=false"))
        assertTrue(report.contains("pathStatus=NOT_CONFIGURED; authorization=AUTHORIZATION_UNVERIFIED"))
        assertTrue(report.contains("REMOTE selected=false; configurationStatus=NOT_CONFIGURED; endpointConfigured=false; tokenConfigured=false; httpsRequired=true; httpsSatisfied=false"))
        assertTrue(report.contains("MFI_READY=false; authorization=AUTHORIZATION_UNVERIFIED"))
        assertTrue(source.calls.none { it.startsWith("network:") || it.startsWith("usb:open") || it.startsWith("i2c:transaction") })
    }

    @Test fun reportsPresentButInaccessiblePathsWithoutCallingActiveProviderOperations() {
        val source = FakeSource().apply {
            selection = resolveMfiTargetSelection(MfiTarget.I2C.name)
            localFiles = mapOf(
                "identity.pk8" to MfiCredentialFileMetadata(true, true, false, 128),
                "certificate.p7b" to MfiCredentialFileMetadata(true, true, true, 256),
            )
            usb = listOf(MfiUsbDescriptorMetadata(UsbDeviceId(0x1a86, 0x5512), permissionGranted = false))
            i2c = MfiNodeMetadata(true, "character-device", readable = false, writable = false)
            preferences = preferences.copy(
                remoteEndpointConfigured = true,
                remoteUsesHttps = true,
                remoteTokenConfigured = true,
            )
        }
        val report = MfiProviderSnapshot(source).report()
        assertTrue(report.contains("LOCAL selected=false; configurationStatus=INACCESSIBLE; credentialResourceStatus=INACCESSIBLE"))
        assertTrue(report.contains("USB_CH341 selected=false; configurationStatus=CONFIGURED; configuredVidPid=1A86:5512; matchingDescriptorCount=1; existingPermission=false; pathStatus=INACCESSIBLE"))
        assertTrue(report.contains("I2C selected=true; configurationStatus=CONFIGURED; pathSource=ACTIVITY_DEFAULT; deviceNode=/dev/i2c-1; exists=true; type=character-device; readable=false; writable=false; pathStatus=INACCESSIBLE"))
        assertTrue(report.contains("REMOTE selected=false; configurationStatus=CONFIGURED; endpointConfigured=true; tokenConfigured=true; httpsRequired=true; httpsSatisfied=true"))
        assertTrue(report.contains("authorization=AUTHORIZATION_UNVERIFIED"))
        assertFalse(report.contains("MFI_READY=true"))
        assertTrue(source.calls.none { it.contains("open") || it.contains("transaction") || it.startsWith("network:") })
    }

    @Test fun androidMetadataSourceRedactsSecretFilesUrlsAndTokensAndDoesNotOpenUsb() {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("mfi_target", MfiTarget.REMOTE.name)
            .putString("mfi_i2c_path", "/dev/invalid-path")
            .putString("remote_mfi_server", "https://user:URL_SECRET@example.invalid/api?key=URL_TOKEN")
            .putString("remote_mfi_token", "REMOTE_TOKEN_SECRET")
            .commit()
        val credentialDirectory = File(context.noBackupFilesDir, "offline-mfi")
        assertTrue(credentialDirectory.mkdirs() || credentialDirectory.isDirectory)
        File(credentialDirectory, "identity.pk8").writeText("PRIVATE_KEY_SENTINEL")
        File(credentialDirectory, "certificate.p7b").writeText("CERTIFICATE_SENTINEL")

        val usbManager = mock(UsbManager::class.java)
        val ch341 = mock(UsbDevice::class.java)
        val unrelated = mock(UsbDevice::class.java)
        `when`(ch341.vendorId).thenReturn(0x1a86)
        `when`(ch341.productId).thenReturn(0x5512)
        `when`(unrelated.vendorId).thenReturn(0x1234)
        `when`(unrelated.productId).thenReturn(0x5678)
        `when`(usbManager.deviceList).thenReturn(hashMapOf("already-enumerated" to ch341, "unrelated" to unrelated))
        `when`(usbManager.hasPermission(ch341)).thenReturn(true)
        try {
            val report = MfiProviderSnapshot(AndroidMfiProviderSnapshotSource(context, usbManager)).report()
            assertTrue(report.contains("Persisted MfiTarget=REMOTE; selectionStatus=CONFIGURED; effectiveCarPlayHostTarget=REMOTE"))
            assertTrue(report.contains("USB_CH341 selected=false; configurationStatus=CONFIGURED; configuredVidPid=1A86:5512; matchingDescriptorCount=1; existingPermission=true; pathStatus=PATH_PRESENT"))
            assertTrue(report.contains("REMOTE selected=true; configurationStatus=CONFIGURED; endpointConfigured=true; tokenConfigured=true; httpsRequired=true; httpsSatisfied=true; futureSecureConfigurationAcceptable=true"))
            for (secret in listOf("URL_SECRET", "URL_TOKEN", "REMOTE_TOKEN_SECRET", "PRIVATE_KEY_SENTINEL", "CERTIFICATE_SENTINEL")) {
                assertFalse("snapshot leaked $secret", report.contains(secret))
            }
            verify(usbManager).deviceList
            verify(usbManager).hasPermission(ch341)
            verify(usbManager, never()).hasPermission(unrelated)
            verify(usbManager, never()).requestPermission(any(UsbDevice::class.java), any(android.app.PendingIntent::class.java))
            verify(usbManager, never()).openDevice(any(UsbDevice::class.java))
        } finally {
            credentialDirectory.deleteRecursively()
            prefs.edit().clear().commit()
        }
    }
}
