package tn.malak.app

import android.content.ComponentName
import android.os.Build
import android.util.TypedValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupThemeTest {
    @Test
    fun launchActivityUsesADedicatedThemeWithTheOriginalIcon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activity = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertNotEquals("Startup should have its own window theme", context.applicationInfo.theme, activity.themeResource)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val theme = context.resources.newTheme().apply { applyStyle(activity.themeResource, true) }
            val icon = TypedValue()
            assertTrue(theme.resolveAttribute(android.R.attr.windowSplashScreenAnimatedIcon, icon, true))
            assertEquals(context.applicationInfo.icon, icon.resourceId)
        }
    }
}
