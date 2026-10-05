package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 22, maxSdkVersion = 22)
class LegacyMediaApi22DeviceTest {
    @Test
    fun legacyMediaComponentsInitializeWithoutStartingCarPlay() {
        assertEquals(22, Build.VERSION.SDK_INT)
        assertNotNull(Class.forName(AndroidMediaSink::class.java.name))
        assertNotNull(Class.forName(MicrophoneUplink::class.java.name))
        assertNotNull(Class.forName("com.shilapi.xcertplay.media.VideoDecoder"))
        val track = LegacyAudioPlatform.createTrack(
            AudioManager.STREAM_MUSIC,
            48_000,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            8_192,
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = LegacyAudioPlatform.createRecorder(
            context,
            MediaRecorder.AudioSource.MIC,
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            4_096,
        )
        var decoder: MediaCodec? = null
        try {
            assertEquals("AudioTrack failed to initialize", AudioTrack.STATE_INITIALIZED, track.state)
            assertEquals(
                "AudioRecord failed to initialize",
                android.media.AudioRecord.STATE_INITIALIZED,
                recorder.state,
            )
            assertFalse("API 22 must recreate the decoder when its surface changes", canUpdateMediaCodecOutputSurface())

            val focus = AudioFocusCoordinator(
                context,
                enabled = true,
            )
            focus.acquire(
                track,
                AudioChannel.MEDIA,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
                AudioManager.STREAM_MUSIC,
            )
            focus.release(track)

            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 64, 64), null, null, 0)
            decoder.start()
        } finally {
            decoder?.let {
                try {
                    it.stop()
                } catch (_: IllegalStateException) {
                }
                it.release()
            }
            recorder.release()
            track.release()
        }
    }
}
