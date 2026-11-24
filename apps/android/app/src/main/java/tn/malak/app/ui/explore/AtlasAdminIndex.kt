package tn.malak.app.ui.explore

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Administrative context, never evidence of title or surveyed parcel boundaries.
 * Source: packaged atlas-admin reference (see SOURCE.md). */
internal data class AdminName(val id:String,val arabic:String,val french:String){
    fun label(ar:Boolean)=if(ar)arabic.ifBlank{french} else french.ifBlank{arabic}
}
internal data class AdminPolygon(val rings:List<List<DiscoveryPoint>>){
    private val outer=rings.firstOrNull().orEmpty()
    private val west=outer.minOfOrNull{it.longitude}?:Double.POSITIVE_INFINITY
    private val east=outer.maxOfOrNull{it.longitude}?:Double.NEGATIVE_INFINITY
    private val south=outer.minOfOrNull{it.latitude}?:Double.POSITIVE_INFINITY
    private val north=outer.maxOfOrNull{it.latitude}?:Double.NEGATIVE_INFINITY
    // 0 outside, 1 inside, 2 edge (ambiguous even if only one polygon touches).
    fun locate(point:DiscoveryPoint):Int{
        if(point.longitude !in west-1e-9..east+1e-9||point.latitude !in south-1e-9..north+1e-9)return 0
        val first=ringLocation(outer,point)
        if(first!=1)return first
        for(hole in rings.drop(1)){
            when(ringLocation(hole,point)){2->return 2;1->return 0}
        }
        return 1
    }
}
internal fun ringLocation(ring:List<DiscoveryPoint>,p:DiscoveryPoint):Int{
    if(ring.size<4)return 0
    var inside=false
    for(i in ring.indices){
        val a=ring[i];val b=ring[(i+1)%ring.size]
        val x=p.longitude;val y=p.latitude
        val dx=b.longitude-a.longitude;val dy=b.latitude-a.latitude
        if(x>=minOf(a.longitude,b.longitude)-1e-9&&x<=maxOf(a.longitude,b.longitude)+1e-9&&
            y>=minOf(a.latitude,b.latitude)-1e-9&&y<=maxOf(a.latitude,b.latitude)+1e-9&&
            abs(dx*(y-a.latitude)-dy*(x-a.longitude))<=1e-9*(abs(dx)+abs(dy)+1e-9))return 2
        if((a.latitude>y)!=(b.latitude>y)&&x<a.longitude+dx*(y-a.latitude)/dy)inside=!inside
    }
    return if(inside)1 else 0
}
internal data class AdminFeature(val name:AdminName,val parent:String?,val polygons:List<AdminPolygon>){
    fun locate(p:DiscoveryPoint):Int{
        val hits=polygons.map{it.locate(p)}
        return if(2 in hits)2 else if(1 in hits)1 else 0
    }
}
internal data class AdminContext(val governorate:AdminName,val delegation:AdminName?,val imada:AdminName?=null){
    fun region(ar:Boolean)=listOfNotNull(delegation?.label(ar),governorate.label(ar)).joinToString(if(ar)"، " else ", ")
}
internal class AtlasAdminIndex(private val governors:List<AdminFeature>,private val delegations:List<AdminFeature>,private val sectors:List<AdminFeature> = emptyList()){
    val governorateCount get()=governors.size
    val delegationCount get()=delegations.size
    val sectorCount get()=sectors.size
    /** Exact bilingual names within their declared parents, no centroids or coordinates. */
    fun resolveNames(governorate:String?,delegation:String?,imada:String?):AdminContext?{
        fun matches(name:AdminName,value:String):Boolean{
            fun fold(v:String)=foldPlaceName(v).replace(Regex("[أإآ]"),"ا").replace("ى","ي")
            val query=fold(value)
            return query.isNotBlank()&&query in listOf(fold(name.arabic),fold(name.french))
        }
        val g=governorate?.takeIf{it.isNotBlank()}?:return null
        val gov=governors.singleOrNull{matches(it.name,g)}?:return null
        val d=delegation?.let{value->delegations.singleOrNull{it.parent==gov.name.id&&matches(it.name,value)}?:return null}
        val sector=imada?.let{value->
            val parent=d?:return null
            sectors.singleOrNull{it.parent==parent.name.id&&matches(it.name,value)}?:return null
        }
        return AdminContext(gov.name,d?.name,sector?.name)
    }
    fun resolve(point:DiscoveryPoint):AdminContext?{
        if(!point.latitude.isFinite()||!point.longitude.isFinite()||point !in InitialBounds)return null
        val g=governors.map{it to it.locate(point)}.filter{it.second!=0}
        val gov=g.singleOrNull()?.takeIf{it.second==1}?.first?:return null
        // Check all delegations, not just children, to avoid asserting inconsistent parent geometry.
        val d=delegations.map{it to it.locate(point)}.filter{it.second!=0}
        val delegate=d.singleOrNull()?.takeIf{it.second==1&&it.first.parent==gov.name.id}?.first
        val sector=if(delegate==null)null else sectors.map{it to it.locate(point)}
            .filter{it.second!=0}.singleOrNull()?.takeIf{it.second==1&&it.first.parent==delegate.name.id}?.first
        return AdminContext(gov.name,delegate?.name,sector?.name)
    }
    // Agreement at the explicitly selected pin and sampled corners is contextual only.
    // It does not prove that an entire polygon lies inside the administrative unit.
    fun resolveSamples(points:List<DiscoveryPoint>):AdminContext?{
        if(points.isEmpty()||points.size>65)return null
        val hits=points.map{resolve(it)?:return null}
        val gov=hits.first().governorate
        if(hits.any{it.governorate.id!=gov.id})return null
        val d=hits.first().delegation
        val commonDelegate=d?.takeIf{candidate->hits.all{it.delegation?.id==candidate.id}}
        val commonSector=if(commonDelegate==null)null else hits.first().imada?.takeIf{candidate->hits.all{it.imada?.id==candidate.id}}
        return AdminContext(gov,commonDelegate,commonSector)
    }
}
private fun parseAdminRing(raw:JSONArray):List<DiscoveryPoint> = List(raw.length()){i->
    val p=raw.getJSONArray(i)
    DiscoveryPoint(p.getDouble(1),p.getDouble(0)).also{require(it.latitude.isFinite()&&it.longitude.isFinite())}
}
private fun parseAdminPolygon(raw:JSONArray)=AdminPolygon(List(raw.length()){parseAdminRing(raw.getJSONArray(it))})
internal fun parseAdminFeatures(text:String):List<AdminFeature>{
    val features=JSONObject(text).getJSONArray("features")
    return List(features.length()){i->
        val f=features.getJSONObject(i);val props=f.getJSONObject("properties")
        val geo=f.getJSONObject("geometry");val c=geo.getJSONArray("coordinates")
        val polygons=when(geo.getString("type")){
            "Polygon"->listOf(parseAdminPolygon(c))
            "MultiPolygon"->List(c.length()){parseAdminPolygon(c.getJSONArray(it))}
            else->error("UNSUPPORTED_ADMIN_GEOMETRY")
        }
        AdminFeature(AdminName(props.getString("id"),props.optString("name_ar"),props.optString("name_fr")),
            props.optString("delegation").takeIf{it.isNotBlank()}?:props.optString("governorate").takeIf{it.isNotBlank()},polygons)
    }
}
internal fun loadAtlasAdminIndex(context:Context):AtlasAdminIndex{
    fun read(name:String)=context.assets.open("atlas-admin/$name.geojson").bufferedReader().use{parseAdminFeatures(it.readText())}
    val g=read("governorates");val d=read("delegations")
    require(g.size==24&&d.size==279&&g.map{it.name.id}.distinct().size==24&&d.map{it.name.id}.distinct().size==279)
    require(d.all{row->g.any{it.name.id==row.parent}})
    val sectors=try{read("sectors")}catch(_:java.io.FileNotFoundException){emptyList()}
    require(sectors.isEmpty()||sectors.size in 2082..2083)
    require(sectors.map{it.name.id}.distinct().size==sectors.size&&sectors.all{row->d.any{it.name.id==row.parent}})
    return AtlasAdminIndex(g,d,sectors)
}
