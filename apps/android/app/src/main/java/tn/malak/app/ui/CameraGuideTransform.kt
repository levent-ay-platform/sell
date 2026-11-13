package tn.malak.app.ui

import kotlin.math.min

/** Sensor-space rect (pixel coordinates, right/bottom exclusive). */
internal data class PixelRect(
    val left:Int,val top:Int,val right:Int,val bottom:Int
){
    val width:Int get()=right-left
    val height:Int get()=bottom-top
}

/** Physical camera transforms, independent of Arabic RTL layout direction.
 *
 * These coordinates are for ImageAnalysis quality sampling ONLY. Never use
 * an analysis rectangle to crop ImageCapture's JPEG: CameraX can choose
 * a different resolution/rotation even with a shared ViewPort.
 */
internal object CameraGuideTransform {
    fun rotatedViewport(rawWidth:Int,rawHeight:Int,
                        degrees:Int,crop:PixelRect):PixelRect? {
        if(rawWidth<64||rawHeight<64||
           crop.left<0||crop.top<0||crop.right>rawWidth||
           crop.bottom>rawHeight||crop.width<=0||crop.height<=0)
            return null
        val mapped=when(degrees){
            0->crop
            90->PixelRect(rawHeight-crop.bottom,crop.left,
                          rawHeight-crop.top,crop.right)
            180->PixelRect(rawWidth-crop.right,rawHeight-crop.bottom,
                           rawWidth-crop.left,rawHeight-crop.top)
            270->PixelRect(crop.top,rawWidth-crop.right,
                           crop.bottom,rawWidth-crop.left)
            else->return null
        }
        if(mapped.width<64||mapped.height<64)return null
        return mapped
    }

    fun guideRect(viewport:PixelRect):PixelRect? {
        if(viewport.width<64||viewport.height<64)return null
        val width=(viewport.width*.84f).toInt()
        val height=min((width/1.585f).toInt(),
                       (viewport.height*.70f).toInt())
        if(width<64||height<45)return null
        val left=viewport.left+(viewport.width-width)/2
        val top=viewport.top+(viewport.height-height)/2
        return PixelRect(left,top,left+width,top+height)
    }
}
