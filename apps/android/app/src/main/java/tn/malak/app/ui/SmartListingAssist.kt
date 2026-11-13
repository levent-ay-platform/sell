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
