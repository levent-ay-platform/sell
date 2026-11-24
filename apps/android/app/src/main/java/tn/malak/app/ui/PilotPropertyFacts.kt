package tn.malak.app.ui

/** Source-backed values remain visible even when they cannot be auto-filled. */
internal data class PilotPropertyFact(val key:String,val value:String,val page:Int,
    val source:String,val status:String)
internal data class OwnerExtractedValue(val key:String,val fact:PilotPropertyFact?)
internal fun ownerExtractedValues(kind:String?,facts:List<PilotPropertyFact>):List<OwnerExtractedValue> {
    val area=when(kind){"SALE_DEED"->"soldAreaM2";"TRANSFER_CARD"->"transferredAreaM2";"POSSESSION_REPORT"->"possessedAreaM2";else->"registeredAreaM2"}
    val plot=when(kind){"SALE_DEED"->"soldPlotNumber";"POSSESSION_REPORT"->"possessedPlotNumber";else->"registeredPlotNumber"}
    val primary=listOf(OwnerExtractedValue("registerReference",facts.firstOrNull{it.key=="registerReference"}),
        OwnerExtractedValue(area,facts.firstOrNull{it.key==area}),OwnerExtractedValue(plot,facts.firstOrNull{it.key==plot}))
    val extraKeys=listOf("parentRegisterReference","registeredParcelCount","registeredPropertyName","registeredPropertyContent",
        "registeredLandCategory","creationNature","rightsDenominator","propertyGovernorate","propertyDelegation","propertyImada",
        "historicalLocality","registeredLocationText")
    return primary+extraKeys.mapNotNull{key->facts.singleOrNull{it.key==key}?.let{OwnerExtractedValue(key,it)}}
}
private val documentAreaPattern = Regex("""[1-9][0-9]{0,8}(?:\.[0-9]{1,3})?""")

internal fun listingAreaEvidence(kind: String?, facts: List<PilotPropertyFact>): PilotPropertyFact? {
    val key = when (kind) {
        "OWNERSHIP_CERTIFICATE" -> "registeredAreaM2"
        "SALE_DEED" -> "soldAreaM2"
        else -> return null
    }
    return facts.filter { it.key == key }.singleOrNull()?.takeIf {
        it.status == "CANDIDATE" && it.page > 0 && documentAreaPattern.matches(it.value)
    }
}

internal fun pilotAutofill(kind: String?, facts: List<PilotPropertyFact>): Map<String, String> {
    if (kind != "SALE_DEED") return emptyMap()
    val fact = listingAreaEvidence(kind, facts) ?: return emptyMap()
    return mapOf("area" to fact.value)
}

internal fun sameListingArea(entered: String, evidence: PilotPropertyFact): Boolean {
    val number = listingNumber(entered)
    return listingDecimalPattern.matches(number) && number.toBigDecimal().compareTo(evidence.value.toBigDecimal()) == 0
}
