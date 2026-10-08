package com.shilapi.xcertplay

import android.widget.Button
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class MfiProviderSnapshotUiTest {
    @Test fun settingsExposeSnapshotManuallyWithoutRunningItOnOpen() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "launchTestSettings",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, LinearLayout(activity)))

            assertEquals(MfiProviderSnapshot.NOT_RUN,
                ReflectionHelpers.getField<String>(activity, "mfiProviderSnapshotReport"))
            assertFalse(ReflectionHelpers.getField<Boolean>(activity, "mfiProviderSnapshotRunning"))
            val button = ReflectionHelpers.getField<Button?>(activity, "mfiProviderSnapshotButton")
            assertNotNull(button)
            assertTrue(button!!.isEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
