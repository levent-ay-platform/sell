package tn.malak.app.ui

import kotlin.math.abs
import kotlin.math.sqrt

/** Frame-only luminance statistics; no identity pixels, names, or scans persisted.
 * Detected bounds and texture are guidance, NOT real-time OCR or authenticity.
 */
internal enum class CaptureHint {
    WAIT, GOOD, CIN_UNCLEAR, DARK, GLARE, TOO_FAR, MOVE_BACK, MOVING, TILT, FOCUS
}
internal data class CardBounds(
    val left:Float,val top:Float,val right:Float,val bottom:Float
)
internal data class FieldZone(
    val x0:Float,val y0:Float,val x1:Float,val y1:Float
)
internal data class FrameAssessment(
    val hint:CaptureHint,
    val bounds:CardBounds?=null,
    val zones:List<Boolean> = emptyList(),
    // Card bounds mapped to the rotated capture viewport, not the UI overlay.
    val captureBounds:CardBounds?=null
)

internal object CaptureFrameAssessment {
    // Approximate text-bearing regions on the normalized Tunisian CIN FRONT.
    // These rectangles measure local contrast only; they never decode characters.
    val zones=listOf(
        FieldZone(.25f,.16f,.77f,.41f),   // 8-digit CIN
        FieldZone(.59f,.43f,.91f,.58f),   // surname
        FieldZone(.59f,.55f,.91f,.71f),   // given name
        FieldZone(.27f,.70f,.87f,.91f)    // birth date
    )

    private fun gradient(p:IntArray,w:Int,x:Int,y:Int,vertical:Boolean):Int {
        val k=y*w+x
        return if(vertical)abs(p[k-w]-p[k+w]) else abs(p[k-1]-p[k+1])
    }
    private fun consistentEdge(p:IntArray,w:Int,h:Int,vertical:Boolean,first:Boolean):Int? {
        // Horizontal search identifies the two left/right vertical strokes.
        // Vertical search identifies the two top/bottom horizontal strokes.
        val size=if(vertical)h else w
        val from=if(first)3 else (size*.64).toInt()
        val to=if(first)(size*.36).toInt() else size-4
        val axisStart=if(vertical)(w*.27).toInt() else (h*.24).toInt()
        val axisEnd=if(vertical)(w*.73).toInt() else (h*.76).toInt()
        val scores=DoubleArray(size)
        for(q in from..to){
            var sum=0.0
            var active=0
            for(z in axisStart..axisEnd step 3){
                val x=if(vertical)z else q
                val y=if(vertical)q else z
                val delta=gradient(p,w,x,y,vertical)
                if(delta>=24){sum+=minOf(delta,150);active++}
            }
            // Long continuous edges beat isolated letter strokes/desk noise.
            scores[q]=if(active>=((axisEnd-axisStart)/3+1)*.48)sum else 0.0
        }
        val peak=(from..to).maxByOrNull{scores[it]}?:return null
        if(scores[peak]<300.0)return null
        return peak
    }
    private fun findBounds(p:IntArray,w:Int,h:Int):CardBounds? {
        val left=consistentEdge(p,w,h,vertical=false,first=true)?:return null
        val right=consistentEdge(p,w,h,vertical=false,first=false)?:return null
        val top=consistentEdge(p,w,h,vertical=true,first=true)?:return null
        val bottom=consistentEdge(p,w,h,vertical=true,first=false)?:return null
        if(right<=left||bottom<=top)return null
        val ratio=(right-left).toDouble()/w*1.38/((bottom-top).toDouble()/h)
        if(ratio !in 1.20..2.20)return null
        return CardBounds(left.toFloat()/w,top.toFloat()/h,
            right.toFloat()/w,bottom.toFloat()/h)
    }
    private fun fieldSharp(p:IntArray,w:Int,h:Int,b:CardBounds,z:FieldZone):Boolean {
        val x0=((b.left+(b.right-b.left)*z.x0)*w).toInt().coerceIn(2,w-3)
        val x1=((b.left+(b.right-b.left)*z.x1)*w).toInt().coerceIn(2,w-3)
        val y0=((b.top+(b.bottom-b.top)*z.y0)*h).toInt().coerceIn(2,h-3)
        val y1=((b.top+(b.bottom-b.top)*z.y1)*h).toInt().coerceIn(2,h-3)
        if(x1-x0<5||y1-y0<5)return false
        var edges=0;var clipped=0;var total=0
        for(y in y0 until y1)for(x in x0 until x1){
            val here=p[y*w+x]
            val v=abs(here-p[y*w+x-1])+abs(here-p[(y-1)*w+x])
            if(v>27)edges++
            if(here>247)clipped++
            total++
        }
        return total>30&&edges>=maxOf(5,total/35)&&clipped<total/4
    }

    fun inspect(pixels:IntArray,cols:Int,rows:Int,previous:IntArray?):FrameAssessment {
        if(cols<64||rows<45||pixels.size!=cols*rows||
            pixels.any{it !in 0..255})return FrameAssessment(CaptureHint.WAIT)
        var sum=0.0;var squares=0.0;var clipped=0
        pixels.forEach {v->
            sum+=v;squares+=v.toDouble()*v
            if(v>=247)clipped++
        }
        val mean=sum/pixels.size
        val spread=sqrt((squares/pixels.size-mean*mean).coerceAtLeast(0.0))
        if(mean<48)return FrameAssessment(CaptureHint.DARK)
        if(mean>230||clipped.toDouble()/pixels.size>.28)
            return FrameAssessment(CaptureHint.GLARE)
        if(previous!=null&&previous.size==pixels.size){
            var delta=0L
            for(i in pixels.indices)delta+=abs(pixels[i]-previous[i])
            if(delta.toDouble()/pixels.size>24)return FrameAssessment(CaptureHint.MOVING)
        }
        if(spread<14)return FrameAssessment(CaptureHint.TOO_FAR)
        val bounds=findBounds(pixels,cols,rows)?:return FrameAssessment(CaptureHint.WAIT)
        val width=bounds.right-bounds.left
        val height=bounds.bottom-bounds.top
        if(width>.94f||height>.93f||bounds.left<.025f||bounds.top<.025f||
           bounds.right>.975f||bounds.bottom>.975f)
            return FrameAssessment(CaptureHint.MOVE_BACK,bounds)
        if(width<.61f||height<.52f)return FrameAssessment(CaptureHint.TOO_FAR,bounds)
        val quality=zones.map{fieldSharp(pixels,cols,rows,bounds,it)}
        val labelAndBirthClear=quality.drop(1).all{it}
        val hint=when {
            quality.all{it}->CaptureHint.GOOD
            labelAndBirthClear->CaptureHint.CIN_UNCLEAR
            else->CaptureHint.FOCUS
        }
        return FrameAssessment(hint,bounds,quality)
    }
    fun evaluate(pixels:IntArray,cols:Int,rows:Int,previous:IntArray?):CaptureHint =
        inspect(pixels,cols,rows,previous).hint
}
