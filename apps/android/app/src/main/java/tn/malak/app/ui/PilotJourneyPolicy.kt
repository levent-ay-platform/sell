package tn.malak.app.ui

internal fun pilotMissingListingFields(title:String,region:String,area:String,price:String):List<String> = buildList {
    if(title.trim().length !in 2..100)add("title")
    if(region.trim().length !in 2..100)add("region")
    if(!Regex("^[1-9][0-9]{0,8}(?:\\.[0-9]{1,3})?$").matches(area.replace(',','.')))add("area")
    if(!Regex("^[1-9][0-9]{0,11}$").matches(price))add("price")
}

internal fun pilotSubmissionReference(id:String,status:String):String? {
    if(!Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$").matches(id)||
        status !in setOf("PENDING_REVIEW","CHANGES_REQUESTED","REJECTED","PUBLISHED","PAUSED"))return null
    return id.take(8).uppercase(java.util.Locale.ROOT)
}
