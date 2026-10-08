package com.shilapi.xcertplay

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityOptionsCompat
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", shadows = [FileProviderPathTestShadow::class])
class DiagnosticExportUiTest {
    @Test fun selectedDocumentDestinationIncludesSavedReadOnlyLockdownReport() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val report = "${ReadOnlyLockdownDiagnostic.TITLE}\nOutcome=STOP\nProductType=not queried"
        val pairReport = "${ControlledPairDiagnostic.TITLE}\nOutcome=STOP\nFailure stage=PREFLIGHT"
        val existingReport = "${ExistingPairValidationDiagnostic.TITLE}\nOutcome=STOP\nPair count = 0"
        val localReport = "${LocalPairMetadataDiagnostic.TITLE}\nRecord entries=0"
        val sessionReport = "${ModernStartSessionDiagnostic.TITLE}\nSTARTSESSION CONFIRMED — TLS REQUIRED"
        ReflectionHelpers.setField(activity, "readOnlyLockdownReport", report)
        ReflectionHelpers.setField(activity, "controlledPairReport", pairReport)
        ReflectionHelpers.setField(activity, "existingPairReport", existingReport)
        ReflectionHelpers.setField(activity, "localPairMetadataReport", localReport)
        ReflectionHelpers.setField(activity, "modernStartSessionReport", sessionReport)
        val file = File(activity.filesDir, "readonly-lockdown-export-test.txt")
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "exportDiagnostics",
                ReflectionHelpers.ClassParameter.from(android.net.Uri::class.java, android.net.Uri.fromFile(file)))
            val deadline = System.nanoTime() + 5_000_000_000L
            while ((!file.exists() || !file.readText().contains(report) || !file.readText().contains(pairReport)) &&
                System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertTrue("Selected document must retain STOP report", file.readText().contains(report))
            assertTrue("Selected document must retain controlled-pair STOP report", file.readText().contains(pairReport))
            assertTrue("Selected document must retain no-Pair report", file.readText().contains(existingReport))
            assertTrue("Selected document must retain local metadata", file.readText().contains(localReport))
            assertTrue("Selected document must retain StartSession report", file.readText().contains(sessionReport))
        } finally {
            controller.pause().stop().destroy()
            file.delete()
        }
    }

    @Test fun missingPickerSavesAReportAndProvidesSelectableTextInsideDiPlay() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val context = activity.applicationContext
        val authority = "${context.packageName}.diagnostic-reports"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        ShadowContentResolver.registerProviderInternal(authority, DiagnosticReportProvider().apply { attachInfo(context, info) })
        val missingPicker = object : ActivityResultLauncher<String>() {
            override fun launch(input: String, options: ActivityOptionsCompat?) {
                throw ActivityNotFoundException("No DocumentsUI")
            }
            override fun unregister() = Unit
            override fun getContract() = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
        }
        ReflectionHelpers.setField(activity, "export", missingPicker)
        val usbReport = PassiveUsbDeviceDiagnostic(PassiveUsbInventory { emptyList() }).scan()
        ReflectionHelpers.setField(activity, "passiveUsbReport", usbReport)
        val directReport = com.shilapi.xcertplay.transport.DirectUsbMuxDiagnostic.NOT_RUN
        ReflectionHelpers.setField(activity, "directUsbMuxReport", directReport)
        val mappingReport = PassiveUsbDeviceDiagnostic(PassiveUsbInventory { emptyList() }, true).scan()
        ReflectionHelpers.setField(activity, "usbConfigurationReport", mappingReport)
        val activeConfigurationReport = ActiveUsbConfigurationDiagnostic.NOT_RUN
        ReflectionHelpers.setField(activity, "activeUsbConfigurationReport", activeConfigurationReport)
        val branchReport = QDriveBranchDiagnostic.NOT_RUN
        ReflectionHelpers.setField(activity, "qdriveDescriptorReport", branchReport)
        val transitionReport = "${QDriveVendorTransitionDiagnostic.TITLE}\nQDRIVE VENDOR TRANSITION INCONCLUSIVE\nVendor request attempted=true"
        ReflectionHelpers.setField(activity, "qdriveTransitionReport", transitionReport)
        val configurationReport = "${QDriveConfigurationDiagnostic.TITLE}\nQDRIVE CONFIGURATION 5 SELECTION CONFIRMED"
        ReflectionHelpers.setField(activity, "qdriveConfigurationReport", configurationReport)
        val claimReport = "${ActiveConfig5UsbMuxClaimDiagnostic.TITLE}\n${ActiveConfig5UsbMuxClaimDiagnostic.PASS}\n${ActiveConfig5UsbMuxClaimDiagnostic.PROTOCOL_NOT_TESTED}"
        ReflectionHelpers.setField(activity, "activeConfig5ClaimReport", claimReport)
        val versionReport = "${UsbMuxVersionDiagnostic.TITLE}\n${UsbMuxVersionDiagnostic.PASS}\nSETUP07 / LOCKDOWN / PAIRING NOT STARTED"
        ReflectionHelpers.setField(activity, "usbMuxVersionReport", versionReport)
        val lockdownReport = "${ReadOnlyLockdownDiagnostic.TITLE}\n${ReadOnlyLockdownDiagnostic.PASS}\nProductType=iPhone11,2"
        ReflectionHelpers.setField(activity, "readOnlyLockdownReport", lockdownReport)
        val pairReport = "${ControlledPairDiagnostic.TITLE}\n${ControlledPairDiagnostic.PASS}\nMode=validation-only"
        val existingReport = "${ExistingPairValidationDiagnostic.TITLE}\nPair count = 0\nOutcome=STOP"
        val localReport = "${LocalPairMetadataDiagnostic.TITLE}\nRecord entries=0"
        ReflectionHelpers.setField(activity, "controlledPairReport", pairReport)
        ReflectionHelpers.setField(activity, "existingPairReport", existingReport)
        ReflectionHelpers.setField(activity, "localPairMetadataReport", localReport)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "chooseReportDestination")
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ShadowAlertDialog.getLatestAlertDialog() == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val saved = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val reports = File(context.getExternalFilesDir(null)!!, "diagnostic-reports")
            val file = reports.listFiles()!!.single()
            assertTrue(file.name.endsWith(".txt"))
            assertTrue(descendants(saved.window!!.decorView).filterIsInstance<TextView>()
                .any { it.text.contains(file.absolutePath) })
            assertTrue(file.readText().contains("Android 9 / API 28"))
            assertTrue(file.readText().contains(usbReport))
            assertTrue(file.readText().contains(directReport))
            assertTrue(file.readText().contains(mappingReport))
            assertTrue(file.readText().contains(activeConfigurationReport))
            assertTrue(file.readText().contains(branchReport))
            assertTrue(file.readText().contains(transitionReport))
            assertTrue(file.readText().contains(configurationReport))
            assertTrue(file.readText().contains(claimReport))
            assertTrue(file.readText().contains(versionReport))
            assertTrue(file.readText().contains(existingReport))
            assertTrue(file.readText().contains(localReport))
            assertTrue(file.readText().contains(lockdownReport))
            assertTrue(file.readText().contains(pairReport))
            saved.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val viewer = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(descendants(viewer.window!!.decorView).filterIsInstance<TextView>()
                .any { it.isTextSelectable && it.text.contains("Android 9 / API 28") })
            viewer.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
