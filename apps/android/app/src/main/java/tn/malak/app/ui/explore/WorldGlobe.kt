package tn.malak.app.ui.explore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
internal fun WorldGlobe(center: DiscoveryPoint, zoom: Double, modifier: Modifier, spin: Boolean,
    onStopSpin: () -> Unit, onSpinLongitude: (Double) -> Unit) {
    val context = LocalContext.current
    val land = remember(context) {
        // Natural Earth 1:110m public-domain land, rasterized in longitude/latitude.
        val bitmap = context.assets.open("maps/natural-earth-land.png").use(BitmapFactory::decodeStream)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val borders = context.assets.open("maps/world-borders.png").use(BitmapFactory::decodeStream)
        require(borders.width == bitmap.width && borders.height == bitmap.height)
        val lines = IntArray(pixels.size)
        borders.getPixels(lines, 0, borders.width, 0, 0, borders.width, borders.height)
        LandTexture(bitmap.width, bitmap.height, pixels, lines)
    }
    val latitude = (center.latitude * 2).roundToInt() / 2.0
    val longitude = (center.longitude * 2).roundToInt() / 2.0
    val spinPosition by rememberUpdatedState(onSpinLongitude)
    val stopSpin by rememberUpdatedState(onStopSpin)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The globe is interactive content, not a decorative Android animator.
    // Continue turning even when the device's animation-duration scale is zero.
    val animated = spin
    val projection by produceState<GlobeProjection?>(null, latitude) {
        value = withContext(Dispatchers.Default) { GlobeProjection(latitude) }
    }
    val image by produceState<GlobeFrame?>(null, land, projection, longitude, animated, lifecycle) {
        val sphere = projection ?: return@produceState
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val start = SystemClock.uptimeMillis()
            do {
                val seconds = (SystemClock.uptimeMillis() - start) / 1000.0
                val meridian = if (animated) longitude + (seconds * 4.5) % 360 else longitude
                val pulse = if (animated) ((1 - cos(seconds * 2.6)) / 2).toFloat() else .35f
                value = withContext(Dispatchers.Default) {
                    GlobeFrame(sphere.render(land, meridian).asImageBitmap(),
                        globeSurfacePoint(DiscoveryPoint(latitude, meridian), InitialCenter), pulse)
                }
                if (animated) {
                    spinPosition(((meridian + 540) % 360) - 180)
                    delay(75)
                }
            } while (animated)
        }
    }
    val alpha = ((WorldGlobeBlendEnd - zoom) / .35).coerceIn(0.0, 1.0).toFloat()
    val tapToStop = if (spin) Modifier.pointerInput(Unit) {
        detectTapGestures(onTap = { stopSpin() })
    } else Modifier
    GlobeSky(modifier.graphicsLayer { this.alpha = alpha }.then(tapToStop)) {
        Canvas(Modifier.matchParentSize()) {
            val frame = image ?: return@Canvas
            val diameter = (minOf(size.width, size.height) * .71 * 2.0.pow(zoom - WorldZoom)).roundToInt()
            val left = (size.width - diameter).div(2).roundToInt()
            val top = (size.height - diameter).div(2).roundToInt()
            drawImage(frame.image, dstOffset = IntOffset(left, top), dstSize = IntSize(diameter, diameter))
            frame.beacon?.let { (x, y) ->
                val point = Offset(size.width / 2 + (x * diameter / 2).toFloat(),
                    size.height / 2 - (y * diameter / 2).toFloat())
                drawCircle(Color(0xFFAD173F), (6f + 5f * frame.pulse).dp.toPx(), point,
                    alpha = .26f * (1f - .7f * frame.pulse))
                drawCircle(Color.White, 4.dp.toPx(), point)
                drawCircle(Color(0xFFAE1740), 2.7.dp.toPx(), point)
            }
        }
    }
}

private data class LandTexture(val width: Int, val height: Int, val pixels: IntArray, val borders: IntArray)

private data class GlobeFrame(val image: ImageBitmap, val beacon: Pair<Double, Double>?, val pulse: Float)

internal fun globeSurfacePoint(center: DiscoveryPoint, target: DiscoveryPoint): Pair<Double, Double>? {
    val lat = Math.toRadians(target.latitude)
    val tilt = Math.toRadians(center.latitude)
    val delta = Math.toRadians(target.longitude - center.longitude)
    val depth = sin(tilt) * sin(lat) + cos(tilt) * cos(lat) * cos(delta)
    if (depth <= 0) return null
    return cos(lat) * sin(delta) to cos(tilt) * sin(lat) - sin(tilt) * cos(lat) * cos(delta)
}

// Rotation changes only the longitude lookup. Cache the spherical projection and lighting.
private class GlobeProjection(latitude: Double) {
    private val size = 240
    private val u = FloatArray(size * size)
    private val v = FloatArray(size * size)
    private val ocean = IntArray(size * size)
    private val border = IntArray(size * size)
    private val earth = IntArray(size * size)

    init {
        val tilt = Math.toRadians(latitude)
        for (y in 0 until size) for (x in 0 until size) {
            val vertical = 1 - (y + .5) * 2 / size
            val horizontal = (x + .5) * 2 / size - 1
            val r2 = horizontal * horizontal + vertical * vertical
            if (r2 >= 1) continue
            val i = y * size + x
            val depth = sqrt(1 - r2)
            val lat = asin((vertical * cos(tilt) + depth * sin(tilt)).coerceIn(-1.0, 1.0))
            u[i] = (.5 + atan2(horizontal, depth * cos(tilt) - vertical * sin(tilt)) / (2 * PI)).toFloat()
            v[i] = (.5 - lat / PI).toFloat()
            val light = (.76 + .24 * depth - .16 * horizontal + .12 * vertical).coerceIn(.58, 1.07)
            val rim = (1 - depth).pow(7) * .34
            val alpha = ((1 - r2) * size / 2).coerceIn(0.0, 1.0)
            fun channel(base: Int, air: Int) = (base * light * (1 - rim) + air * rim).roundToInt().coerceIn(0, 255)
            fun color(r: Int, g: Int, b: Int) = android.graphics.Color.argb((alpha * 255).roundToInt(),
                channel(r, 182), channel(g, 223), channel(b, 247))
            ocean[i] = color(103, 175, 208)
            earth[i] = color(234, 229, 207)
            border[i] = color(175, 179, 159)
        }
    }

    fun render(land: LandTexture, longitude: Double): Bitmap {
        val offset = ((longitude / 360 % 1 + 1) % 1).toFloat()
        val pixels = IntArray(size * size)
        for (i in pixels.indices) {
            if (ocean[i] == 0) continue
            val x = ((u[i] + offset) * land.width).toInt() % land.width
            val y = (v[i] * land.height).toInt().coerceIn(0, land.height - 1)
            val index = y * land.width + x
            pixels[i] = when {
                (land.pixels[index] and 255) < 128 -> ocean[i]
                (land.borders[index] and 255) > 76 -> border[i]
                else -> earth[i]
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
