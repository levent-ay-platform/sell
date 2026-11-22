package tn.malak.app.ui

import tn.malak.app.ui.explore.PropertyKinds
import java.math.BigDecimal

internal enum class DirectListingStage { Kind, Location, Details, Preview }

internal data class DirectListingDraft(
    val kind: String,
    val title: String,
    val region: String,
    val price: String,
    val area: String,
    val description: String,
    val latitude: String,
    val longitude: String
) {
    val point get() = SmartListingAssist.coordinates(latitude, longitude)

    val fields get() = mapOf(
        "kind" to kind, "title" to title.trim(), "region" to region.trim(),
        "price" to listingNumber(price), "area" to listingNumber(area),
        "description" to description.trim(), "latitude" to latitude, "longitude" to longitude
    )

    fun canContinue(stage: DirectListingStage): Boolean = when (stage) {
        DirectListingStage.Kind -> PropertyKinds.any { it.first == kind }
        DirectListingStage.Location -> point != null && region.trim().length in 2..100
        DirectListingStage.Details -> title.trim().length in 2..100 &&
            validAmount(price, MaxPrice) && validAmount(area, MaxArea)
        DirectListingStage.Preview -> DirectListingStage.entries.dropLast(1).all(::canContinue)
    }

    fun resume(stage: DirectListingStage): DirectListingStage =
        DirectListingStage.entries.take(stage.ordinal).firstOrNull { !canContinue(it) } ?: stage
}

private val Decimal = Regex("[0-9]{1,12}(?:\\.[0-9]{1,3})?")
private val MaxPrice = BigDecimal("1000000000000")
private val MaxArea = BigDecimal("1000000000")

private fun validAmount(input: String, maximum: BigDecimal): Boolean {
    val normalized = listingNumber(input)
    if (!Decimal.matches(normalized)) return false
    val amount = normalized.toBigDecimal()
    return amount.signum() == 1 && amount <= maximum
}

private fun listingNumber(raw: String): String = buildString {
    raw.trim().forEach { character ->
        append(when (character) {
            ',', '٫' -> '.'
            else -> character.digitToIntOrNull()?.digitToChar() ?: character
        })
    }
}
