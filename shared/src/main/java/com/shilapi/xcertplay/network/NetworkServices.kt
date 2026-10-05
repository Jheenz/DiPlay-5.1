package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build

internal fun Context.connectivityService(): ConnectivityManager? =
    if (Build.VERSION.SDK_INT >= 23) getSystemService(ConnectivityManager::class.java)
    else getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

internal fun Context.wifiService(): WifiManager? =
    if (Build.VERSION.SDK_INT >= 23) getSystemService(WifiManager::class.java)
    else getSystemService(Context.WIFI_SERVICE) as? WifiManager
