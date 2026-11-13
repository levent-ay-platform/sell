package tn.malak.app.ui

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Free, entirely on-device listing assistance. Suggestions are composed only
 * from seller-supplied facts; no paid API, geocoding, AI claims or remote calls.
 *
 * This is a presentation helper, NOT property, owner or title verification.
 */
internal object SmartListingAssist {
    private val areaPattern = Regex("""^[1-9][0-9]{0,8}(?:\.[0-9]{1,3})?$""")
    private val pricePattern = Regex("""^[1-9][0-9]{0,11}$""")

    private fun area(raw: String): String? {
        val normalized = raw.trim().replace(',', '.')
        return normalized.takeIf { areaPattern.matches(it) }
    }

    fun titleSuggestion(regionRaw: String, areaRaw: String, prefix: String, unit: String): String? {
        val region = regionRaw.trim().replace(Regex("""\s+"""), " ")
        val size = area(areaRaw) ?: return null
        if (region.length !in 2..60 || region.any { Character.isISOControl(it) } ||
            prefix.isBlank() || unit.isBlank()) return null
        val compactArea = BigDecimal(size).stripTrailingZeros().toPlainString()
        return "$prefix $region · $compactArea $unit".takeIf { it.length <= 100 }
    }

    /**
     * Caller-supplied geo point. Never geocode, claim title boundaries or
     * send location to an external provider until the user explicitly opens a map.
     */
    fun coordinates(latRaw:String,lonRaw:String):Pair<Double,Double>? {
        val numeric=Regex("""^-?(?:0|[1-9][0-9]{0,2})(?:[.,][0-9]{1,7})?$""")
        val lat=latRaw.trim().replace(',', '.')
        val lon=lonRaw.trim().replace(',', '.')
        if(!numeric.matches(lat)||!numeric.matches(lon))return null
        val x=lat.toDoubleOrNull()?:return null
        val y=lon.toDoubleOrNull()?:return null
        return if(x in -90.0..90.0 && y in -180.0..180.0) x to y else null
    }

    /** Facts-only multilingual copy, with no fabricated services or sea view. */
    fun descriptionSuggestion(regionRaw:String,areaRaw:String,priceRaw:String,
                              landPrefix:String,unit:String,pricePrefix:String):String? {
        val region=regionRaw.trim().replace(Regex("""\\s+""")," ")
        val size=area(areaRaw)?:return null
        val price=priceRaw.trim()
        if(region.length !in 2..80||region.any{Character.isISOControl(it)}||
           !pricePattern.matches(price)||landPrefix.isBlank()||unit.isBlank()||
           pricePrefix.isBlank())return null
        val cleanArea=BigDecimal(size).stripTrailingZeros().toPlainString()
        return "$landPrefix $region · $cleanArea $unit. $pricePrefix $price TND."
            .takeIf{it.length<=320}
    }

    /** A basic arithmetic hint, not an appraisal or a market price estimate. */
    fun pricePerSquareMeter(areaRaw: String, priceRaw: String): Long? {
        val size = area(areaRaw) ?: return null
        if (!pricePattern.matches(priceRaw)) return null
        return runCatching {
            BigDecimal(priceRaw).divide(BigDecimal(size), 0, RoundingMode.HALF_UP)
                .longValueExact()
        }.getOrNull()
    }
}
