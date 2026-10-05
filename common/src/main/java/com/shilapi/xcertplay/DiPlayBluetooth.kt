package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.BluetoothCompatibility
import android.content.Context
import android.provider.Settings

internal object DiPlayBluetooth {
    fun localAddress(context: Context): String? {
        val adapter = runCatching { BluetoothCompatibility.adapter(context)?.address }
            .onFailure { android.util.Log.w("DiPlayBluetooth", "Local Bluetooth address unavailable", it) }.getOrNull()
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }
            .onFailure { android.util.Log.w("DiPlayBluetooth", "Stored Bluetooth address unavailable", it) }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }
}
