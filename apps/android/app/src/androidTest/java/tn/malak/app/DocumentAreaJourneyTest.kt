package tn.malak.app

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tn.malak.app.ui.*
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DocumentAreaJourneyTest {
    @Test fun restoredSaleDeedSupportsKeepingReplacingAndOfferingPartInPreview() =
        restoredAreaJourney("SALE_DEED", "soldAreaM2", true)

    @Test fun restoredIndividualCertificateCannotOfferWholeEstateAsSaleArea() =
        restoredAreaJourney("OWNERSHIP_CERTIFICATE", "registeredAreaM2", false)

    @Test fun restoredSharedCertificateCannotOfferWholeEstateAsSaleArea() =
        restoredAreaJourney("SHARED_OWNERSHIP_CERTIFICATE", "registeredAreaM2", false)

    private fun restoredAreaJourney(kind: String, areaKey: String, eligible: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        val account = UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()
        val key = MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
        val prefs = context.getSharedPreferences("amlak_direct_$key", 0)
        val session = PilotOcrSession("https://example.invalid", "fixture", Long.MAX_VALUE)
        val fact = PilotPropertyFact(areaKey, "900", 2, "pdf-text", "CANDIDATE")
        val report = CertificateIdentityReview("INSUFFICIENT_OR_AMBIGUOUS", null, null, "NOT_COMPARABLE",
            emptyList(), "FRONT", 2, 1, detectedKind = kind,
            reviewId = id, sourceFacts = listOf(fact))
        prefs.edit().putInt("stage", DirectListingStage.Details.ordinal)
            .putString("kind", "LAND").putString("title", "Terrain de test").putString("region", "Nabeul")
            .putString("latitude", "36.4").putString("longitude", "10.7").putString("area", "275")
            .putString("price", "48000").putString("documentReview", id).commit()
        fun visible(text: String): UiObject2 =
            requireNotNull(device.wait(Until.findObject(By.text(text)), 6000)) { "Missing $text" }
        fun preview(area: String) {
            visible("Aperçu de l’annonce").click()
            visible("$area m² · 48000 TND")
        }
        fun snapshot(name: String) {
            val folder = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(folder, "$name.png")))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val french = activity.createConfigurationContext(Configuration(activity.resources.configuration)
                        .apply { setLocale(Locale.FRENCH) })
                    activity.setContent {
                        CompositionLocalProvider(LocalMalakStringsContext provides french) {
                            MaterialTheme(colorScheme = AmlakColorScheme, typography = AmlakTypography) {
                                Surface(modifier = Modifier.systemBarsPadding()) {
                                    DirectOwnerListing(session, account, null, canAttachDocuments = true,
                                        onBack = {}, onPublished = { error("Unexpected publication") },
                                        readReview = { owner, requested ->
                                            check(owner == session && requested == id)
                                            report
                                        })
                                }
                            }
                        }
                    }
                }
                if (!eligible) {
                    visible("Aucune superficie exploitable. Saisissez la superficie proposée.")
                    assertFalse(device.hasObject(By.text("Utiliser cette superficie")))
                    assertFalse(device.hasObject(By.text("Proposer une partie")))
                    preview("275")
                    assertEquals("275", prefs.getString("area", ""))
                    return@use
                }
                visible("Superficie saisie dans l’annonce · 275 m²")
                assertTrue(device.hasObject(By.textContains("page 2")))
                snapshot("area-document-comparison")
                preview("275")
                device.pressBack()
                visible("Utiliser cette superficie").click()
                preview("900")
                device.pressBack()
                visible("Proposer une partie").click()
                val field = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").focused(true)), 6000))
                field.text = "180"
                device.pressBack()
                preview("180")
                snapshot("area-part-preview")
                assertEquals("180", prefs.getString("area", ""))
            }
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
