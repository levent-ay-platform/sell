package tn.malak.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import tn.malak.app.ui.explore.planFeatures
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import tn.malak.app.MainActivity
import tn.malak.app.ui.AmlakColorScheme
import tn.malak.app.ui.AmlakTypography
import tn.malak.app.ui.explore.NozhaProjectDetail
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tn.malak.app.ui.explore.NozhaLotStatus
import tn.malak.app.ui.explore.decodeNozhaProject
import java.time.Instant

/** Synthetic ONLY; never a real public CRM response or a published project. */
@RunWith(AndroidJUnit4::class)
class NozhaPublicCardContractTest {
    private val now = Instant.parse("2026-11-23T10:00:00Z").toEpochMilli()
    private fun fixture(): JSONObject = JSONObject("""
      {
        "schemaVersion":1,"source":"CASAIMED_CRM",
        "project":{"id":"3","code":"561663","title":"Nozha"},
        "advertisedBy":{"name":"CasaImed Group","role":"MARKETING_AGENT_NOT_LEGAL_OWNER"},
        "refreshedAt":"2026-11-23T09:55:00Z","inventoryCurrent":true,
        "counts":{"total":3,"available":1,"reserved":0,"sold":1,"held":0,"blocked":1},
        "lots":[
          {"id":"101","code":"7","status":"AVAILABLE","availableForInquiry":true,
           "areaSqm":"150.000","asking":{"amountMinor":"18900000","currency":"TND",
           "exponent":3,"pricingBasis":"total"},"geometry":null},
          {"id":"102","code":"8","status":"SOLD","availableForInquiry":false,
           "areaSqm":"150.000","asking":null,"geometry":null},
          {"id":"103","code":"9","status":"BLOCKED","availableForInquiry":false,
           "areaSqm":"300.000","asking":null,"geometry":null}
        ],
        "sourcePlan":{"display":false,"reason":"NO_PUBLIC_PLAN_RENDITION"},
        "verification":{"ownership":"NOT_VERIFIED","title":"NO_APPROVED_OFFICIAL_REFERENCE",
           "cadastralBoundary":"NOT_VERIFIED","atlasSpatialMatch":"NOT_VERIFIED","overall":"NOT_VERIFIED"},
        "land":{"classification":"agricultural_land","ownershipModelClaim":"individual_title",
           "waterStatus":"on_project","electricityStatus":"on_project"}
      }
    """.trimIndent())

    @Test fun onlyAvailableCurrentLotMayReceiveInquiry() {
        val card = decodeNozhaProject(fixture(), now)
        assertNotNull(card)
        assertEquals(3, card!!.lots.size)
        assertEquals(1, card.counts[NozhaLotStatus.SOLD])
        assertEquals(1, card.counts[NozhaLotStatus.AVAILABLE])
        assertTrue(card.lots[0].inquiryAllowed)
        assertEquals("18900000", card.lots[0].askingMinor)
        assertFalse(card.lots[1].inquiryAllowed)
        assertNull(card.lots[1].askingMinor)
        assertFalse(card.lots[2].inquiryAllowed)
        assertNotEquals(NozhaLotStatus.SOLD.color, NozhaLotStatus.AVAILABLE.color)
    }

    @Test fun pricingBasisSurvivesProjectionAndEveryPriceCarriesItsUnit() {
        val payload = fixture()
        val ask = payload.getJSONArray("lots").getJSONObject(0).getJSONObject("asking")
        ask.put("amountMinor", "125500")
        for ((basis, expected) in listOf(
            "total" to "125.5 TND",
            "per_sqm" to "125.5 TND / m²",
            "starting_from" to "À partir de 125.5 TND"
        )) {
            ask.put("pricingBasis", basis)
            val lot = requireNotNull(decodeNozhaProject(payload, now)).lots.first()
            assertEquals(expected, lot.askingLabel(ar = false))
        }
    }

    @Test fun projectCardRendersPricePerSquareMeterInTheLotDialog() {
        val payload = fixture()
        payload.getJSONArray("lots").getJSONObject(0).getJSONObject("asking").put("pricingBasis", "per_sqm")
        val card = requireNotNull(decodeNozhaProject(payload, now))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = AmlakColorScheme, typography = AmlakTypography) {
                        NozhaProjectDetail(card, "fr", onBack = {}, onLotInquiry = {})
                    }
                }
            }
            val lot = device.wait(Until.findObject(By.text("7")), 8_000)
            requireNotNull(lot).click()
            assertTrue(device.wait(Until.hasObject(By.text("Prix : 18900 TND / m²")), 5_000))
        }
    }

    @Test fun openLotDialogRefreshesWhenCrmMovesThroughReservationSaleAndRelease() {
        val state = mutableStateOf(requireNotNull(decodeNozhaProject(fixture(), now)))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val price = By.text("Prix : 18900 TND")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = AmlakColorScheme, typography = AmlakTypography) {
                        NozhaProjectDetail(state.value, "fr", onBack = {})
                    }
                }
            }
            requireNotNull(device.wait(Until.findObject(By.text("7")), 8_000)).click()
            assertTrue(device.wait(Until.hasObject(price), 5_000))
            for ((status, label) in listOf("RESERVED" to "Réservé", "SOLD" to "Vendu", "AVAILABLE" to "Disponible")) {
                val payload = fixture()
                val lot = payload.getJSONArray("lots").getJSONObject(0)
                val summary = payload.getJSONObject("counts")
                val available = status == "AVAILABLE"
                lot.put("status", status).put("availableForInquiry", available)
                summary.put("available", if (available) 1 else 0)
                    .put("reserved", if (status == "RESERVED") 1 else 0)
                    .put("sold", if (status == "SOLD") 2 else 1)
                scenario.onActivity { state.value = requireNotNull(decodeNozhaProject(payload, now)) }
                assertTrue(device.wait(Until.hasObject(By.text("Lot 7")), 5_000))
                assertTrue(device.wait(Until.hasObject(By.text(label)), 5_000))
                assertTrue(if (available) device.wait(Until.hasObject(price), 5_000)
                    else device.wait(Until.gone(price), 5_000))
            }
        }
    }

    @Test fun onlyAnApprovedApproximateProjectPointCanEnterRadar() {
        val payload = fixture()
        assertNull(decodeNozhaProject(payload, now)?.projectPoint)
        val point = JSONObject("""{"latitude":36.85,"longitude":11.09,"accuracy":"APPROXIMATE","source":"REVIEWED_PROJECT_POINT"}""")
        payload.put("projectPoint", point)
        val accepted = requireNotNull(decodeNozhaProject(payload, now)?.projectPoint)
        assertTrue(tn.malak.app.ui.explore.DiscoveryRadar(accepted, 1).contains(accepted))
        point.put("source", "UNVERIFIED")
        assertNull(decodeNozhaProject(payload, now)?.projectPoint)
        point.put("source", "REVIEWED_PROJECT_POINT").put("latitude", 0)
        assertNull(decodeNozhaProject(payload, now)?.projectPoint)
    }

    @Test fun approximateLotRendersAndIsSelectableInMapLibre() {
        val card = requireNotNull(decodeNozhaProject(fixture(), now))
        val points = org.json.JSONArray("""[{"code":"7","ring":[[11.088,36.848],[11.092,36.848],[11.092,36.852],[11.088,36.852],[11.088,36.848]]}]""")
        val lots = tn.malak.app.ui.explore.parseNozhaPlan(points, card)
        assertEquals(1, lots.size)
        val geometry = card.copy(pilotPlan = lots).planFeatures()
        val selected = java.util.concurrent.atomic.AtomicReference("")
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { androidx.compose.foundation.layout.Box {
                    tn.malak.app.ui.explore.DiscoveryMap(
                        styleUrl = "asset://maps/liberty-globe.json",
                        initial = tn.malak.app.ui.explore.DiscoveryCamera(
                            tn.malak.app.ui.explore.DiscoveryPoint(36.85, 11.09), 16.0,
                            tn.malak.app.ui.explore.InitialBounds
                        ),
                        jump = null, markers = tn.malak.app.ui.explore.DiscoveryMarkers(emptyList()),
                        selectedId = null, priceLabel = { "" },
                        modifier = androidx.compose.ui.Modifier.fillMaxSize().semantics { contentDescription = "Test map" },
                        nozhaPlan = geometry, onNozhaLot = { selected.set(it) },
                        onCameraIdle = { _, _ -> }, onListing = {}, onCluster = {},
                        onFailure = {}, onReady = { ready.set(true) }
                    )
                } }
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 25_000
            while (!ready.get() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
            assertTrue("Map style did not load", ready.get())
            val bounds = device.findObject(By.desc("Test map")).visibleBounds
            for (attempt in 1..12) {
                device.click(bounds.centerX(), bounds.centerY())
                Thread.sleep(500)
                if (selected.get() == "7") break
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            device.takeScreenshot(java.io.File(context.cacheDir, "nozha-plan-render.png"))
            assertEquals("7", selected.get())
        }
    }

    @Test fun staleSnapshotNeverPromotesAvailableToForSale() {
        val stale = fixture()
        stale.put("inventoryCurrent", false)
        stale.getJSONArray("lots").getJSONObject(0).put("availableForInquiry", false)
        val card = decodeNozhaProject(stale, now)
        assertNotNull(card)
        assertFalse(card!!.current)
        assertFalse(card.lots[0].inquiryAllowed)
        assertNull(card.lots[0].askingMinor)
    }

    @Test fun strictTenantProjectAndReviewScopeFailClosed() {
        val badScope = fixture()
        badScope.getJSONObject("project").put("code","999999")
        assertNull(decodeNozhaProject(badScope, now))
        val badProof = fixture()
        badProof.getJSONObject("verification").put("ownership","VERIFIED")
        assertNull(decodeNozhaProject(badProof, now))
        val unapproved = fixture()
        unapproved.getJSONObject("sourcePlan").put("display",true)
        assertNull(decodeNozhaProject(unapproved, now))
    }

    @Test fun fabricatedSoldInquiryAndStatusCountsAreDenied() {
        val bad = fixture()
        bad.getJSONArray("lots").getJSONObject(1).put("availableForInquiry",true)
        assertNull(decodeNozhaProject(bad,now))
        val falseCount = fixture()
        falseCount.getJSONObject("counts").put("sold",0)
        assertNull(decodeNozhaProject(falseCount,now))
    }

    @Test fun unknownPersonalFieldsCannotEnterPublicCardModel() {
        val x = fixture()
        x.put("privateOwnerName","PRIVATE FAKE OWNER")
        x.put("customerNames",org.json.JSONArray().put("PRIVATE PERSON"))
        val card = decodeNozhaProject(x,now)
        assertNotNull(card)
        assertFalse(card.toString().contains("PRIVATE FAKE OWNER"))
        assertFalse(card.toString().contains("PRIVATE PERSON"))
    }
}
