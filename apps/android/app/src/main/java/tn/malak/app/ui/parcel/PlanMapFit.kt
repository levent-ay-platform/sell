package tn.malak.app.ui.parcel

import tn.malak.app.ui.explore.DiscoveryPoint
import tn.malak.app.ui.explore.InitialBounds
import kotlin.math.*

/** Owner-selected control correspondence, not independent survey/registry evidence. */
internal data class PlanMapFit(val points:List<DiscoveryPoint>,val checkErrorM:Double)
private fun distance(a:ParcelPoint,b:ParcelPoint)=hypot(a.x-b.x,a.y-b.y)
private fun cross(a:ParcelPoint,b:ParcelPoint,c:ParcelPoint)=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)

/** Three well-spaced fit vertices and a separate fourth check. */
internal fun planControlIndices(raw:List<ParcelPoint>):List<Int>{
    val v=openParcelRing(raw)
    if(v.size !in 4..64||v.distinct().size!=v.size||v.any{!it.x.isFinite()||!it.y.isFinite()})return emptyList()
    var pair=0 to 1
    for(i in v.indices)for(j in i+1 until v.size)
        if(distance(v[i],v[j])>distance(v[pair.first],v[pair.second]))pair=i to j
    val a=pair.first;val b=pair.second;val span=distance(v[a],v[b])
    if(span<=0)return emptyList()
    val c=v.indices.filter{it!=a&&it!=b}.maxByOrNull{abs(cross(v[a],v[b],v[it]))}?:return emptyList()
    val determinant=abs(cross(v[a],v[b],v[c]))
    if(determinant<span*span*.02)return emptyList()
    // Prefer an independent corner away from all three lines of the fit triangle.
    fun separation(i:Int)=minOf(abs(cross(v[a],v[b],v[i]))/distance(v[a],v[b]),
        abs(cross(v[b],v[c],v[i]))/distance(v[b],v[c]),
        abs(cross(v[c],v[a],v[i]))/distance(v[c],v[a]))
    val d=v.indices.filter{it !in listOf(a,b,c)}.maxByOrNull(::separation)?:return emptyList()
    if(separation(d)<span*.005)return emptyList()
    return listOf(a,b,c,d)
}
internal fun fitPlanToMap(raw:List<ParcelPoint>,indices:List<Int>,controls:List<DiscoveryPoint>):PlanMapFit?{
    val v=openParcelRing(raw)
    if(v.size !in 4..64||indices!=planControlIndices(v)||indices.size!=4||controls.size!=4||
        controls.distinct().size!=4||controls.any{!it.latitude.isFinite()||!it.longitude.isFinite()||it !in InitialBounds})return null
    val origin=controls.first()
    val latScale=PI/180*6371008.8;val lonScale=latScale*cos(origin.latitude*PI/180)
    val q=controls.map{ParcelPoint((it.longitude-origin.longitude)*lonScale,(it.latitude-origin.latitude)*latScale)}
    val a=v[indices[0]];val b=v[indices[1]];val c=v[indices[2]]
    val bx=b.x-a.x;val by=b.y-a.y;val cx=c.x-a.x;val cy=c.y-a.y
    val det=bx*cy-by*cx
    if(abs(det)<1e-9)return null
    fun project(p:ParcelPoint):ParcelPoint{
        val dx=p.x-a.x;val dy=p.y-a.y
        val u=(dx*cy-dy*cx)/det;val w=(bx*dy-by*dx)/det
        return ParcelPoint(q[1].x*u+q[2].x*w,q[1].y*u+q[2].y*w)
    }
    // Do not silently accept an implausibly warped drawing. Check singular-value ratio.
    val e=project(ParcelPoint(a.x+1,a.y));val f=project(ParcelPoint(a.x,a.y+1))
    val trace=e.x*e.x+e.y*e.y+f.x*f.x+f.y*f.y
    val determinant=e.x*f.y-e.y*f.x
    val disc=sqrt(maxOf(0.0,trace*trace-4*determinant*determinant))
    val lo=(trace-disc)/2;val hi=(trace+disc)/2
    if(!lo.isFinite()||lo<=0||!hi.isFinite()||sqrt(hi/lo)>1.35)return null
    val check=distance(project(v[indices[3]]),q[3])
    val span=q.maxOf{distance(q[0],it)}
    if(span<2||span>20000||check>maxOf(1.0,minOf(5.0,span*.02)))return null
    val points=v.map{val p=project(it);DiscoveryPoint(origin.latitude+p.y/latScale,origin.longitude+p.x/lonScale)}
    if(measureOwnerMap(points)==null)return null
    return PlanMapFit(points,check)
}
