package tn.malak.app.ui

/**
 * No personal examples, logging or network calls. These routines only parse
 * text extracted on-device from spatially constrained Tunisian document zones.
 * Missing/contradictory fields remain null and must never be inferred from a
 * different document.
 */
internal object TunisianDocumentFields {
    internal data class Certificate(
        val owner:String?,val titleNumber:String?,val areaM2:String?,
        val expectedPages:Int?
    )
    internal data class Identity(
        val surname:String?,val givenName:String?,val name:String?,
        val cinNumber:String?,val dateOfBirth:String?
    )
    private val arDiacritics=Regex("""[\u064B-\u065F\u0670]""")
    private val cleanWhitespace=Regex("""\s+""")
    private val invalidChars=Regex("""[\p{Cc}\p{Cf}]""")
    private val letters=Regex("""^[\p{L}\p{M} \-'’]{2,60}$""")
    private fun trimmed(s:String)=s.trim().trim(' ',':','：','؛','،','-','–','(',')')
        .replace(cleanWhitespace," ")
    private fun asciiDigits(s:String):String=buildString {
        for(c in s)append(if(c.isDigit()) ('0'.code+Character.digit(c,10)).toChar() else c)
    }
    private fun component(raw:String):String? {
        val value=trimmed(raw)
        if(value.length !in 2..55||invalidChars.containsMatchIn(value)||
            !letters.matches(value)||value.split(' ').any{it.length<2})return null
        val ar=value.any{it in '\u0600'..'\u06ff'}
        val lat=value.any{it in 'A'..'Z'||it in 'a'..'z'||it in '\u00C0'..'\u024F'}
        return value.takeIf{ar!=lat}
    }
    /** This is valid only for an actual cropped title-holder zone. A noisy OCR
     * heading may be unreadable while the name following its colon is clear.
     * Never run this routine on unrelated full-document body text.
     */
    fun certificateOwnerZone(ocr:String):List<String> {
        val hits=mutableListOf<String>()
        for(raw in ocr.lineSequence()) {
            val line=raw.trim().replace(arDiacritics,"")
            val pos=maxOf(line.lastIndexOf(':'),line.lastIndexOf('：'))
            if(pos<0||pos==line.lastIndex)continue
            val tail=trimmed(line.substring(pos+1))
            val name=TunisianNameParser.unique(listOf(tail))?:continue
            if(name.split(' ').size in 2..4)hits.add(name)
        }
        return hits.distinctBy(TunisianNameParser::canonical)
    }
    /** The CIN printed labels and their values occupy two separate narrow
     * rows. A value must be legible in its OWN row; no father-name fallback.
     */
    private fun row(ocr:String,expected:String):String? {
        val values=mutableListOf<String>()
        for(line in ocr.lineSequence()) {
            val raw=trimmed(line.replace(arDiacritics,""))
            if(raw.isBlank()||invalidChars.containsMatchIn(raw))continue
            val label=Regex(if(expected=="surname") """(?iu)^(?:اللقب|nom)\s*[:：\-]?\s*"""
                else """(?iu)^(?:الاسم|pr[ée]nom)\s*[:：\-]?\s*""")
            val text=label.replaceFirst(raw,"")
            val value=component(text)?:continue
            if(value.split(' ').size<=3)values.add(value)
        }
        val dedup=values.distinctBy(TunisianNameParser::canonical)
        return dedup.singleOrNull()
    }
    fun identityFromRows(surnameRegion:String,givenRegion:String):Pair<String?,String?> =
        row(surnameRegion,"surname") to row(givenRegion,"given")
    fun titleNumber(text:String):String? {
        val clean=asciiDigits(text)
        val label=Regex("""(?:معرف\s+الرسم\s+العقار[يى]|الرسم\s+العقار[يى]|titre\s+foncier)\s*[:：]?\s*(\d{5,9})""",RegexOption.IGNORE_CASE)
        return label.findAll(clean).map{it.groupValues[1]}.distinct().singleOrNull()
    }
    fun areaM2(text:String):String? {
        val clean=asciiDigits(text)
        // Tunisian CPF printouts may show square metres simply as "م" rather than "م²".
        // Only accept that abbreviated unit immediately after an explicit area label.
        val re=Regex("""(?:المساحة|superficie)\s*[:：]?\s*(\d{1,7}(?:[.,]\d{1,2})?)\s*(?:م|m)(?:\s*[²2])?(?=\s|$|[،؛;:])""",RegexOption.IGNORE_CASE)
        return re.findAll(clean).map{it.groupValues[1]}.distinct().singleOrNull()
    }
    fun numberFromCinBand(text:String):String? {
        val clean=asciiDigits(text)
        val re=Regex("""(?<!\d)\d{8}(?!\d)""")
        return re.findAll(clean).map{it.value}.distinct().singleOrNull()
    }
    /** Two independent image renderings must agree on every digit.
     * Agreement still means OCR consistency only, not an authentic CIN.
     */
    fun repeatableCin(primary:String,secondary:String):String? {
        val first=numberFromCinBand(primary)
        val second=numberFromCinBand(secondary)
        return first?.takeIf{it==second}
    }
    fun birthDate(text:String):String? {
        val clean=asciiDigits(text)
        val re=Regex("""(?:[0-3]?\d[/.\-\s]+[0-1]?\d[/.\-\s]+(?:19|20)\d{2}|[0-3]?\d\s+(?:جانفي|فيفري|مارس|أفريل|ماي|جوان|جويلية|أوت|سبتمبر|أكتوبر|نوفمبر|ديسمبر|janvier|février|mars|avril|mai|juin|juillet|août|septembre|octobre|novembre|décembre)\s+(?:19|20)\d{2})""",RegexOption.IGNORE_CASE)
        return re.findAll(clean).map{it.value.trim()}.distinct().singleOrNull()
    }
    fun certificate(fullText:String,holderRegion:String,numberRegion:String,footer:String):Certificate {
        val fromLabels=TunisianNameParser.certificate(fullText)
        val fromRegion=certificateOwnerZone(holderRegion)
        val all=(fromLabels+fromRegion).distinctBy(TunisianNameParser::canonical)
        val num=titleNumber(numberRegion)?:titleNumber(fullText)
        return Certificate(TunisianNameParser.unique(all),num,
            areaM2(fullText)?:areaM2(numberRegion),
            TunisianNameParser.declaredPages(footer)
                ?:TunisianNameParser.declaredPages(fullText))
    }
    fun identity(nameBlock:String,surnameBand:String,givenBand:String,
                 numberBand:String,birthBand:String):Identity {
        val (surname,given)=identityFromRows(surnameBand,givenBand)
        val fromRows=if(surname!=null&&given!=null)
            TunisianNameParser.unique(listOf(surname+" "+given)) else null
        val fromLabels=TunisianNameParser.unique(TunisianNameParser.identity(nameBlock))
        val actual=when {
            fromRows!=null&&fromLabels!=null&&
                TunisianNameParser.canonical(fromRows)!=TunisianNameParser.canonical(fromLabels)->null
            else->fromRows?:fromLabels
        }
        return Identity(surname,given,actual,
            numberFromCinBand(numberBand),birthDate(birthBand))
    }
}
