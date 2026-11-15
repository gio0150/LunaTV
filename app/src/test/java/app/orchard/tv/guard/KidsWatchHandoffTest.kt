package app.orchard.tv.guard

import android.content.Context
import android.os.UserManager
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class KidsWatchHandoffTest {
    private val app = RuntimeEnvironment.getApplication()
    private fun handoff(on: Boolean) {
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        app.getSharedPreferences("halo_identity_migration", Context.MODE_PRIVATE).edit().putBoolean("handoff", on).commit()
        app.createDeviceProtectedStorageContext().getSharedPreferences("halo_identity_migration", Context.MODE_PRIVATE).edit().putBoolean("handoff", on).commit()
    }

    @Test fun queuedWatcherAndDirectBringBackDoNothingAfterHandoff() {
        handoff(false)
        ReflectionHelpers.setStaticField(KidsWatch::class.java, "app", app)
        KidsWatch.poke()
        handoff(true)
        val method = KidsWatch.javaClass.getDeclaredMethod("bringBack", Context::class.java, String::class.java)
        method.isAccessible = true
        method.invoke(KidsWatch, app, "test")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun quizOverlayCannotBeOpenedAfterHandoff() {
        handoff(true)
        assertFalse(QuizOverlay.show(app, accessibility = true))
        assertFalse(QuizOverlay.visible)
    }

    @Test fun lateUnlockDoesNotRestartGuardAfterHandoff() {
        handoff(true)
        val service = Robolectric.buildService(HomeGuardService::class.java).get()
        ReflectionHelpers.setField(service, "unlockPending", true)
        val method = HomeGuardService::class.java.getDeclaredMethod("onUserUnlocked", String::class.java)
        method.isAccessible = true
        method.invoke(service, "test")
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "unlockPending"))
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
