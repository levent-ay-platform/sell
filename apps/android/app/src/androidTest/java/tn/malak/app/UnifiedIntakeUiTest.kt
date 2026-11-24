package tn.malak.app

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tn.malak.app.ui.*
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UnifiedIntakeUiTest {
    @Test fun allDocumentSlotsAndPhotosAreReachableWithoutStepNavigation() {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val draft="offline-ui-"+UUID.randomUUID()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val fr=activity.createConfigurationContext(Configuration(activity.resources.configuration)
                    .apply { setLocale(Locale.FRENCH) })
                activity.setContent {
                    CompositionLocalProvider(LocalMalakStringsContext provides fr) {
                        MaterialTheme(colorScheme=AmlakColorScheme, typography=AmlakTypography) {
                            Surface(modifier=Modifier.systemBarsPadding()) {
                                SellerOcrJourney(onBack={},draftScope=draft)
                            }
                        }
                    }
                }
            }
            fun seek(label:String) {
                repeat(12) {
                    if(device.wait(Until.hasObject(By.text(label)),700)==true)return
                    val x=device.displayWidth/2
                    device.swipe(x,device.displayHeight*70/100,x,device.displayHeight*30/100,25)
                    device.waitForIdle()
                }
                assertTrue("Missing intake section: "+label,device.hasObject(By.text(label)))
            }
            seek("Ajouter votre certificat de propriété")
            seek("Carte d’identité")
            seek("Importer la pièce pour aperçu")
            seek("Plan parcellaire")
            seek("Document complémentaire")
            seek("Photos du terrain")
        }
    }
}
