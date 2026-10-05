package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.CarPlayController

/** Parked-video playback is excluded from the API 22 install/launch build. */
internal object CarPlayVideo {
    fun attach(context: Context, controller: CarPlayController) {
        controller.videoListener = null
    }

    fun onMediaKey(index: Int): Boolean = false
}
