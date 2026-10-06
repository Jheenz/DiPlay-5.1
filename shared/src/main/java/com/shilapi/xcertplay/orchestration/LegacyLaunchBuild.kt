package com.shilapi.xcertplay.orchestration

/** Geely hardware validation: opt-in local diagnostics only, never projection or vehicle work. */
object LegacyLaunchBuild {
    const val CONNECTIONS_ENABLED = false
    const val VENDOR_INTEGRATION_ENABLED = false
    const val PHASE3A_DIAGNOSTICS_ENABLED = true
    const val PHASE3B_DIAGNOSTICS_ENABLED = true
    const val PHASE3B_TRANSPORT_TESTS_ENABLED = false
}
