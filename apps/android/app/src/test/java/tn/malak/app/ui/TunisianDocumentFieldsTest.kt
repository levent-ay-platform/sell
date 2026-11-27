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
    @Test fun printedTunisianAreaAllowsBareMetreUnitOnlyAfterAreaLabel(){
        // Fictional CPF-like header: no user image or identifier in CI.
        val cert=TunisianDocumentFields.certificate(
            "معرف الرسم العقاري: 745219 نابل\nالمساحة: 510 م\nالتجزئة: 510 جزء",
            "", "", "صفحة 1 من 2")
        assertEquals("745219",cert.titleNumber)
        assertEquals("510",cert.areaM2)
        assertEquals(2,cert.expectedPages)
        assertNull(TunisianDocumentFields.areaM2("التجزئة: 510 جزء"))
        assertNull(TunisianDocumentFields.areaM2("مجموع 510 م"))
    }
    @Test fun printedCpfOwnerOnlyBandAcceptsOneIsolatedNameButNoOtherPerson(){
        assertEquals("المنصوري سلمى",
            TunisianDocumentFields.certificateOwnerOnlyBand("المنصوري سلمى"))
        assertNull(TunisianDocumentFields.certificateOwnerOnlyBand(
            "المنصوري سلمى\nتقــطن بضاحية أخرى"))
        assertNull(TunisianDocumentFields.certificateOwnerOnlyBand("بن فلان بن فلان 1970"))
    }
    @Test fun printedAreaHeaderAndTotalMustAgree(){
        assertEquals("510",TunisianDocumentFields.confirmedAreaDigits("510","510"))
        assertNull(TunisianDocumentFields.confirmedAreaDigits("510","150"))
        assertNull(TunisianDocumentFields.confirmedAreaDigits("S10","510"))
        assertNull(TunisianDocumentFields.confirmedAreaDigits("510 م","510"))
        assertNull(TunisianDocumentFields.confirmedAreaDigits("","510"))
    }
    @Test fun sparseFallbackRecoversOnlyMissingFieldsWithoutInventingOwners(){
        val primary=TunisianDocumentFields.Identity(
            null,null,null,null,null)
        val sparse=TunisianDocumentFields.Identity(
            "المهدي","ياسمين","المهدي ياسمين","00123456","08 جويلية 1988")
        val merged=TunisianDocumentFields.reconcileIdentity(primary,sparse)
        assertEquals("المهدي ياسمين",merged.name)
        assertEquals("00123456",merged.cinNumber)
        assertEquals("08 جويلية 1988",merged.dateOfBirth)
    }
    @Test fun twoOcrPassesMustNotHideConflictingPersonalFields(){
        val a=TunisianDocumentFields.Identity(
            "المهدي","ياسمين","المهدي ياسمين","00123456","08 جويلية 1988")
        val b=TunisianDocumentFields.Identity(
            "المهدي","يسرى","المهدي يسرى","00123458","09 جويلية 1988")
        val result=TunisianDocumentFields.reconcileIdentity(a,b)
        assertEquals("المهدي",result.surname)
        assertNull(result.givenName)
        assertNull(result.name)
        assertNull(result.cinNumber)
        assertNull(result.dateOfBirth)
    }
    @Test fun fullImageDateNeedsRecognizedBirthLabel(){
        assertNull(TunisianDocumentFields.labelledBirthDate(
            "تاريخ الإصدار\n08 جويلية 1988"))
        assertEquals("08 جويلية 1988",
            TunisianDocumentFields.labelledBirthDate(
                "بطاقة التعريف الوطنية\nتاريخ الولادة\n08 جويلية 1988"))
        assertNull(TunisianDocumentFields.labelledBirthDate(
            "تاريخ الولادة\n08 جويلية 1988\nتاريخ الولادة 09 جويلية 1988"))
    }
    @Test fun cameraFrontMustReadAllFourFields(){
        val ok=LocalDocumentOcr.FrontScan(
            "المهدي","ياسمين","المهدي ياسمين","3456","1988-07-08",83)
        assertTrue(ok.readyForReview)
        assertFalse(ok.copy(cinLast4=null).readyForReview)
        assertFalse(ok.copy(birthDate=null).readyForReview)
        assertFalse(ok.copy(surname=null).readyForReview)
        assertFalse(ok.copy(givenName=null).readyForReview)
        assertFalse(ok.copy(fullName="المهدي يسرى").readyForReview)
        assertFalse(ok.copy(confidence=45).readyForReview)
    }
    @Test fun screenshotRatioCannotBecomeANameOrSurname() {
        val hallucinated=TunisianDocumentFields.identity(
            "بطاقة التعريف الوطنية",
            "RATIO",
            "RATIO",
            "",
            "")
        assertNull(hallucinated.surname)
        assertNull(hallucinated.givenName)
        assertNull(hallucinated.name)
        val labelled=TunisianDocumentFields.identity(
            "اللقب: RATIO\nالاسم: RATIO",
            "اللقب: RATIO",
            "الاسم: RATIO",
            "",
            "")
        assertNull(labelled.surname)
        assertNull(labelled.givenName)
        assertNull(labelled.name)
        assertTrue(TunisianNameParser.identity(
            "اللقب: RATIO\nالاسم: RATIO").isEmpty())
    }
    @Test fun tunisiaArabicLabelsStillAcceptRealArabicComponents(){
        val card=TunisianDocumentFields.identity(
            "بيانات التعريف","اللقب: بن المثال",
            "الاسم: عبد المالك","","")
        assertEquals("بن المثال",card.surname)
        assertEquals("عبد المالك",card.givenName)
        assertEquals("بن المثال عبد المالك",card.name)
        assertNull(TunisianDocumentFields.identity(
            "","اللقب","الاسم","", "").name)
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
