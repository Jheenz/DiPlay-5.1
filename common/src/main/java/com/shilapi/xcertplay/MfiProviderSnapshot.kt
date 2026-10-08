package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.io.File

internal data class MfiCredentialFileMetadata(
    val exists: Boolean?,
    val regularFile: Boolean?,
    val readable: Boolean?,
    val sizeBytes: Long?,
    val error: Boolean = false,
)

internal data class MfiUsbDescriptorMetadata(
    val identity: UsbDeviceId,
    val permissionGranted: Boolean,
)

internal data class MfiNodeMetadata(
    val exists: Boolean?,
    val type: String?,
    val readable: Boolean?,
    val writable: Boolean?,
    val error: Boolean = false,
)

internal interface MfiProviderSnapshotSource {
    fun targetSelection(): MfiTargetSelectionSnapshot
    fun providerPreferences(): MfiProviderPreferenceMetadata
    fun packagedCredentialNames(): Set<String>?
    fun credentialFile(name: String): MfiCredentialFileMetadata
    fun usbDescriptors(): List<MfiUsbDescriptorMetadata>
    fun i2cNode(path: String): MfiNodeMetadata
}

/** Metadata-only MFi provider snapshot. This class has no provider, USB-open, I2C-transaction, or network API. */
internal class MfiProviderSnapshot(private val source: MfiProviderSnapshotSource) {
    fun report(): String = buildString {
        val selection = source.targetSelection()
        val preferences = source.providerPreferences()
        val selected = selection.effectiveTarget
        appendLine("Phase 3D.2Z — Read-Only MFi Provider Snapshot")
        appendLine("Version=$VERSION")
        appendLine("Mode=METADATA_ONLY; providerInitialization=0; credentialContentsRead=0; " +
            "credentialSigning=0; usbPermissionRequests=0; usbOpens=0; I2CTransactions=0; networkRequests=0")
        appendLine("Persisted MfiTarget=${selection.persistedTarget?.name ?: selection.persistedState}; " +
            "selectionStatus=${if (selection.persistedTarget == null) NOT_CONFIGURED else CONFIGURED}; " +
            "effectiveCarPlayHostTarget=${selected.name}; fallbackSource=${selection.fallbackSource}")

        val assetNames = try {
            source.packagedCredentialNames()
        } catch (_: Exception) {
            null
        }
        val localKey = safeCredentialMetadata("identity.pk8")
        val localCertificate = safeCredentialMetadata("certificate.p7b")
        val assetsComplete = assetNames?.containsAll(LOCAL_CREDENTIALS) == true
        val privateFilesComplete = listOf(localKey, localCertificate).all {
            it.exists == true && it.regularFile == true && it.readable == true
        }
        val localInaccessible = assetNames == null || listOf(localKey, localCertificate).any {
            it.error || (it.exists == true && (it.regularFile != true || it.readable != true))
        }
        val localPath = when {
            assetsComplete || privateFilesComplete -> PATH_PRESENT
            localInaccessible -> INACCESSIBLE
            else -> NOT_CONFIGURED
        }
        appendLine("LOCAL selected=${selected == MfiTarget.LOCAL}; configurationStatus=${statusFor(localPath)}; " +
            "credentialResourceStatus=$localPath; packagedPairPresent=$assetsComplete; " +
            "privatePairPresent=$privateFilesComplete; authorization=$AUTHORIZATION_UNVERIFIED")

        val matchingUsb = try {
            source.usbDescriptors().filter { it.identity == CH341_IDENTITY }
        } catch (_: Exception) {
            null
        }
        val usbStatus = when {
            matchingUsb == null -> INACCESSIBLE
            matchingUsb.isEmpty() -> NOT_CONFIGURED
            matchingUsb.any { it.permissionGranted } -> PATH_PRESENT
            else -> INACCESSIBLE
        }
        appendLine("USB_CH341 selected=${selected == MfiTarget.USB_CH341}; configurationStatus=$CONFIGURED; " +
            "configuredVidPid=1A86:5512; matchingDescriptorCount=${matchingUsb?.size ?: "UNKNOWN"}; " +
            "existingPermission=${matchingUsb?.any { it.permissionGranted } ?: "UNKNOWN"}; pathStatus=$usbStatus; " +
            "authorization=$AUTHORIZATION_UNVERIFIED")

        val i2cPathIsValid = I2C_PATH.matches(preferences.i2cPath)
        val i2cNode = if (i2cPathIsValid) {
            try { source.i2cNode(preferences.i2cPath) } catch (_: Exception) { null }
        } else null
        val i2cStatus = when {
            !i2cPathIsValid -> NOT_CONFIGURED
            i2cNode == null || i2cNode.error -> INACCESSIBLE
            i2cNode.exists == false -> NOT_CONFIGURED
            i2cNode.exists != true -> INACCESSIBLE
            i2cNode.type != "character-device" -> NOT_CONFIGURED
            i2cNode.readable == true && i2cNode.writable == true -> PATH_PRESENT
            else -> INACCESSIBLE
        }
        appendLine("I2C selected=${selected == MfiTarget.I2C}; configurationStatus=" +
            "${if (i2cPathIsValid) CONFIGURED else NOT_CONFIGURED}; pathSource=${preferences.i2cPathSource}; " +
            "deviceNode=${if (i2cPathIsValid) preferences.i2cPath else "INVALID_REDACTED"}; " +
            "exists=${i2cNode?.exists ?: "UNKNOWN"}; type=${i2cNode?.type ?: "UNKNOWN"}; " +
            "readable=${i2cNode?.readable ?: "UNKNOWN"}; writable=${i2cNode?.writable ?: "UNKNOWN"}; " +
            "pathStatus=$i2cStatus; authorization=$AUTHORIZATION_UNVERIFIED")

        val remoteConfigurationStatus = if (preferences.remoteEndpointConfigured) CONFIGURED else NOT_CONFIGURED
        val remoteSecureConfigurationAcceptable = preferences.remoteEndpointConfigured &&
            preferences.remoteUsesHttps && preferences.remoteTokenConfigured
        appendLine("REMOTE selected=${selected == MfiTarget.REMOTE}; configurationStatus=$remoteConfigurationStatus; " +
            "endpointConfigured=${preferences.remoteEndpointConfigured}; tokenConfigured=${preferences.remoteTokenConfigured}; " +
            "httpsRequired=true; httpsSatisfied=${preferences.remoteUsesHttps}; " +
            "futureSecureConfigurationAcceptable=$remoteSecureConfigurationAcceptable; serverValue=REDACTED; tokenValue=REDACTED; " +
            "networkContacted=false; reachability=NOT_CHECKED; authorization=$AUTHORIZATION_UNVERIFIED")
        appendLine("MFI_READY=false; authorization=AUTHORIZATION_UNVERIFIED; " +
            "pathPresenceDoesNotProveMfiHardwareOrAppleAuthorization")
    }

    private fun safeCredentialMetadata(name: String): MfiCredentialFileMetadata = try {
        source.credentialFile(name)
    } catch (_: Exception) {
        MfiCredentialFileMetadata(null, null, null, null, error = true)
    }

    private fun statusFor(pathStatus: String): String = when (pathStatus) {
        PATH_PRESENT -> CONFIGURED
        else -> pathStatus
    }

    companion object {
        const val TITLE = "Phase 3D.2Z — Read-Only MFi Provider Snapshot"
        const val VERSION = "0.2.12-api22-phase3d2z-mfi-provider-snapshot"
        const val SAFETY = "METADATA ONLY — NO PROVIDER INITIALIZATION / CREDENTIAL READ / I2C / USB OPEN / NETWORK / IPHONE"
        const val NOT_RUN = "$TITLE: not run\n$SAFETY"
        internal const val CONFIGURED = "CONFIGURED"
        internal const val PATH_PRESENT = "PATH_PRESENT"
        internal const val INACCESSIBLE = "INACCESSIBLE"
        internal const val NOT_CONFIGURED = "NOT_CONFIGURED"
        internal const val AUTHORIZATION_UNVERIFIED = "AUTHORIZATION_UNVERIFIED"
        private val LOCAL_CREDENTIALS = setOf("identity.pk8", "certificate.p7b")
        private val I2C_PATH = Regex("/dev/i2c-[0-9]+")
        private val CH341_IDENTITY = UsbDeviceId(0x1a86, 0x5512)
    }
}

internal class AndroidMfiProviderSnapshotSource(
    private val context: Context,
    private val usbManager: UsbManager?,
) : MfiProviderSnapshotSource {
    override fun targetSelection() = AirPlayPersistence.loadMfiTargetSelection(context)

    override fun providerPreferences() = AirPlayPersistence.loadMfiProviderPreferenceMetadata(context)

    override fun packagedCredentialNames(): Set<String>? = context.assets.list(LocalMfiAuthenticationClient.DIRECTORY)?.toSet()

    override fun credentialFile(name: String): MfiCredentialFileMetadata {
        require(name in setOf("identity.pk8", "certificate.p7b"))
        val file = File(context.noBackupFilesDir, "${LocalMfiAuthenticationClient.DIRECTORY}/$name")
        val stat = AndroidPassiveI2cInventoryFiles.stat(file.absolutePath)
        return MfiCredentialFileMetadata(
            exists = stat.exists,
            regularFile = stat.type == "regular-file",
            readable = stat.canRead,
            sizeBytes = stat.size,
            error = stat.error != null,
        )
    }

    override fun usbDescriptors(): List<MfiUsbDescriptorMetadata> = checkNotNull(usbManager) {
        "Android USB host service unavailable"
    }.deviceList.values.filter { device ->
        device.vendorId == 0x1a86 && device.productId == 0x5512
    }.map { device ->
        MfiUsbDescriptorMetadata(
            identity = UsbDeviceId(device.vendorId, device.productId),
            permissionGranted = usbManager.hasPermission(device),
        )
    }

    override fun i2cNode(path: String): MfiNodeMetadata {
        val stat = AndroidPassiveI2cInventoryFiles.stat(path)
        return MfiNodeMetadata(stat.exists, stat.type, stat.canRead, stat.canWrite, stat.error != null)
    }
}
