package tn.malak.app.ui

import kotlin.math.abs
import kotlin.math.sqrt

/** Lightweight luminance-only guidance. No OCR, face recognition, frame
 * retention, remote calls, or false assertions that a card was detected.
 */
internal enum class CaptureHint { WAIT, GOOD, DARK, GLARE, TOO_FAR, MOVE_BACK, MOVING }

internal object CaptureFrameAssessment {
    fun evaluate(pixels:IntArray,cols:Int,rows:Int,previous:IntArray?):CaptureHint {
        if(cols<16||rows<12||pixels.size!=cols*rows||
            pixels.any{it !in 0..255})return CaptureHint.WAIT
        var sum=0.0;var squares=0.0;var clipped=0
        pixels.forEach {v->
            sum+=v;squares+=v.toDouble()*v
            if(v>=247)clipped++
        }
        val mean=sum/pixels.size
        val spread=sqrt((squares/pixels.size-mean*mean).coerceAtLeast(0.0))
        if(mean<58)return CaptureHint.DARK
        if(mean>231||clipped.toDouble()/pixels.size>.32)return CaptureHint.GLARE
        if(previous!=null&&previous.size==pixels.size){
            var delta=0L
            for(i in pixels.indices)delta+=abs(pixels[i]-previous[i])
            if(delta.toDouble()/pixels.size>24)return CaptureHint.MOVING
        }
        if(spread<16)return CaptureHint.TOO_FAR
        var edges=0L;var count=0
        for(r in 1 until rows-1)for(c in 1 until cols-1){
            val at=r*cols+c
            edges+=abs(pixels[at]-pixels[at-1])
            edges+=abs(pixels[at]-pixels[at-cols])
            count+=2
        }
        val detail=edges.toDouble()/count
        if(detail<9.0)return CaptureHint.TOO_FAR
        // Three pronounced transitions touching the sampling boundary suggest
        // a possibly cropped card; the final OCR is the authoritative gate.
        var left=0;var right=0;var top=0;var bottom=0
        for(r in 1 until rows-1){
            left+=abs(pixels[r*cols]-pixels[r*cols+1])
            right+=abs(pixels[(r+1)*cols-1]-pixels[(r+1)*cols-2])
        }
        for(c in 1 until cols-1){
            top+=abs(pixels[c]-pixels[cols+c])
            bottom+=abs(pixels[(rows-1)*cols+c]-pixels[(rows-2)*cols+c])
        }
        val outer=listOf(left.toDouble()/(rows-2),right.toDouble()/(rows-2),
            top.toDouble()/(cols-2),bottom.toDouble()/(cols-2))
        if(outer.count{it>maxOf(34.0,detail*1.65)}>=3)return CaptureHint.MOVE_BACK
        return CaptureHint.GOOD
    }
}
