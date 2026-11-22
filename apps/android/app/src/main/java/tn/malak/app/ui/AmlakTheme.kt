package tn.malak.app.ui

import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Amlak light design system.
 *
 * Every Material 3 text, input, control, sheet, snackbar and error must receive a
 * complete semantic foreground/background pair. Providing only primary and surface
 * silently inherited lavender/grey defaults and caused inconsistent readability.
 *
 * System sans-serif is used deliberately: Android selects an appropriate Arabic
 * font fallback rather than forcing a Latin font on Arabic script. Do not ship
 * externally sourced font binaries without license and shaping QA.
 */
internal val AmlakColorScheme = lightColorScheme(
    primary = Brand.Crimson,
    onPrimary = Color.White,
    primaryContainer = Brand.Rose,
    onPrimaryContainer = Color(0xFF681026),
    inversePrimary = Color(0xFFFFB3C4),
    secondary = Color(0xFF384651),
    onSecondary = Color.White,
    secondaryContainer = Brand.NeutralContainer,
    onSecondaryContainer = Brand.Ink,
    tertiary = Color(0xFF255B69),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDF1F3),
    onTertiaryContainer = Color(0xFF103943),
    background = Brand.Pearl,
    onBackground = Brand.Ink,
    surface = Brand.Pearl,
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFF1ECEA),
    onSurfaceVariant = Brand.Muted,
    surfaceTint = Brand.Crimson,
    inverseSurface = Brand.Ink,
    inverseOnSurface = Color.White,
    outline = Color(0xFF6E727A),
    outlineVariant = Color(0xFFC0B9BB),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF5F1510),
    scrim = Color(0x99000000)
)

private val TextFamily = FontFamily.SansSerif

internal val AmlakTypography = Typography(
    displayLarge = TextStyle(fontFamily = TextFamily, fontSize = 34.sp, lineHeight = 43.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontFamily = TextFamily, fontSize = 30.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontFamily = TextFamily, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontFamily = TextFamily, fontSize = 26.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = TextFamily, fontSize = 24.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontFamily = TextFamily, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = TextFamily, fontSize = 20.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = TextFamily, fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = TextFamily, fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = TextFamily, fontSize = 16.sp, lineHeight = 25.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = TextFamily, fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = TextFamily, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = TextFamily, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = TextFamily, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = TextFamily, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
)
