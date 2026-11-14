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
        val label=Regex(
            if(expected=="surname") """(?iu)^(?:اللقب|القب|nom)\s*[:：\-]?\s*"""
            else """(?iu)^(?:الاسم|اسم|pr[ée]nom)\s*[:：\-]?\s*"""
        )
        val forbidden=Regex("""(?:الاسم|اللقب|القب|الولادة|التعريف|العنوان|الجنسية|تاريخ)""")
        val values=mutableListOf<String>()
        for(line in ocr.lineSequence()) {
            val raw=trimmed(line.replace(arDiacritics,""))
            if(raw.isBlank()||invalidChars.containsMatchIn(raw))continue
            // A previous bug used label.replaceFirst(raw, "") even when
            // there was NO label match, accepting unrelated OCR like RATIO.
            val match=label.find(raw)?:continue
            if(match.range.first!=0)continue
            val text=trimmed(raw.substring(match.range.last+1))
            // This document is the Arabic-script Tunisian CIN front.
            // A Latin OCR hallucination is not a surname/given name.
            if(text.any {it in 'A'..'Z'||it in 'a'..'z'||
                    it in '\u00c0'..'\u024f'} ||
                !text.any{it in '\u0621'..'\u064a'} ||
                forbidden.containsMatchIn(text))continue
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
    /** Name ONLY from the isolated printed owner-name line of a CPF table.
     * Never apply to the table body, where residences and relatives occur.
     */
    fun certificateOwnerOnlyBand(text:String):String? {
        val lines=text.lineSequence().map{trimmed(it.replace(arDiacritics,""))}
            .filter{it.isNotBlank()}.toList()
        if(lines.size!=1)return null
        val name=TunisianNameParser.unique(lines)?:return null
        return name.takeIf{it.split(' ').size in 2..4}
    }
    /** No inferred square metres: independently read header digits must agree
     * with the total printed in a *different* CPF zone.
     */
    fun confirmedAreaDigits(headerDigits:String,totalDigits:String):String? {
        val number=Regex("""[1-9]\d{0,6}""")
        val h=asciiDigits(headerDigits).trim()
        val t=asciiDigits(totalDigits).trim()
        return h.takeIf{number.matches(it)&&it==t}
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
    /** Accept a field from two distinct OCR passes only when neither pass
     * contradicts the other. Absent values are never fabricated or fuzzied.
     */
    private fun reconcileText(a:String?,b:String?):String? = when {
        a==null -> b
        b==null -> a
        TunisianNameParser.canonical(a)==TunisianNameParser.canonical(b)->a
        else -> null
    }
    fun reconcileIdentity(primary:Identity,sparse:Identity):Identity {
        val surname=reconcileText(primary.surname,sparse.surname)
        val given=reconcileText(primary.givenName,sparse.givenName)
        val name=reconcileText(primary.name,sparse.name)?.takeIf{ whole ->
            val parts=if(surname!=null&&given!=null)
                TunisianNameParser.unique(listOf("$surname $given")) else null
            parts==null || TunisianNameParser.canonical(parts)==
                TunisianNameParser.canonical(whole)
        }
        return Identity(surname,given,name,
            reconcileText(primary.cinNumber,sparse.cinNumber),
            reconcileText(primary.dateOfBirth,sparse.dateOfBirth))
    }
    /** Use the full-image birth date only if adjacent to an OCR-recognized
     * birth-date label. Else another date on the card could be substituted.
     */
    fun labelledBirthDate(text:String):String? {
        val rows=text.lineSequence().map{it.trim()}.filter{it.isNotEmpty()}.toList()
        val label=Regex("""(?iu)(?:تاريخ\s*الولادة|date\s+de\s+naissance)""")
        val found=mutableListOf<String>()
        for((i,line) in rows.withIndex()){
            if(!label.containsMatchIn(line))continue
            for(segment in listOf(line,rows.getOrNull(i+1).orEmpty())){
                val value=birthDate(segment)
                if(value!=null)found.add(value)
            }
        }
        return found.distinct().singleOrNull()
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
