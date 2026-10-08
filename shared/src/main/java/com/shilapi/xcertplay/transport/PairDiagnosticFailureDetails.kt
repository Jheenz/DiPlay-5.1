package com.shilapi.xcertplay.transport

/** Never exports arbitrary exception text from storage, plist or crypto providers. */
object PairDiagnosticFailureDetails {
    fun transportReason(error: IphoneUsbException): String = when (error) {
        is IphoneUsbException.TimedOut -> "LOCKDOWN_TIMEOUT"
        is IphoneUsbException.PermissionDenied -> "PERMISSION_UNAVAILABLE"
        else -> when (error.message) {
            "USBMUX TCP connection was reset by the peer" -> "USBMUX_TCP_RESET"
            "USBMUX TCP connection is not open" -> "USBMUX_TCP_CLOSED"
            "USBMUX TCP stream ended inside a Lockdown plist frame" -> "LOCKDOWN_EOF"
            "USBMUX reader failed" -> "USBMUX_READER_FAILED"
            "USBMUX host is closed" -> "USBMUX_HOST_CLOSED"
            "Lockdown plist channel is closed" -> "LOCKDOWN_CHANNEL_CLOSED"
            "Pipe closed during read" -> "USB_CONNECTION_LOST"
            "Interrupted while waiting for USBMUX TCP data" -> "TCP_WAIT_INTERRUPTED"
            else -> if (safeTransferMessage(error.message) != null) {
                if (error.message.orEmpty().contains("short/failure=-")) "USB_WRITE_FAILURE" else "USB_SHORT_WRITE"
            }
                else if (error is IphoneUsbException.Protocol) "MALFORMED_PROTOCOL"
                else "TRANSPORT_UNAVAILABLE_UNCLASSIFIED"
        }
    }

    private fun safeTransferMessage(message: String?): String? = message?.takeIf {
        it.matches(Regex("(?:TCP_OUT|VERSION_OUT|SETUP07_OUT) short/failure=-?\\d+ expected=\\d+; no retry"))
    }

    private fun safeMessage(error: Throwable): String? {
        if (error is NoSuchMethodError) return missingMethod(error.message.orEmpty())
        if (error is IphoneUsbException) {
            val reason = transportReason(error)
            return safeTransferMessage(error.message) ?: when (reason) {
                "USBMUX_TCP_RESET", "USBMUX_TCP_CLOSED", "LOCKDOWN_EOF",
                "USBMUX_READER_FAILED", "USBMUX_HOST_CLOSED", "LOCKDOWN_CHANNEL_CLOSED",
                "USB_CONNECTION_LOST", "TCP_WAIT_INTERRUPTED" -> error.message
                "LOCKDOWN_TIMEOUT" -> if (error.message == "Timed out while reading Lockdown plist frame")
                    error.message else "Bounded Lockdown/transport receive timed out (other message redacted)"
                else -> null
            }
        }
        return null
    }

    fun format(stage: String, error: Throwable): String {
        val missing = if (error is NoSuchMethodError) missingMethod(error.message.orEmpty()) else null
        return buildString {
            append("Failure stage=").append(stage).append(" errorClass=").append(error.javaClass.name)
            append("; STOP\n")
            append("Exception message=")
            if (missing != null) append("NoSuchMethodError: ").append(missing)
            else append(safeMessage(error) ?: "<redacted: no safely recognized message>")
            append('\n')
            append("Missing method signature=").append(missing ?: "<unavailable>").append('\n')
            var count = 0
            for (frame in error.stackTrace) {
                if (!frame.className.startsWith("com.shilapi.xcertplay.")) continue
                if (count++ == 8) break
                append("Application frame=").append(frame.className).append('.').append(frame.methodName)
                append(" line=").append(frame.lineNumber).append('\n')
            }
            var cause = error.cause
            var depth = 0
            while (cause != null && depth++ < 4) {
                append("Original cause class=").append(cause.javaClass.name)
                append(" safeMessage=").append(safeMessage(cause) ?: "<redacted>").append('\n')
                var frames = 0
                for (frame in cause.stackTrace) {
                    if (!frame.className.startsWith("com.shilapi.xcertplay.")) continue
                    if (frames++ == 8) break
                    append("Original application frame=").append(frame.className).append('.')
                    append(frame.methodName).append(" line=").append(frame.lineNumber).append('\n')
                }
                cause = cause.cause
            }
        }
    }

    private fun missingMethod(message: String): String? {
        val art = Regex("No (?:virtual|static|interface|direct) method " +
            "([A-Za-z0-9_$<>]+\\([A-Za-z0-9_$/;\\[\\]]*\\)[A-Za-z0-9_$/;\\[\\]]+) " +
            "in class (L[A-Za-z0-9_$/]+;)")
            .find(message)
        if (art != null) return art.groupValues[2] + "->" + art.groupValues[1]
        return Regex("'((?:[A-Za-z0-9_.$\\[\\]]+ )?" +
            "[A-Za-z0-9_.$]+\\.[A-Za-z0-9_$<>]+\\([A-Za-z0-9_.$\\[\\], ]*\\))'")
            .find(message)?.groupValues?.get(1)
    }
}
