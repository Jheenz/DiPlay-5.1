package com.shilapi.xcertplay.transport

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.UUID

object BluetoothCompatibility {
    val IAP2_SERVICE_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")

    fun manager(context: Context): BluetoothManager? =
        if (Build.VERSION.SDK_INT >= 23) {
            context.getSystemService(BluetoothManager::class.java)
        } else {
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        }

    @Suppress("DEPRECATION")
    fun adapter(context: Context): BluetoothAdapter? =
        manager(context)?.adapter ?: BluetoothAdapter.getDefaultAdapter()

    fun runtimePermissions(sdk: Int, scan: Boolean): List<String> = when {
        sdk >= 31 -> if (scan) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            else listOf(Manifest.permission.BLUETOOTH_CONNECT)
        sdk >= 23 && scan -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyList()
    }

    fun missingPermissions(context: Context, scan: Boolean): List<String> =
        runtimePermissions(Build.VERSION.SDK_INT, scan).filter {
            Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
}
