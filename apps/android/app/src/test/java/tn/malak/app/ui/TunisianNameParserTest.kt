package tn.malak.app.ui

import org.junit.Assert.*
import org.junit.Test

/** All fixtures are fictitious and contain no uploaded personal document data. */
class TunisianNameParserTest {
    @Test fun tunisianCardOneFaceSeparateArabicLabels() {
        val certificate="وزارة املاك الدولة\nالرسم الأصلي: المكي ياسين\nالملكية العقارية"
        val front="بطاقة التعريف الوطنية\nاللقب: المكي\nالاسم: ياسين\nبن فؤاد بن كامل\nتاريخ الولادة"
        val owner=TunisianNameParser.unique(TunisianNameParser.certificate(certificate))
        val person=TunisianNameParser.unique(TunisianNameParser.identity(front))
        assertEquals("المكي ياسين",owner)
        assertEquals(TunisianNameParser.canonical(owner!!),TunisianNameParser.canonical(person!!))
    }
    @Test fun typedOriginalFieldWithOcrNoiseBeforeLabel() {
        val cert="الرسم العقاري\n‏م)الأصلى(:): المكي ياسين\nهوية المالك و موضوع الملكية"
        assertEquals("المكي ياسين",TunisianNameParser.unique(TunisianNameParser.certificate(cert)))
    }
    @Test fun missingBackIsNotNeededToExtractFrontName() {
        assertEquals("المكي ياسين",
            TunisianNameParser.unique(TunisianNameParser.identity("اللقب المكي\nالاسم ياسين")))
    }
    @Test fun ambiguousOwnerIsNeverMadeIntoAnAgreement() {
        val cert="المالك: المكي ياسين\nالمالك: المكي مروان"
        assertNull(TunisianNameParser.unique(TunisianNameParser.certificate(cert)))
    }
    @Test fun patronymAndDatesDoNotBecomeOwnerFields() {
        assertTrue(TunisianNameParser.identity("بن فؤاد بن كامل\n1980\nتونس").isEmpty())
    }
    @Test fun wrongSurnameIsNotAProvisionalMatch() {
        val a=TunisianNameParser.unique(TunisianNameParser.certificate("الرسم الأصلي: المكي ياسين"))
        val b=TunisianNameParser.unique(TunisianNameParser.identity("اللقب: الزين\nالاسم: ياسين"))
        assertNotEquals(TunisianNameParser.canonical(a!!),TunisianNameParser.canonical(b!!))
    }
    @Test fun unicodeVariantsAreNormalizedWithoutReordering() {
        assertEquals(TunisianNameParser.canonical("الأمين يحيى"),
            TunisianNameParser.canonical("الامين يحيي"))
        assertNotEquals(TunisianNameParser.canonical("المكي ياسين"),
            TunisianNameParser.canonical("ياسين المكي"))
    }
    @Test fun suspiciousControlsOrDigitsNeverBecomeAName() {
        assertTrue(TunisianNameParser.identity("اللقب: المك\u202eي\nالاسم: ياسين").isEmpty())
        assertTrue(TunisianNameParser.certificate("الرسم الأصلي: المكي 1980").isEmpty())
    }
    @Test fun pageCountFromVisibleFooterRequestsMissingTitlePage() {
        assertEquals(2,TunisianNameParser.declaredPages("صفحة 1 من 2"))
        assertEquals(2,TunisianNameParser.declaredPages("صفحة ![ من 2"))
        assertNull(TunisianNameParser.declaredPages("صفحة [ من ؟"))
        assertEquals(2,TunisianNameParser.declaredPages("Page 1 sur 2"))
    }
    @Test fun frenchSurnameAndGivenNameUsePrintedCINOrderOnly() {
        val a=TunisianNameParser.identity("Nom: Ben Salem\nPrénom: Amira")
        assertEquals("Ben Salem Amira",TunisianNameParser.unique(a))
        assertFalse(a.contains("Amira Ben Salem")) // no invented reordering
    }

    @Test fun lowConfidenceScanCannotFalselyClaimDifferentNames() {
        assertEquals("INSUFFICIENT_OR_AMBIGUOUS",
            TunisianNameParser.assess("المكي ياسين","المكي مروان",78))
        assertEquals("TEXT_DIFFERENCE_NEEDS_REVIEW",
            TunisianNameParser.assess("المكي ياسين","المكي مروان",96))
        assertEquals("PRELIMINARY_TEXT_AGREEMENT",
            TunisianNameParser.assess("المكي ياسين","المكي ياسين",74))
    }
    @Test fun incompleteTitleStillRequiresReviewWhenPrintedOwnerAgrees() {
        assertEquals("INSUFFICIENT_OR_AMBIGUOUS",
            TunisianNameParser.assess("المكي ياسين","المكي ياسين",97,true))
    }

}
