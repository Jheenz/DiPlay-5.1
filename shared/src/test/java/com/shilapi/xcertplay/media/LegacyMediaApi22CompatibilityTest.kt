package com.shilapi.xcertplay.media

import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.content.pm.PackageManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [23])
class LegacyMediaApi22CompatibilityTest {
    @Test
    fun legacyTrackAndRecorderConstructorsInitialize() {
        assertEquals(23, Build.VERSION.SDK_INT)
        val track = LegacyAudioPlatform.createTrack(
            AudioManager.STREAM_MUSIC,
            48_000,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            8_192,
        )
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun checkSelfPermission(permissionName: String): Int =
                PackageManager.PERMISSION_GRANTED
        }
        val recorder = LegacyAudioPlatform.createRecorder(
            context,
            MediaRecorder.AudioSource.MIC,
            48_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            4_096,
        )
        try {
            assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
            assertEquals(android.media.AudioRecord.STATE_INITIALIZED, recorder.state)
        } finally {
            track.release()
            recorder.release()
        }
    }

    @Test
    fun outputSurfaceUpdatesAreAvailableFromApi23() {
        assertTrue(canUpdateMediaCodecOutputSurface())
    }

    @Test
    fun preApi26AudioFocusCanBeRequestedAndAbandoned() {
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .build()
        val track = LegacyAudioPlatform.createTrack(
            AudioManager.STREAM_MUSIC,
            48_000,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            8_192,
        )
        try {
            val focus = AudioFocusCoordinator(RuntimeEnvironment.getApplication(), enabled = true)
            focus.acquire(track, AudioChannel.MEDIA, attributes, AudioManager.STREAM_MUSIC)
            focus.release(track)
        } finally {
            track.release()
        }
    }
}
