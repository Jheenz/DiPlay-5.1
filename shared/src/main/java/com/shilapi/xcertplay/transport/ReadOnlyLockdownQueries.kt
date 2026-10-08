package com.shilapi.xcertplay.transport

class ReadOnlyLockdownQueries(
    private val request: (LockdownPlistValue.Dictionary) -> LockdownPlistValue.Dictionary,
) {
    private var typeAttempted = false
    private var typeConfirmed = false
    private var valueAttempted = false

    fun queryType(): String {
        check(!typeAttempted) { "QueryType once only" }
        typeAttempted = true
        val reply = exchange("QueryType")
        val type = (reply.entries["Type"] as? LockdownPlistValue.Text)?.value
        if (type != "com.apple.mobile.lockdown") throw IphoneUsbException.Protocol("QueryType returned unexpected service type")
        typeConfirmed = true
        return type
    }

    fun productType(): String {
        check(typeConfirmed && !valueAttempted) { "One ProductType after valid QueryType only" }
        valueAttempted = true
        val reply = exchange("GetValue", "ProductType")
        val value = (reply.entries["Value"] as? LockdownPlistValue.Text)?.value
        if (value == null || !value.matches(Regex("[A-Za-z0-9,._-]{1,64}"))) {
            throw IphoneUsbException.Protocol("ProductType response was not safe model metadata")
        }
        return value
    }

    private fun exchange(operation: String, key: String? = null): LockdownPlistValue.Dictionary {
        val entries = linkedMapOf<String, LockdownPlistValue>(
            "Label" to LockdownPlistValue.Text("DiPlay-ReadOnlyDiscovery"),
            "Request" to LockdownPlistValue.Text(operation),
        )
        if (key != null) entries["Key"] = LockdownPlistValue.Text(key)
        val reply = request(LockdownPlistValue.Dictionary(entries))
        if (reply.entries.containsKey("Error")) {
            val error = (reply.entries["Error"] as? LockdownPlistValue.Text)?.value
                ?.takeIf { it.matches(Regex("[A-Za-z]{1,64}")) } ?: "unrecognized remote error"
            throw IphoneUsbException.Protocol("$operation remote error=$error; no Pair/Trust fallback")
        }
        if ((reply.entries["Request"] as? LockdownPlistValue.Text)?.value != operation) {
            throw IphoneUsbException.Protocol("$operation response Request mismatch")
        }
        return reply
    }
}
