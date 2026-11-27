package tn.malak.app

import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tn.malak.app.ui.AppCue
import tn.malak.app.ui.explore.loadIndustrialCatalog
import tn.malak.app.ui.explore.loadIndustrialShapes

@RunWith(AndroidJUnit4::class)
class MapCatalogFeedbackTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everyGovernorateNumberMatchesItsIndexedLotGeometries() {
        val catalog = loadIndustrialCatalog(context)
        val features = loadIndustrialShapes(context, catalog).features().orEmpty()
        val parents = catalog.zones.associateBy { it.id }
        assertEquals(47, parents.size)
        assertEquals(2838, features.size)
        assertEquals(features.size, features.map { it.getStringProperty("id") }.distinct().size)
        val perGovernorate = features.groupingBy {
            checkNotNull(parents[it.getStringProperty("zoneId")]).governorate
        }.eachCount()
        assertEquals(24, catalog.governorates.size)
        for ((place, lots) in catalog.governorates) assertEquals(perGovernorate[place.arabic] ?: 0, lots)
    }

    @Test fun actionSoundsAreShortDecodableAudioAssets() {
        for (cue in AppCue.entries) {
            val player = requireNotNull(MediaPlayer.create(context, cue.sound))
            try { assertTrue("${cue.name} duration", player.duration in 30..400) }
            finally { player.release() }
        }
    }
}
