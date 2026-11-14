package tn.malak.app.ui

/** Source-backed values remain visible even when they cannot be auto-filled. */
internal data class PilotPropertyFact(val key:String,val value:String,val page:Int,
    val source:String,val status:String)
internal fun pilotAutofill(kind:String?,facts:List<PilotPropertyFact>):Map<String,String>{
    val trusted=facts.filter{it.status=="CANDIDATE"}.associate{it.key to it.value}
    return buildMap{
        if(kind=="SALE_DEED")trusted["soldAreaM2"]?.let{put("area",it)}
        // A historical transaction price is evidence, not today's asking price.
    }
}
