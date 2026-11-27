package tn.malak.app

import android.content.res.Resources
import android.os.Build
import android.util.TypedValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LightThemeContrastTest {
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    @Test
    fun applicationThemeOptsOutOfAutomaticDarkening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val theme: Resources.Theme = context.resources.newTheme().apply {
            applyStyle(context.applicationInfo.theme, true)
        }
        val allowed = TypedValue()
        assertTrue(theme.resolveAttribute(android.R.attr.forceDarkAllowed, allowed, true))
        assertEquals("Android must preserve the app's light foreground/background pairs", 0, allowed.data)
    }
}
