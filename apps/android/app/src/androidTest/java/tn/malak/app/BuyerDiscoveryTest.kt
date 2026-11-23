package tn.malak.app

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tn.malak.app.ui.MalakApp
import tn.malak.app.ui.explore.*

@RunWith(AndroidJUnit4::class)
class BuyerDiscoveryTest {
    @Test
    fun searchParcelPreviewAndReturnToTheSameMapResults() {
        assumeTrue(BuildConfig.MALAK_V2_DISCOVERY)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("malak_public_preferences", 0).edit().putString("language", "fr").commit()
        val device = UiDevice.getInstance(instrumentation)
        val map = By.desc("Carte immobilière interactive")
        val search = By.text("Ville ou quartier")
        val preview = By.text("Voir le bien")
        val plan = By.text("Plan parcellaire")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { MalakApp(discoverySource = BuyerFixture) } }
            device.waitFor(search, timeout = 25_000)
            device.waitFor(search).click()
            assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 6_000))
            device.findObject(UiSelector().className("android.widget.EditText")).setText("قليبية")
            device.waitFor(By.text("Kélibia")).click()
            assertTrue(device.wait(Until.gone(By.desc("Réduire la recherche")), 6_000))
            device.waitFor(By.desc("Filtres")).click()
            device.waitFor(By.text("Terrains")).click()
            device.waitFor(By.text("Appliquer")).click()
            device.wait(Until.gone(By.text("Appliquer")), 6_000)

            val bounds = device.waitFor(map).visibleBounds
            for (attempt in 1..6) {
                device.click(bounds.centerX(), bounds.centerY())
                if (device.wait(Until.hasObject(preview), 800)) break
            }
            device.waitFor(preview)
            device.takeScreenshot(File(context.cacheDir, "buyer-preview.png"))
            device.findObject(preview).click()
            device.waitFor(By.text("Documents du bien"))
            device.takeScreenshot(File(context.cacheDir, "buyer-detail.png"))
            for (attempt in 1..3) {
                if (device.wait(Until.hasObject(plan), 1_200)) break
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                    device.displayWidth / 2, device.displayHeight / 3, 20)
            }
            device.waitFor(plan).click()
            device.waitFor(By.desc("Page PDF"))
            device.takeScreenshot(File(context.cacheDir, "buyer-pdf.png"))
            device.pressBack()
            device.waitFor(plan)
            device.pressBack()
            device.waitFor(preview)
            device.waitFor(By.text("Kélibia"))
            // This fixture only returns the marker when the LAND filter is active.
            // The retained preview and the visible filter badge confirm the selection after Back.
            device.waitFor(By.text("1"))
            device.takeScreenshot(File(context.cacheDir, "buyer-return.png"))
        }
    }

    private fun UiDevice.waitFor(selector: BySelector, timeout: Long = 7_000) =
        wait(Until.findObject(selector), timeout).also { assertNotNull("Missing $selector", it) }!!
}

private object BuyerFixture : DiscoverySource {
    private val point = DiscoveryPoint(36.87978, 11.00488)
    private val land = DiscoveryListing("test-land", "Terrain de validation UI", "Kélibia", "LAND",
        "128000", "420", null, point, "APPROXIMATE", null, null)
    private val document = PublicPropertyDocument("test-plan", "PARCEL_PLAN", "application/pdf", "https://example.test")

    override suspend fun suggest(text: String, language: String) = emptyList<DiscoveryPlace>()

    override suspend fun list(query: DiscoveryQuery, cursor: String?) =
        DiscoveryPage(listOf(land).filter { point in query.bounds && query.filters.kind == "LAND" })

    override suspend fun markers(query: DiscoveryQuery, zoom: Int) = DiscoveryMarkers(list(query).listings)

    override suspend fun detail(id: String) = land.takeIf { it.id == id }

    override suspend fun parcel(id: String) =
        PublicParcel(documents = listOf(document).filter { id == land.id })

    override fun documentBytes(document: PublicPropertyDocument): ByteArray {
        check(document == this.document)
        val pdf = PdfDocument()
        return try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(480, 600, 1).create())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f }
            page.canvas.drawText("Document de test", 60f, 66f, paint)
            paint.textSize = 15f
            page.canvas.drawText("Plan parcellaire de validation UI", 60f, 98f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            page.canvas.drawRect(120f, 150f, 360f, 420f, paint)
            pdf.finishPage(page)
            ByteArrayOutputStream().also { pdf.writeTo(it) }.toByteArray()
        } finally {
            pdf.close()
        }
    }
}
