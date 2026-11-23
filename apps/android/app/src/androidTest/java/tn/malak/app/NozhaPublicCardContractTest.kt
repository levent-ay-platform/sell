package tn.malak.app

import androidx.test.ext.junit.runners.AndroidJUnit4
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
