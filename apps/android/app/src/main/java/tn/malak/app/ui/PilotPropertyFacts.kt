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
        // The whole registered estate is not the owner\u0027s sale extent, even for an individual certificate.
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

/** A review-only arithmetic ratio; never a sale area or a physical polygon. */
internal fun ownerSharePercentage(parts:String?,facts:List<PilotPropertyFact>):String? {
    val denominator=facts.filter{it.key=="rightsDenominator"}.singleOrNull()
        ?.takeIf{it.page>0&&it.status=="CANDIDATE"}?.value ?: return null
    val pattern=Regex("""[0-9]{1,12}(?:[.,][0-9]{1,3})?""")
    if(parts==null||!pattern.matches(parts)||!pattern.matches(denominator))return null
    val n=parts.replace(',','.').toBigDecimal()
    val d=denominator.replace(',','.').toBigDecimal()
    if(n.signum()<=0||d.signum()<=0||n>d)return null
    return n.multiply(java.math.BigDecimal("100")).divide(d,4,java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
}
