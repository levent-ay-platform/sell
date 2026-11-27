package tn.malak.app.ui.parcel

import kotlin.math.abs

/**
 * Bounded local shape suggestion from a page raster. White enclosed regions
 * are hypotheses only: neither parcel identity nor ground coordinates.
 * No OCR, network, storage or automatic submission. Reject open outlines,
 * page-sized frames and irregular regions that a convex hull would distort.
 */
internal object LocalPlanOutline {
    private data class P(val x:Int,val y:Int)
    private fun cross(a:P,b:P,c:P):Long =
        (b.x-a.x).toLong()*(c.y-a.y)-(b.y-a.y).toLong()*(c.x-a.x)
    private fun hull(points:List<P>):List<P>{
        val ordered=points.distinct().sortedWith(compareBy<P>{it.x}.thenBy{it.y})
        if(ordered.size<3)return emptyList()
        fun half(input:List<P>):List<P>{
            val h=mutableListOf<P>()
            for(p in input){
                while(h.size>=2&&cross(h[h.size-2],h.last(),p)<=0)h.removeAt(h.lastIndex)
                h.add(p)
            }
            return h.dropLast(1)
        }
        return half(ordered)+half(ordered.asReversed())
    }
    fun candidates(width:Int,height:Int,dark:BooleanArray,frame:ParcelFrame):List<List<ParcelPoint>>{
        require(width in 32..640&&height in 32..640&&dark.size==width*height)
        val visited=dark.copyOf()
        val queue=IntArray(dark.size)
        val result=mutableListOf<Pair<Int,List<ParcelPoint>>>()
        val minimum=(dark.size*.015).toInt()
        val maximum=(dark.size*.55).toInt()
        for(start in visited.indices){
            if(visited[start])continue
            var head=0;var tail=1;queue[0]=start;visited[start]=true
            var border=false
            var left=width;var right=0;var top=height;var bottom=0
            while(head<tail){
                val i=queue[head++];val x=i%width;val y=i/width
                if(x==0||x==width-1||y==0||y==height-1)border=true
                left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y)
                fun add(j:Int){if(!visited[j]){visited[j]=true;queue[tail++]=j}}
                if(x>0)add(i-1)
                if(x<width-1)add(i+1)
                if(y>0)add(i-width)
                if(y<height-1)add(i+width)
            }
            if(border||tail !in minimum..maximum||right-left<width*.08||bottom-top<height*.08)continue
            if(left<width*.025||right>width*.975||top<height*.025||bottom>height*.975)continue
            val boundary=ArrayList<P>()
            for(n in 0 until tail){
                val i=queue[n];val x=i%width;val y=i/width
                if(dark[i-1]||dark[i+1]||dark[i-width]||dark[i+width])boundary.add(P(x,y))
            }
            val shape=hull(boundary)
            if(shape.size !in 3..64)continue
            val twice=shape.indices.sumOf{n->
                val a=shape[n];val b=shape[(n+1)%shape.size]
                a.x.toLong()*b.y-b.x.toLong()*a.y
            }
            val area=abs(twice)/2.0
            if(area<=0||tail/area !in .82..1.18)continue
            val points=shape.map{ParcelPoint((it.x+.5)*frame.width/width,(it.y+.5)*frame.height/height)}
            if(frame.validatedRing(points)!=null)result.add(tail to points)
        }
        return result.sortedByDescending{it.first}.take(5).map{it.second}
    }
}
