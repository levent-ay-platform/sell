package tn.malak.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ActivityScenario
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import tn.malak.app.ui.LocalMalakStringsContext
import tn.malak.app.ui.explore.PropertyDocumentViewer
import tn.malak.app.ui.explore.PublicPropertyDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndustrialMapTest {
    @Test
    fun opensTheRealArianaIndustrialSubdivisionAndAgencyDocuments() {
        assumeTrue(BuildConfig.MALAK_V2_DISCOVERY)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        context.getSharedPreferences("malak_public_preferences", 0).edit().putString("language", "fr").commit()
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))

        device.await(By.desc("Couches de la carte"), 25_000).click()
        device.await(By.text("Parcourir les zones AFI")).click()
        device.await(By.clazz("android.widget.EditText"))
        device.findObject(UiSelector().className("android.widget.EditText")).setText("Ariana")
        device.await(By.text("ARIANA AEROPORT")).click()
        assertTrue(device.wait(Until.gone(By.clazz("android.widget.EditText")), 8_000))
        device.await(By.desc("Carte immobilière interactive"), 15_000)
        val document = By.textContains("Cahier des charges")
        device.openPlan(document)
        assertTrue(device.takeScreenshot(File(context.cacheDir, "afi-documents.png")))
        device.await(document).click()
        device.await(By.desc("Page PDF"), 25_000)
        device.waitForIdle()
        // The rendered PDF image can reach accessibility before the display presents the frame.
        android.os.SystemClock.sleep(500)
        assertTrue(device.takeScreenshot(File(context.cacheDir, "afi-plan.png")))
        device.await(By.desc("Page suivante")).click()
        device.await(By.text("Page 2 sur 35"), 25_000)
        device.pressBack()
        device.await(document)
        device.pressBack()
        val plan = By.text("Plans et lots")
        device.await(plan)
        assertTrue(device.takeScreenshot(File(context.cacheDir, "afi-subdivision.png")))
        device.openPlan(document)
        assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("Lot 1")))
        device.await(By.text("Lot 1")).click()
        device.await(plan)
        device.await(By.text("Lot 1"))
        assertTrue(device.takeScreenshot(File(context.cacheDir, "afi-lot.png")))
    }

    // Synthetic document only: the first source read succeeds and every later
    // network read would fail. All three page renders must use that one snapshot.
    @Test
    fun viewerTurnsPagesWithoutDownloadingAgain() {
        val bytes = ByteArrayOutputStream().use { output ->
            PdfDocument().use { pdf ->
                repeat(3) { index ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(180, 260, index + 1).create())
                    page.canvas.drawColor(if (index == 0) android.graphics.Color.WHITE else android.graphics.Color.LTGRAY)
                    pdf.finishPage(page)
                }
                pdf.writeTo(output)
            }
            output.toByteArray()
        }
        val calls = AtomicInteger()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val document = PublicPropertyDocument("synthetic-pdf", "AFI_PLAN", "application/pdf", "fixture")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val french = activity.createConfigurationContext(
                    Configuration(activity.resources.configuration).apply { setLocale(Locale.FRENCH) })
                activity.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalMalakStringsContext provides french) {
                            PropertyDocumentViewer(document, "Synthetic PDF", {}, readBytes = {
                                check(calls.incrementAndGet() == 1) { "unexpected repeat download" }
                                bytes
                            })
                        }
                    }
                }
            }
            device.await(By.text("Page 1 sur 3"), 25_000)
            device.await(By.desc("Page suivante")).click()
            device.await(By.text("Page 2 sur 3"), 25_000)
            device.await(By.desc("Page suivante")).click()
            device.await(By.text("Page 3 sur 3"), 25_000)
            assertEquals(1, calls.get())
        }
    }

    private fun UiDevice.await(selector: BySelector, timeout: Long = 9_000) =
        wait(Until.findObject(selector), timeout).also { assertNotNull("Missing $selector", it) }!!

    private fun UiDevice.openPlan(document: BySelector) {
        val plan = By.text("Plans et lots")
        waitForIdle()
        await(plan).click()
        if (!wait(Until.hasObject(document), 5_000)) {
            findObject(plan)?.click()
            await(document)
        }
    }
}
