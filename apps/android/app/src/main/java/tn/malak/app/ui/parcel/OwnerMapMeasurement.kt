package tn.malak.app.ui.parcel

import tn.malak.app.ui.explore.DiscoveryPoint
import tn.malak.app.ui.explore.InitialBounds
import kotlin.math.*

/** Local metric estimate for small owner-drawn polygons. Never registry evidence. */
internal data class OwnerMapMeasurement(val areaM2:Double,val lengthsM:List<Double>)
internal fun measureOwnerMap(points:List<DiscoveryPoint>):OwnerMapMeasurement?{
    if(points.size !in 3..64||points.distinct().size!=points.size||
        points.any{!it.latitude.isFinite()||!it.longitude.isFinite()||it !in InitialBounds})return null
    val origin=points.first()
    val rad=PI/180.0
    val radius=6371008.8
    val xy=points.map{p->ParcelPoint((p.longitude-origin.longitude)*rad*radius*cos(origin.latitude*rad),
        (p.latitude-origin.latitude)*rad*radius)}
    val minX=xy.minOf{it.x};val minY=xy.minOf{it.y}
    if(xy.maxOf{it.x}-minX>20000||xy.maxOf{it.y}-minY>20000)return null
    val ring=ParcelFrame(100000,100000).validatedRing(xy.map{ParcelPoint(it.x-minX,it.y-minY)})?:return null
    val area=abs(ring.zipWithNext().sumOf{(a,b)->a.x*b.y-b.x*a.y})/2
    val lengths=ring.zipWithNext().map{(a,b)->hypot(a.x-b.x,a.y-b.y)}
    if(area<1||lengths.any{it<.5})return null
    return OwnerMapMeasurement(area,lengths)
}
