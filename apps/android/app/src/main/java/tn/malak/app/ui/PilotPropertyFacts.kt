package tn.malak.app.ui

/** Source-backed values remain visible even when they cannot be auto-filled. */
internal data class PilotPropertyFact(val key:String,val value:String,val page:Int,
    val source:String,val status:String)
internal data class OwnerExtractedValue(val key:String,val fact:PilotPropertyFact?)
internal fun ownerExtractedValues(kind:String?,facts:List<PilotPropertyFact>):List<OwnerExtractedValue> {
    val area=when(kind){"SALE_DEED"->"soldAreaM2";"TRANSFER_CARD"->"transferredAreaM2";"POSSESSION_REPORT"->"possessedAreaM2";else->"registeredAreaM2"}
    val plot=when(kind){"SALE_DEED"->"soldPlotNumber";"POSSESSION_REPORT"->"possessedPlotNumber";else->"registeredPlotNumber"}
    return listOf(OwnerExtractedValue("registerReference",facts.firstOrNull{it.key=="registerReference"}?:facts.firstOrNull{it.key=="parentRegisterReference"}),
        OwnerExtractedValue(area,facts.firstOrNull{it.key==area}),OwnerExtractedValue(plot,facts.firstOrNull{it.key==plot}))
}
internal fun pilotAutofill(kind:String?,facts:List<PilotPropertyFact>):Map<String,String>{
    val trusted=facts.filter{it.status=="CANDIDATE"}.associate{it.key to it.value}
    return buildMap{
        if(kind=="SALE_DEED")trusted["soldAreaM2"]?.let{put("area",it)}
        // A historical transaction price is evidence, not today's asking price.
    }
}
internal fun pilotConfirmableArea(kind:String?,facts:List<PilotPropertyFact>):PilotPropertyFact? =
    if(kind=="OWNERSHIP_CERTIFICATE")facts.singleOrNull{
        it.key=="registeredAreaM2"&&it.status=="CANDIDATE"&&
            Regex("^[1-9][0-9]{0,8}(?:\\.[0-9]{1,3})?$").matches(it.value)
    } else null
