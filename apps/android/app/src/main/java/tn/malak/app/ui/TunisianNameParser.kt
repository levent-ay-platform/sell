package tn.malak.app.ui

import java.text.Normalizer
import java.util.Locale

/**
 * Conservative OCR-field parser for Tunisian title certificates and CIN cards.
 * Only labelled fields are considered; father's name, dates, title numbers and
 * unlabelled free text must NEVER become candidate owners.
 * Return candidate strings, not identity decisions. No fuzzy similarity,
 * invented letters, remote transmission, logs or stored PII.
 */
internal object TunisianNameParser {
    private val whitespace=Regex("""\s+""")
    private val diacritics=Regex("""\p{M}+""")
    private val controls=Regex("""[\p{Cc}\p{Cf}\p{N}]""")
    private val fullName=Regex("""^[\p{L}\p{M}\p{Zs}'’\-]{5,100}$""")
    private val component=Regex("""^[\p{L}\p{M}\p{Zs}'’\-]{2,55}$""")
    private val ownerLabel=Regex("""(?iu)(?:المالك|اسم\s+المالك|صاحب\s+الرسم|هوية\s+المالك|الأصل[يى]|propri[ée]taire|titulaire\s+du\s+titre)""")
    private val arabicFull=Regex("""(?iu)^(?:الاسم\s+واللقب|اللقب\s+والاسم|nom\s+et\s+pr[ée]noms?|nom\s+complet)\s*[:：\-–]?\s+(.+)$""")
    private val lastLabel=Regex("""(?iu)^(?:اللقب|nom)\s*[:：\-–]?\s*(.+)?$""")
    private val firstLabel=Regex("""(?iu)^(?:الاسم|pr[ée]nom)\s*[:：\-–]?\s*(.+)?$""")
    private val leadingBidi=setOf('\u200e','\u200f','\u061c')
    private fun lines(raw:String):List<String> = raw.lineSequence()
        .map{it.trim().trimStart { c-> c in leadingBidi }}
        .filter{it.isNotEmpty()&&it.length<=160}.toList()

    private fun clean(raw:String):String =
        raw.trim().trim(' ',':','：','-','–','(',')','،','؛','\u061c','\u200f','\u200e')
            .replace(whitespace," ").trim()

    private fun valid(raw:String,minParts:Int):String? {
        val n=clean(raw)
        if(n.length !in 4..100||controls.containsMatchIn(n)||
           !fullName.matches(n))return null
        val parts=n.split(' ').filter{it.isNotBlank()}
        if(parts.size !in minParts..7||parts.any{it.length<2})return null
        val ar=n.any{it in '\u0600'..'\u06ff'}
        val lat=n.any{it in 'A'..'Z'||it in 'a'..'z'||it in '\u00c0'..'\u024f'}
        if(ar==lat)return null // reject mixed scripts and meaningless names
        return n
    }
    private fun validComponent(raw:String):String? {
        val n=clean(raw)
        return if(n.length<=55&&!controls.containsMatchIn(n)&&component.matches(n)&&
            n.split(" ").all{it.length>=2})n else null
    }
    private fun afterLabel(line:String):String? {
        val pos=maxOf(line.lastIndexOf(':'),line.lastIndexOf('：'))
        return if(pos>=0&&pos+1<line.length)line.substring(pos+1) else null
    }
    /** Reads the explicit owner/registered titleholder, not arbitrary body names. */
    fun certificate(text:String):List<String> {
        val raw=lines(text);val out=mutableListOf<String>()
        for((i,line) in raw.withIndex()){
            if(!ownerLabel.containsMatchIn(line))continue
            // Printouts often label "الرسم(هم) الأصلي(ة):" followed by the owner.
            val candidate=afterLabel(line)
            valid(candidate?:"",2)?.let{out.add(it)}
            // On some scans the value wraps to the next line. Do not cross
            // into a different labelled field or a date/numeric region.
            if(candidate.isNullOrBlank()&&i+1<raw.size)
                valid(raw[i+1],2)?.let{out.add(it)}
        }
        return out.distinctBy(::canonical)
    }
    /** CIN front can contain just "اللقب" and "الاسم" in separate rows. */
    fun identity(text:String):List<String> {
        val raw=lines(text)
        val full=mutableListOf<String>()
        val first=mutableListOf<String>()
        val last=mutableListOf<String>()
        for((i,line) in raw.withIndex()) {
            arabicFull.matchEntire(line)?.groupValues?.getOrNull(1)?.let {
                valid(it,2)?.let(full::add)
            }
            fun piece(regex:Regex):String? {
                val matched=regex.matchEntire(line)?:return null
                val part=matched.groupValues.getOrNull(1).orEmpty().trim()
                // When OCR puts the label and value on separate lines, use
                // exactly the next unlabelled name line. No father-name jump.
                return validComponent(if(part.isNotEmpty())part else
                    raw.getOrNull(i+1).orEmpty())
            }
            piece(lastLabel)?.let(last::add)
            piece(firstLabel)?.let(first::add)
        }
        // Separate fields are evidence only if both labels are present and
        // internally consistent. "اللقب + الاسم" is printed surname first
        // on Tunisian CIN; reconstruct the same order as title certificates.
        val surnames=last.distinctBy(::canonical)
        val given=first.distinctBy(::canonical)
        if(surnames.size==1&&given.size==1) {
            valid(surnames[0]+" "+given[0],2)?.let(full::add)
            // French title examples often print given first; keep this second
            // order only when both components are Latin, never fuzzy match.
            if(!surnames[0].any{it in '\u0600'..'\u06ff'})
                valid(given[0]+" "+surnames[0],2)?.let(full::add)
        }
        return full.distinctBy(::canonical)
    }
    fun canonical(text:String):String =
        Normalizer.normalize(text,Normalizer.Form.NFKD)
            .replace(diacritics,"").replace("ـ","")
            .replace(Regex("[إأآٱ]"),"ا")
            .replace('ى','ي')
            .lowercase(Locale.ROOT)
            .replace(whitespace," ").trim()
    fun unique(names:List<String>):String? {
        if(names.isEmpty()||names.any{valid(it,2)==null})return null
        return names.firstOrNull()?.takeIf { first->
            names.all{canonical(it)==canonical(first)}
        }
    }
    /** Only explicit printed page count; never infer all title pages from one photo. */
    fun declaredPages(text:String):Int? {
        val ar=Regex("""(?:صفحة|الصفحة)\s*[\d٠-٩]+\s*من\s*([2-8٢-٨])""")
        val fr=Regex("""(?iu)\bpage\s*\d+\s*(?:sur|/)\s*([2-8])""")
        val match=ar.find(text)?.groupValues?.get(1)?:fr.find(text)?.groupValues?.get(1)
        return match?.let{if(it.length==1) Character.getNumericValue(it[0]) else null}
    }
}
