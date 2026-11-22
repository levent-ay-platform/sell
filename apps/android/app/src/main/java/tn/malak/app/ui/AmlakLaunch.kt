package tn.malak.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tn.malak.app.R
import kotlin.math.PI
import kotlin.math.sin

private const val LaunchDurationMs = 820

@Composable
internal fun AmlakLaunch(animate: Boolean, content: @Composable (Boolean) -> Unit) {
    var showing by remember { mutableStateOf(animate) }
    Box(Modifier.fillMaxSize()) {
        val contentModifier = if (showing) {
            Modifier.clearAndSetSemantics { hideFromAccessibility() }
        } else {
            Modifier
        }
        // Mount the normal app immediately so the map can start before the entrance finishes.
        Box(Modifier.fillMaxSize().then(contentModifier)) { content(showing) }
        if (showing) LaunchOverlay { showing = false }
    }
}

@Composable
private fun LaunchOverlay(onFinished: () -> Unit) {
    val motion = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        motion.animateTo(1f, tween(durationMillis = LaunchDurationMs, easing = LinearEasing))
        onFinished()
    }

    val iconShape = RoundedCornerShape(23.dp)
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            alpha = 1f - FastOutSlowInEasing.transform(phase(motion.value, .72f, 1f))
        }.background(Brand.Pearl).semantics { contentDescription = APP_BRAND_NAME }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(320.dp)) {
            val spread = FastOutSlowInEasing.transform(phase(motion.value, .08f, .76f))
            val linesAlpha = spread * .19f
            ParcelLines.forEach { outline ->
                val path = Path()
                outline.forEachIndexed { i, point ->
                    val x = size.width * (.5f + (point.x - .5f) * (.76f + .24f * spread))
                    val y = size.height * (.5f + (point.y - .5f) * (.76f + .24f * spread))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Brand.Muted, alpha = linesAlpha, style = Stroke(1.dp.toPx()))
            }
        }
        Box(
            Modifier.size(104.dp).graphicsLayer {
                val p = motion.value
                val tilt = sin(PI * phase(p, 0f, .70f)).toFloat()
                rotationX = 13f * tilt
                rotationY = -9f * tilt
                cameraDistance = 640.dp.toPx()
                val scale = .94f + .10f * FastOutSlowInEasing.transform(phase(p, 0f, .65f))
                scaleX = scale
                scaleY = scale
                translationY = -4.dp.toPx() * tilt
                shape = iconShape
                clip = true
                shadowElevation = (8f + 6f * tilt).dp.toPx()
            }
        ) {
            Image(painterResource(R.drawable.amlak_launcher_icon), contentDescription = null,
                modifier = Modifier.fillMaxSize())
            Canvas(Modifier.matchParentSize()) {
                val sweep = phase(motion.value, .24f, .66f)
                val alpha = .32f * sin(PI * sweep).toFloat()
                if (alpha > 0f) {
                    val x = size.width * (-.55f + sweep * 2.10f)
                    drawRect(Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = alpha), Color.Transparent),
                        start = Offset(x, size.height * .18f),
                        end = Offset(x + size.width * .42f, size.height * .35f)
                    ))
                }
            }
        }
    }
}

private fun phase(value: Float, start: Float, end: Float) =
    ((value - start) / (end - start)).coerceIn(0f, 1f)

private val ParcelLines = listOf(
    listOf(Offset(.04f, .37f), Offset(.25f, .29f), Offset(.31f, .09f), Offset(.61f, .05f)),
    listOf(Offset(.25f, .29f), Offset(.40f, .36f), Offset(.57f, .13f), Offset(.76f, .18f), Offset(.96f, .13f)),
    listOf(Offset(.04f, .64f), Offset(.20f, .56f), Offset(.25f, .29f)),
    listOf(Offset(.20f, .56f), Offset(.38f, .67f), Offset(.33f, .88f), Offset(.11f, .94f)),
    listOf(Offset(.57f, .13f), Offset(.69f, .39f), Offset(.91f, .34f)),
    listOf(Offset(.69f, .39f), Offset(.78f, .63f), Offset(.97f, .69f)),
    listOf(Offset(.38f, .67f), Offset(.61f, .75f), Offset(.78f, .63f)),
    listOf(Offset(.61f, .75f), Offset(.69f, .94f), Offset(.92f, .87f), Offset(.78f, .63f))
)
