package tn.malak.app.ui.explore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal const val WorldGlobeBlendEnd = 1.82

@Composable
internal fun WorldGlobe(center: DiscoveryPoint, zoom: Double, modifier: Modifier) {
    val context = LocalContext.current
    val land = remember(context) {
        // Natural Earth 1:110m public-domain land, rasterized in longitude/latitude.
        val bitmap = context.assets.open("maps/natural-earth-land.png").use(BitmapFactory::decodeStream)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        LandTexture(bitmap.width, bitmap.height, pixels)
    }
    val latitude = (center.latitude * 2).roundToInt() / 2.0
    val longitude = (center.longitude * 2).roundToInt() / 2.0
    val image by produceState<ImageBitmap?>(null, land, latitude, longitude) {
        value = withContext(Dispatchers.Default) { renderGlobe(land, latitude, longitude).asImageBitmap() }
    }
    val alpha = ((WorldGlobeBlendEnd - zoom) / .35).coerceIn(0.0, 1.0).toFloat()
    val frame = image ?: return
    GlobeSky(modifier.graphicsLayer { this.alpha = alpha }) {
        Canvas(Modifier.matchParentSize()) {
            val diameter = (minOf(size.width, size.height) * .71 * 2.0.pow(zoom - WorldZoom)).roundToInt()
            val left = (size.width - diameter).div(2).roundToInt()
            val top = (size.height - diameter).div(2).roundToInt()
            drawImage(frame, dstOffset = IntOffset(left, top), dstSize = IntSize(diameter, diameter))
        }
    }
}

private data class LandTexture(val width: Int, val height: Int, val pixels: IntArray)

private fun renderGlobe(land: LandTexture, latitude: Double, longitude: Double): Bitmap {
    val resolution = 208
    val pixels = IntArray(resolution * resolution)
    val centerLatitude = Math.toRadians(latitude)
    val lonFraction = longitude / 360 + .5
    val north = sin(centerLatitude)
    val forward = cos(centerLatitude)
    for (y in 0 until resolution) {
        val vertical = 1 - (y + .5) * 2 / resolution
        for (x in 0 until resolution) {
            val horizontal = (x + .5) * 2 / resolution - 1
            val squaredDistance = horizontal * horizontal + vertical * vertical
            if (squaredDistance >= 1) continue

            val depth = sqrt(1 - squaredDistance)
            val latitudeAtPoint = asin((vertical * forward + depth * north).coerceIn(-1.0, 1.0))
            val longitudeAtPoint = atan2(horizontal, depth * forward - vertical * north)
            val u = ((lonFraction + longitudeAtPoint / (2 * PI) + 1) % 1 * land.width).toInt()
            val v = ((.5 - latitudeAtPoint / PI) * land.height).toInt().coerceIn(0, land.height - 1)
            val landAmount = (land.pixels[v * land.width + u] and 255) / 255.0
            val light = (.76 + .24 * depth - .16 * horizontal + .12 * vertical).coerceIn(.58, 1.07)
            val rim = (1 - depth).pow(7) * .34
            val alpha = ((1 - squaredDistance) * resolution / 2).coerceIn(0.0, 1.0)

            fun channel(water: Int, ground: Int, atmosphere: Int): Int {
                val lit = (water + (ground - water) * landAmount) * light
                return (lit + (atmosphere - lit) * rim).roundToInt().coerceIn(0, 255)
            }
            pixels[y * resolution + x] = android.graphics.Color.argb(
                (255 * alpha).roundToInt(), channel(103, 234, 182),
                channel(175, 229, 223), channel(208, 207, 247)
            )
        }
    }
    return Bitmap.createBitmap(pixels, resolution, resolution, Bitmap.Config.ARGB_8888)
}
