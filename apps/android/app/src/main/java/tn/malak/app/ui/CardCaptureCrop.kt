package tn.malak.app.ui

import kotlin.math.max
import kotlin.math.min

internal data class CardPixelCrop(val left:Int,val top:Int,val width:Int,val height:Int)

/** Crop the detected FRONT card with margins; never crop the central
 * overlay by assumption when the user's real card is off-centre.
 * Bounded camera geometry is guidance only, not identity verification.
 */
internal object CardCaptureCrop {
    private const val ASPECT=1.585f
    fun plan(width:Int,height:Int,detected:CardBounds?):CardPixelCrop {
        require(width>=80&&height>=80)
        val maximumWidth=min(width*.97f,height*.97f*ASPECT)
        val fallbackWidth=maximumWidth.toInt().coerceAtLeast(64)
        val fallbackHeight=(fallbackWidth/ASPECT).toInt().coerceAtLeast(40)
        fun centered(x:Float,y:Float,cw:Int,ch:Int):CardPixelCrop {
            val left=(x-cw/2f).toInt().coerceIn(0,width-cw)
            val top=(y-ch/2f).toInt().coerceIn(0,height-ch)
            return CardPixelCrop(left,top,cw,ch)
        }
        val fallback=centered(width/2f,height/2f,fallbackWidth,fallbackHeight)
        if(detected==null||!listOf(detected.left,detected.top,
                detected.right,detected.bottom).all{it.isFinite()}||
            detected.left<0f||detected.top<0f||detected.right>1f||
            detected.bottom>1f||detected.left>=detected.right||
            detected.top>=detected.bottom)return fallback

        val spanW=(detected.right-detected.left)*width
        val spanH=(detected.bottom-detected.top)*height
        // Require a substantial frontal card, not a random background edge.
        if(spanW<width*.25f||spanH<height*.16f)return fallback
        val x=(detected.left+detected.right)*width/2f
        val y=(detected.top+detected.bottom)*height/2f
        val needed=max(spanW,spanH*ASPECT)*1.14f
        val target=min(maximumWidth,needed).toInt().coerceAtLeast(64)
        val targetHeight=(target/ASPECT).toInt().coerceAtLeast(40)
        val proposed=centered(x,y,target,targetHeight)
        val margin=0.024f
        val dx=margin*width;val dy=margin*height
        // Never return a bounding-box crop that chops off printed edges.
        val contains=(proposed.left<=detected.left*width-dx&&
            proposed.top<=detected.top*height-dy&&
            proposed.left+proposed.width>=detected.right*width+dx&&
            proposed.top+proposed.height>=detected.bottom*height+dy)
        return if(contains)proposed else fallback
    }
}
