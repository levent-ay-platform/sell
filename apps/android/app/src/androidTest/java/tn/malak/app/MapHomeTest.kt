package tn.malak.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapHomeTest {
    @Test
    fun mapOpensWithCompactSearchAndShowsNoSampleListings() {
        assumeTrue(BuildConfig.MALAK_V2_DISCOVERY && BuildConfig.MALAK_DISCOVERY_BASE_URL.isEmpty()
            && !BuildConfig.MALAK_DIRECT_PUBLISHING)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("malak_public_preferences", 0).edit().putString("language", "fr").commit()
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val device = UiDevice.getInstance(instrumentation)

        val globe = By.desc("Vue du globe")
        val propertyMap = By.desc("Carte immobilière interactive")
        val hasGlobe = BuildConfig.MALAK_MAP_STYLE_URL.isEmpty()
        if (hasGlobe && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            assertTrue(device.wait(Until.hasObject(globe), 20_000))
            assertTrue(device.wait(Until.gone(globe), 6_000))
        }
        val search = By.text("Ville ou quartier")
        val houses = By.text("Maisons")
        assertTrue(device.wait(Until.hasObject(search), 20_000))
        assertFalse(device.hasObject(houses))
        assertFalse(device.hasObject(By.textContains("annonces fictives")))

        if (hasGlobe) {
            assertTrue(device.wait(Until.hasObject(propertyMap), 8_000))
            repeat(6) {
                device.findObject(propertyMap)?.let { target ->
                    target.setGestureMarginsPercentage(.12f, .23f, .12f, .26f)
                    target.pinchClose(.88f, 400)
                }
                device.waitForIdle()
            }
            assertTrue(device.wait(Until.hasObject(globe), 6_000))
            // Compose can be idle before MapLibre presents the new globe tiles.
            android.os.SystemClock.sleep(3_000)
            val globeBounds = device.findObject(globe).visibleBounds
            val screenshot = File(context.cacheDir, "world-zoom-out.png")
            assertTrue(device.takeScreenshot(screenshot))
            val world = BitmapFactory.decodeFile(screenshot.absolutePath)
            val sampleOffset = (globeBounds.width() * .17).toInt()
            for (x in listOf(-sampleOffset, sampleOffset)) {
                for (y in listOf(-sampleOffset, sampleOffset)) {
                    val pixel = world.getPixel(globeBounds.centerX() + x, globeBounds.centerY() + y)
                    assertTrue("A globe quadrant is missing", Color.green(pixel) > 85)
                }
            }
            val recenter = By.desc("Recentrer sur la Tunisie")
            assertTrue(device.wait(Until.hasObject(recenter), 5_000))
            device.findObject(recenter).click()
            assertTrue(device.wait(Until.hasObject(propertyMap), 6_000))
        }

        device.findObject(By.text("Explorer")).click()
        assertTrue(device.wait(Until.hasObject(By.text("La recherche est indisponible dans cette version.")), 10_000))
        assertFalse(device.hasObject(By.textContains("annonces fictives")))
        assertFalse(device.hasObject(By.textContains("Terrain résidentiel près de la mer")))

        device.pressBack()
        assertTrue(device.wait(Until.hasObject(propertyMap), 8_000))
        device.findObject(search).click()
        val collapseSearch = By.desc("Réduire la recherche")
        assertTrue(device.wait(Until.hasObject(collapseSearch), 5_000))
        device.findObject(UiSelector().className("android.widget.EditText")).setText("Kelibia")
        val kelibiaSuggestion = By.text("Kélibia")
        assertTrue(device.wait(Until.hasObject(kelibiaSuggestion), 5_000))
        device.findObject(kelibiaSuggestion).click()
        assertTrue(device.wait(Until.gone(collapseSearch), 5_000))
        assertTrue(device.hasObject(kelibiaSuggestion))

        val layers = By.desc("Couches de la carte")
        assertTrue(device.wait(Until.hasObject(layers), 5_000))
        device.findObject(layers).click()
        assertTrue(device.wait(Until.hasObject(By.text("Biens à vendre")), 5_000))
        assertFalse(device.hasObject(By.text("Villas")))
        val adminLayer = By.desc("Limites administratives Atlas")
        assertTrue(device.hasObject(adminLayer))
        device.findObject(adminLayer).click()
        device.waitForIdle()
        device.takeScreenshot(File(context.cacheDir, "layers-qa.png"))
        val done = By.desc("Fermer")
        assertTrue(device.wait(Until.hasObject(done), 5_000))
        device.findObject(done).click()
        assertTrue(device.wait(Until.gone(done), 5_000))
        assertTrue(device.wait(Until.hasObject(By.textContains("© OpenStreetMap")), 5_000))
        device.takeScreenshot(File(context.cacheDir, "administrative-atlas.png"))
    }
}
