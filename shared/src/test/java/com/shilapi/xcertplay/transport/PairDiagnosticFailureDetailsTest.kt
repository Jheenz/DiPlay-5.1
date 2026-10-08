package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class PairDiagnosticFailureDetailsTest {
    @Test fun extractsArtSignatureWithoutDexSuffixOrArbitraryMessage() {
        val error = NoSuchMethodError("No virtual method verify(Ljava/security/PublicKey;Ljava/security/Provider;)V " +
            "in class Ljava/security/cert/X509Certificate; or its super classes (SECRET pairing record)")
        error.stackTrace = arrayOf(
            StackTraceElement("java.lang.Class", "resolve", "Class.java", 1),
            StackTraceElement("com.shilapi.xcertplay.transport.DiagnosticPairMaterial", "verify", "DiagnosticPairMaterial.kt", 25))
        val report = PairDiagnosticFailureDetails.format("VERIFY_MATERIAL", error)
        assertTrue(report.contains("java.lang.NoSuchMethodError"))
        assertTrue(report.contains("L") && report.contains("->verify(Ljava/security/PublicKey;Ljava/security/Provider;)V"))
        assertTrue(report.contains("stage=VERIFY_MATERIAL"))
        assertTrue(report.contains("DiagnosticPairMaterial.verify line=25"))
        assertFalse(report.contains("SECRET"))
        assertFalse(report.contains("java.lang.Class.resolve"))
    }

    @Test fun classifiedTransportCauseRetainsSafeMessageAndApplicationStackOnly() {
        val original = IphoneUsbException.DeviceUnavailable("USBMUX TCP connection was reset by the peer")
        original.stackTrace = arrayOf(StackTraceElement(
            "com.shilapi.xcertplay.transport.Iap2UsbMuxTcpConnection", "onPacket", "Iap2UsbMuxHost.kt", 545))
        val report = PairDiagnosticFailureDetails.format("VALIDATE_PAIR",
            ControlledPairFailure("VALIDATE_PAIR", "USBMUX_TCP_RESET", cause = original))
        assertTrue(report.contains("Original cause class=" + original.javaClass.name))
        assertTrue(report.contains("safeMessage=USBMUX TCP connection was reset by the peer"))
        assertTrue(report.contains("Iap2UsbMuxTcpConnection.onPacket line=545"))
        val secret = IphoneUsbException.DeviceUnavailable("SECRET HostID and certificates",
            IllegalStateException("SECRET provider material"))
        assertFalse(PairDiagnosticFailureDetails.format("TEST", secret).contains("SECRET"))
        assertEquals("LOCKDOWN_EOF", PairDiagnosticFailureDetails.transportReason(
            IphoneUsbException.Protocol("USBMUX TCP stream ended inside a Lockdown plist frame")))
        assertEquals("USB_SHORT_WRITE", PairDiagnosticFailureDetails.transportReason(
            IphoneUsbException.DeviceUnavailable("TCP_OUT short/failure=3 expected=80; no retry")))
        assertEquals("USB_WRITE_FAILURE", PairDiagnosticFailureDetails.transportReason(
            IphoneUsbException.DeviceUnavailable("TCP_OUT short/failure=-1 expected=80; no retry")))
    }

    @Test fun recognizesJvmSignatureAndRedactsUnrecognizedOrNonLinkageText() {
        val method = "void java.security.cert.X509Certificate.verify(java.security.PublicKey, java.security.Provider)"
        assertTrue(PairDiagnosticFailureDetails.format("TEST", NoSuchMethodError("'$method'")).contains(method))
        for (error in listOf(NoSuchMethodError("SECRET HostID certificate"), IllegalStateException("SECRET EscrowBag"))) {
            val report = PairDiagnosticFailureDetails.format("TEST", error)
            assertTrue(report.contains("Exception message=<redacted"))
            assertTrue(report.contains("Missing method signature=<unavailable>"))
            assertFalse(report.contains("SECRET"))
        }
    }
}
