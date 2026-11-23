package tn.malak.app.ui.explore

import kotlin.math.roundToLong

/**
 * Local, deterministic AR/Tunisian/FR query interpretation.
 * This never asks a language model to invent listings, maps, documents or prices.
 * Only recognized constraints are applied; all other words remain unverified wishes.
 */
internal data class SmartDiscoveryIntent(
    val place: AtlasPlace? = null,
    val kind: String? = null,
    val maxPriceTnd: String? = null,
    val minAreaM2: String? = null,
    val maxAreaM2: String? = null,
    val industrial: Boolean = false,
    val asksForDocuments: Boolean = false
) {
    val actionable: Boolean get() = place != null || kind != null || maxPriceTnd != null ||
        minAreaM2 != null || maxAreaM2 != null || industrial

    fun applyTo(existing: DiscoveryFilters): DiscoveryFilters = existing.copy(
        kind = kind ?: existing.kind,
        maxPrice = maxPriceTnd ?: existing.maxPrice,
        minArea = minAreaM2 ?: existing.minArea,
        maxArea = maxAreaM2 ?: existing.maxArea,
        minRooms = if (kind in setOf("LAND", "FACTORY")) null else existing.minRooms
    )

    fun labels(language: String): List<String> = buildList {
        place?.let { add(if (language == "ar") it.arabic else it.french.ifBlank { it.arabic }) }
        kind?.let {
            add(
                if (language == "ar") when (it) {
                    "LAND" -> "أرض"
                    "APARTMENT" -> "شقة"
                    "HOUSE" -> "منزل"
                    "VILLA" -> "فيلا"
                    else -> "مصنع"
                } else when (it) {
                    "LAND" -> "Terrain"
                    "APARTMENT" -> "Appartement"
                    "HOUSE" -> "Maison"
                    "VILLA" -> "Villa"
                    else -> "Usine"
                }
            )
        }
        maxPriceTnd?.let { add(if (language == "ar") "حتى $it د.ت" else "≤ $it TND") }
        if (minAreaM2 != null && maxAreaM2 != null) {
            add("$minAreaM2–$maxAreaM2 m²")
        } else {
            minAreaM2?.let { add("≥ $it m²") }
            maxAreaM2?.let { add("≤ $it m²") }
        }
        if (industrial) add(if (language == "ar") "مخططات AFI (ليست إعلانات)" else "Plans AFI (hors annonces)")
        if (asksForDocuments) add(if (language == "ar") "الوثائق: عند توفرها" else "Documents : si disponibles")
    }
}

private fun normalizeSmartText(value: String): String {
    val digits = buildString {
        for (char in value) append(char.digitToIntOrNull()?.toString() ?: char)
    }
    return foldPlaceName(digits)
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ة', 'ه').replace('ى', 'ي')
        .replace(Regex("[^\\p{L}0-9]+"), " ").trim()
}

private fun hasAnyPhrase(text: String, vararg phrases: String): Boolean =
    phrases.any { " $it " in " $text " }

private val numericToken = Regex("""([0-9]{1,9})\s*(الف|k|mille)?""")
private val priceWithCurrency = Regex(
    """([0-9]{2,9})\s*(الف|k|mille)?\s*(?:دينار|دنانير|دت|dt|tnd|dinars?)"""
)
private val budgetWithNumber = Regex(
    """(?:ميزانيه|ميزانيتي|budget|prix|تفوتش|تتعداش|اقل من|حدود|maximum|moins de|pas plus de)\s*(?:ب|de|a|est|هو)?\s*([0-9]{2,9})\s*(الف|k|mille)?"""
)
private val areaUnit = """(?:متر|م2|m2|m²|m|sqm|metres?|meters?)"""
private val areaRange = Regex(
    """(?:بين|من|entre|de)?\s*([0-9]{2,7})\s*(?:و|الى|a|et|to)\s*([0-9]{2,7})\s*$areaUnit"""
)
private val singleArea = Regex("""([0-9]{2,7})\s*$areaUnit""")

private fun parseMoney(number: String, magnitude: String?): String? {
    val raw = number.toLongOrNull() ?: return null
    val multiplied = if (magnitude in setOf("الف", "k", "mille")) raw * 1000 else raw
    return multiplied.takeIf { it in 100..1_000_000_000 }?.toString()
}

private fun matchPlace(text: String, places: List<AtlasPlace>): AtlasPlace? =
    places.asSequence().mapNotNull { place ->
        val matchLength = sequenceOf(place.arabic, place.french)
            .map(::normalizeSmartText)
            .filter { it.length >= 4 && " $it " in " $text " }
            .maxOfOrNull(String::length) ?: return@mapNotNull null
        matchLength to place
    }.sortedByDescending { it.first }.firstOrNull()?.second

internal fun interpretSmartDiscovery(query: String, places: List<AtlasPlace>): SmartDiscoveryIntent {
    val text = normalizeSmartText(query.take(180))
    if (text.length < 3) return SmartDiscoveryIntent()

    val industrial = hasAnyPhrase(
        text, "afi", "zone industrielle", "zones industrielles", "industriel",
        "industrielle", "صناعي", "صناعيه", "مناطق صناعيه", "منطقه صناعيه"
    )
    val kind = when {
        hasAnyPhrase(text, "فيلا", "villa", "villas") -> "VILLA"
        hasAnyPhrase(text, "شقه", "شقق", "appartement", "appartements", "apartment") -> "APARTMENT"
        hasAnyPhrase(text, "منزل", "بيت", "maison", "maisons") -> "HOUSE"
        hasAnyPhrase(text, "مصنع", "usine", "factory") -> "FACTORY"
        hasAnyPhrase(text, "ارض", "اراضي", "قطعه", "terrain", "terrains", "parcelle", "parcelles") -> "LAND"
        else -> null
    }

    val priceMatch = priceWithCurrency.find(text) ?: budgetWithNumber.find(text)
    val maxPrice = priceMatch?.let { parseMoney(it.groupValues[1], it.groupValues[2]) }

    val range = areaRange.find(text)
    val firstArea: Long?
    val secondArea: Long?
    if (range != null) {
        firstArea = range.groupValues[1].toLongOrNull()
        secondArea = range.groupValues[2].toLongOrNull()
    } else {
        val single = singleArea.find(text)
        val base = single?.groupValues?.get(1)?.toLongOrNull()
        val prefix = single?.let { text.substring(0, it.range.first).takeLast(22) }.orEmpty()
        val approximate = hasAnyPhrase(prefix, "حوالي", "تقريبا", "environ", "approx", "autour de")
        firstArea = base?.let { if (approximate) (it * 0.8).roundToLong() else it }
        secondArea = base?.let { if (approximate) (it * 1.2).roundToLong() else it }
    }
    val areaValid = firstArea != null && secondArea != null &&
        firstArea in 10..10_000_000 && secondArea in firstArea..10_000_000

    val documents = hasAnyPhrase(
        text, "وثائق", "وثيقه", "اوراق", "رسم عقاري", "شهاده ملكيه",
        "شهادات ملكيه", "document", "documents", "titre foncier", "cadastre"
    )

    return SmartDiscoveryIntent(
        place = matchPlace(text, places),
        kind = kind,
        maxPriceTnd = maxPrice,
        minAreaM2 = if (areaValid) firstArea.toString() else null,
        maxAreaM2 = if (areaValid) secondArea.toString() else null,
        industrial = industrial,
        asksForDocuments = documents
    )
}
