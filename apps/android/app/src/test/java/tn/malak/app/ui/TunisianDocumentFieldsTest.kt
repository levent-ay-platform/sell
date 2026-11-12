package tn.malak.app.ui

import org.junit.Assert.*
import org.junit.Test

/** Spatial text examples are deliberately fictional, never user documents. */
class TunisianDocumentFieldsTest {
    @Test fun noisyTunisianCertificateLabelCanPreserveTwoTokenOwnerInRestrictedZone(){
        val region="+3 أزم)لاصلى(:): السالمي مروان\nهوية المالك وموضوع الملكية"
        assertEquals(listOf("السالمي مروان"),TunisianDocumentFields.certificateOwnerZone(region))
    }
    @Test fun singleFrontCinReadsSeparateSurnameAndGivenRowsWithoutFatherName(){
        val card=TunisianDocumentFields.identity(
            "بيانات بطاقة التعريف","اللقب: السالمي","الاسم: مروان",
            "01234567","08 ديسمبر 1974")
        assertEquals("السالمي مروان",card.name)
        assertEquals("01234567",card.cinNumber)
        assertEquals("08 ديسمبر 1974",card.dateOfBirth)
    }
    @Test fun noGuessedIdentityFromOnlyOneField(){
        val card=TunisianDocumentFields.identity(
            "بن فؤاد بن كامل","","الاسم: مروان","01234567","")
        assertNull(card.name)
        assertNull(card.surname)
    }
    @Test fun distinctPrintedNamesRemainAmbiguous(){
        val cert=TunisianDocumentFields.certificate(
            "المالك: السالمي مروان\nالمالك: السالمي ياسين",
            "", "", "صفحة 1 من 2")
        assertNull(cert.owner)
        assertEquals(2,cert.expectedPages)
    }
    @Test fun labelledTitleNumberAndAreaAreIndependentOfCINNumber(){
        val cert=TunisianDocumentFields.certificate(
            "معرف الرسم العقاري: 654321\nالمساحة: 150 م²",
            "الرسم الأصلي: السالمي مروان","", "Page 1 sur 2")
        assertEquals("654321",cert.titleNumber)
        assertEquals("150",cert.areaM2)
        assertEquals("السالمي مروان",cert.owner)
        assertEquals(2,cert.expectedPages)
    }
    @Test fun suspiciousArabicSurnameControlCannotBeAccepted(){
        val p=TunisianDocumentFields.identity(
            "","اللقب: السال\u202eمي","الاسم: مروان","","")
        assertNull(p.name)
    }
    @Test fun ambiguousCINNumbersAreNotInvented(){
        assertNull(TunisianDocumentFields.numberFromCinBand("01234567 07654321"))
        assertNull(TunisianDocumentFields.numberFromCinBand("0001234"))
    }
    @Test fun noUnlabelledAreaOrTitleGuess(){
        assertNull(TunisianDocumentFields.titleNumber("رقم 654321"))
        assertNull(TunisianDocumentFields.areaM2("150 متر"))
    }
    @Test fun cinDigitDisagreementMustNotBeDisplayedAsReliable() {
        assertEquals("01234567",TunisianDocumentFields.repeatableCin("01234567","01234567"))
        assertNull(TunisianDocumentFields.repeatableCin("01234567","01234587"))
        assertNull(TunisianDocumentFields.repeatableCin("01234567","0I234567"))
        assertNull(TunisianDocumentFields.repeatableCin("01234567",""))
    }

}
