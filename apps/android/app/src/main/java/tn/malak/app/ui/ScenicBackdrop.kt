package tn.malak.app.ui

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Safe illustrated QA placeholder. Never represent this as live aerial tiles or legal parcel geometry.
 * The real native map/GIS engine replaces this composition while preserving overlays and responsive layout.
 */
@Composable
fun ScenicBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFFE6F3FF), Color(0xFF8DD6F1), Color(0xFF0B91C9))))

        val sea = Path().apply {
            moveTo(0.48f * w, 0.23f * h)
            cubicTo(0.72f * w, 0.24f * h, 0.63f * w, 0.52f * h, w, 0.61f * h)
            lineTo(w, h)
            lineTo(0.48f * w, h)
            close()
        }
        drawPath(sea, Brush.verticalGradient(listOf(Color(0xFF65CBE4), Color(0xFF056A9B))))

        val land = Path().apply {
            moveTo(0f, 0.28f * h)
            cubicTo(0.35f * w, 0.29f * h, 0.60f * w, 0.31f * h, 0.50f * w, 0.41f * h)
            cubicTo(0.36f * w, 0.52f * h, 0.68f * w, 0.57f * h, 0.55f * w, 0.67f * h)
            cubicTo(0.51f * w, 0.76f * h, 0.45f * w, 0.87f * h, 0f, h)
            close()
        }
        drawPath(land, Brush.verticalGradient(listOf(Color(0xFF88B37F), Color(0xFFBDCA89), Color(0xFF9BBA73))))

        val shore = Path().apply {
            moveTo(0.48f * w, 0.23f * h)
            cubicTo(0.72f * w, 0.24f * h, 0.63f * w, 0.52f * h, w, 0.61f * h)
        }
        drawPath(shore, color = Color.White.copy(alpha = 0.74f), style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
        val road = Path().apply {
            moveTo(0f, 0.73f * h)
            cubicTo(0.23f * w, 0.64f * h, 0.33f * w, 0.67f * h, 0.44f * w, 0.44f * h)
        }
        drawPath(road, color = Color(0xFFDDD5B5), style = Stroke(width = 14.dp.toPx()))
        drawPath(road, color = Color.White.copy(alpha = 0.8f), style = Stroke(width = 2.dp.toPx()))

        // Simplified white Tunisian houses; purely decorative, NOT a geographic map.
        for (i in 0..6) {
            for (j in 0..6) {
                val x = w * (0.04f + i * 0.066f)
                val y = h * (0.33f + j * 0.057f + i * 0.007f)
                if (x < w * 0.48f && y < h * 0.67f) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.82f),
                        topLeft = Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(12.dp.toPx(), 9.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                    drawRect(
                        color = Color(0xFF4A86A2).copy(alpha = .7f),
                        topLeft = Offset(x + 3.dp.toPx(), y + 4.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(3.dp.toPx(), 4.dp.toPx())
                    )
                }
            }
        }

        // DEMO geometry: an illustrated highlight; never show as a verified cadastral boundary.
        val plot = Path().apply {
            moveTo(w * .24f, h * .65f)
            lineTo(w * .50f, h * .625f)
            lineTo(w * .63f, h * .71f)
            lineTo(w * .31f, h * .745f)
            close()
        }
        drawPath(plot, Color(0x33D21C36))
        drawPath(plot, Brand.TunisianRed, style = Stroke(width = 3.dp.toPx()))
        drawCircle(Brand.Crimson, radius = 14.dp.toPx(), center = Offset(.43f * w, .65f * h))
        drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(.43f * w, .65f * h))
    }
}

@Composable
fun CoastalThumbnail(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFA4D8F8), Color(0xFF90D9EE), Color(0xFF0877A9))))
        drawRect(Color(0xFF1B98BA), topLeft = Offset(0f,size.height * .51f),
            size = androidx.compose.ui.geometry.Size(size.width,size.height *.49f))
        val hill = Path().apply {
            moveTo(0f,size.height *.7f)
            quadraticTo(size.width *.2f,size.height *.53f,size.width *.4f,size.height *.75f)
            quadraticTo(size.width *.70f,size.height *.65f,size.width,size.height *.88f)
            lineTo(size.width,size.height);lineTo(0f,size.height);close()
        }
        drawPath(hill, Color(0xFF91A970))
        for(i in 1..8) {
            drawCircle(Color(0xFF496C3F),radius=(3+i%2*2).dp.toPx(),
                center=Offset(size.width*i/10f,size.height*(.76f+(i%3)*.04f)))
        }
    }
}

/** Soft single-layer floral motion. Reduced motion, low RAM and battery saver disable animation. */
@Composable
fun BougainvilleaSway(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val lowPower = remember(ctx) {
        val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val memory = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val reduced = runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        (power?.isPowerSaveMode == true) || (memory?.isLowRamDevice == true) || reduced
    }
    val angle = if (!lowPower) {
        val inf = rememberInfiniteTransition(label = "light-wind")
        val current by inf.animateFloat(
            initialValue = -1.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bougainvillea-sway"
        )
        current
    } else 0f

    Canvas(modifier.graphicsLayer {
        rotationZ = angle
        transformOrigin = TransformOrigin(1f, 0f)
    }) {
        val x = size.width
        val y = size.height
        drawLine(Color(0xFF517E52), Offset(x*.98f,0f), Offset(x*.35f,y*.78f), 2.dp.toPx(), cap=StrokeCap.Round)
        drawLine(Color(0xFF517E52), Offset(x*.82f,0f), Offset(x*.12f,y*.40f), 2.dp.toPx(), cap=StrokeCap.Round)
        listOf(Offset(x*.36f,y*.72f),Offset(x*.40f,y*.62f),Offset(x*.55f,y*.45f),
            Offset(x*.23f,y*.3f),Offset(x*.69f,y*.24f),Offset(x*.46f,y*.16f)
        ).forEachIndexed { n,p ->
            val colors = listOf(Brand.TunisianRed,Color(0xFFE83158),Color(0xFFD5194B))
            for(k in 0..4){
                val a = k*1.2566
                drawCircle(
                    colors[(n+k)%colors.size].copy(alpha=.78f),
                    radius=7.dp.toPx(),
                    center=Offset(p.x+(kotlin.math.cos(a)*5.dp.toPx()).toFloat(),
                        p.y+(kotlin.math.sin(a)*5.dp.toPx()).toFloat())
                )
            }
            drawCircle(Color(0xFFFFD69A),radius=2.dp.toPx(),center=p)
        }
    }
}
