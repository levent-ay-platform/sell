package tn.malak.app

import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tn.malak.app.ui.MalakApp
import tn.malak.app.ui.explore.*

/** Isolated on-device QA: local fixture, no live API, no owner data, no publishing. */
@RunWith(AndroidJUnit4::class)
class PublicFeedAreaOnlyUiTest {
    @Test
    fun incompletePublishedListingRemainsReadableWithoutInventedPinOrType() {
        assumeTrue(BuildConfig.MALAK_V2_DISCOVERY)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("malak_public_preferences", 0)
            .edit().putString("language", "fr").commit()

        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent { MalakApp(discoverySource = AreaOnlyFixture) }
            }
            val explore = By.text("Explorer")
            assertNotNull("The list tab is missing", device.wait(Until.findObject(explore), 20_000))
            device.findObject(explore).click()

            val title = By.text("QA — Annonce sans position")
            assertNotNull("Real list card failed to render", device.wait(Until.findObject(title), 20_000))
            assertNotNull("Missing kind must be visible",
                device.wait(Until.findObject(By.textContains("Type de bien non précisé")), 8_000))
            assertNotNull("Unknown public location must be visible",
                device.wait(Until.findObject(By.textContains("Secteur uniquement")), 8_000))
            assertTrue("Unable to capture list screenshot", device.takeScreenshot(
                File(context.getExternalFilesDir(null), "p0-public-feed-fr.png")))

            device.findObject(title).click()
            assertNotNull("Cannot open item detail from list",
                device.wait(Until.findObject(By.text("QA — Annonce sans position")), 8_000))
            device.pressBack()
            assertNotNull("List navigation did not restore result",
                device.wait(Until.findObject(title), 8_000))
        }
    }
}

private object AreaOnlyFixture : DiscoverySource {
    private val item = DiscoveryListing(
        "12345678-1234-4234-8234-123456789012",
        "QA — Annonce sans position", "Nabeul", "UNKNOWN",
        "80000", "300", null, null, "AREA_ONLY", null, null
    )

    override suspend fun list(query: DiscoveryQuery, cursor: String?) =
        DiscoveryPage(listOf(item))
    override suspend fun markers(query: DiscoveryQuery, zoom: Int) =
        DiscoveryMarkers(emptyList())
    override suspend fun suggest(text: String, language: String) =
        emptyList<DiscoveryPlace>()
    override suspend fun detail(id: String) = item.takeIf { id == item.id }
}
