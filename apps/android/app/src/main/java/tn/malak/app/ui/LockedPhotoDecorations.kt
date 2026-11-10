package tn.malak.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import tn.malak.app.R

/**
 * Recover the exact original coastal villa + upper floral details from the
 * user's SHA-locked screenshot, with feathered edges. No generated imagery.
 * Everything outside these photo regions stays an interactive Compose layer.
 *
 * The full reference image must NEVER become the app interface itself:
 * only carefully selected decorative image pixels are used here.
 */
@Composable
fun LockedPhotoDecorations(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val overlay = remember(context) {
        val original = BitmapFactory.decodeResource(
            context.resources, R.drawable.malak_approved_reference
        )
        val output = Bitmap.createBitmap(
            original.width, original.height, Bitmap.Config.ARGB_8888
        )
        val width = original.width
        val limitY = minOf(233, original.height)
        fun softRect(
            x: Int, y: Int, left: Int, top: Int, right: Int, bottom: Int,
            rightEdge: Boolean = false
        ): Float {
            if (x < left || x > right || y < top || y > bottom) return 0f
            val feather = 8f
            val fadeLeft = ((x-left) / feather).coerceIn(0f,1f)
            val fadeTop = ((y-top) / feather).coerceIn(0f,1f)
            val fadeBottom = ((bottom-y) / feather).coerceIn(0f,1f)
            val fadeRight = if(rightEdge) 1f else ((right-x) / feather).coerceIn(0f,1f)
            return minOf(fadeLeft, fadeTop, fadeBottom, fadeRight)
        }
        for (y in 0 until limitY) {
            for (x in 0 until width) {
                val pixel = original.getPixel(x,y)
                val r = AndroidColor.red(pixel)
                val g = AndroidColor.green(pixel)
                val b = AndroidColor.blue(pixel)

                // Strong magenta flowers and real green leaves only, stopping
                // before the red logo or system status symbols begin.
                var flower = 0f
                if (x < width * .66f && y < 73) {
                    val petal = r > 125 && r > g+40 && b > g+9
                    val leaf = g > r+11 && g > b+3 && g > 48
                    if (petal || leaf) {
                        flower = ((73-y) / 9f).coerceIn(0f,1f) *
                            ((width*.66f-x)/10f).coerceIn(0f,1f)
                    }
                }

                // Preserve the right-hand white Tunisian architecture while
                // avoiding the source screenshot's status icons, search,
                // category buttons and other baked interactive widgets.
                val villa = maxOf(
                    softRect(x,y,507,76,width,231,rightEdge=true),
                    softRect(x,y,476,120,510,164),
                    softRect(x,y,441,131,477,159)
                )
                // The tiny real Tunisian flag under the wordmark is an art
                // detail from the approved screenshot, never a text/emoji copy.
                val flag = if (x in 223..245 && y in 138..153) 1f else 0f
                val opacity = (maxOf(flower,villa,flag)*255f).toInt().coerceIn(0,255)
                if(opacity!=0) {
                    output.setPixel(x,y,AndroidColor.argb(opacity,r,g,b))
                }
            }
        }
        original.recycle()
        output.asImageBitmap()
    }
    Image(
        bitmap=overlay, contentDescription=null, contentScale=ContentScale.FillBounds,
        modifier=modifier
    )
}
