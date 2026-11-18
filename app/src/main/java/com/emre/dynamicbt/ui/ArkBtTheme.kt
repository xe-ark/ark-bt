package com.emre.dynamicbt.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Brand tokens
// ---------------------------------------------------------------------------

/** Primary brand crimson. Reserved for accents, selection and the crest. */
val ArkCrimson = Color(0xFFFF3B5C)

/** Deeper crimson used on light surfaces where [ArkCrimson] would vibrate. */
val ArkCrimsonDeep = Color(0xFFC81E45)

/** Very deep crimson used for gradients / glows. */
val CrimsonDeep = Color(0xFF7A1026)

// Backwards-compatible aliases used by the crest logo.
val CrimsonPrimary = ArkCrimson
val CrimsonSecondary = Color(0xFFFF6B81)

@Immutable
data class ArkColors(
    val isDark: Boolean,
    val backdropBg: Color,
    val surfaceBg: Color,
    val raisedBg: Color,
    val sunkenBg: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentContainer: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val heroTop: Color,
    val heroBottom: Color,
)

private val DarkColors = ArkColors(
    isDark = true,
    backdropBg = Color(0xFF08080A),
    surfaceBg = Color(0xFF121215),
    raisedBg = Color(0xFF1A1A1F),
    sunkenBg = Color(0xFF0D0D10),
    border = Color(0xFF26262E),
    borderStrong = Color(0xFF34343E),
    textPrimary = Color(0xFFF4F4F6),
    textSecondary = Color(0xFF9C9CA8),
    textTertiary = Color(0xFF6B6B78),
    accent = ArkCrimson,
    accentSoft = Color(0xFFFF8194),
    accentContainer = Color(0xFF2E1017),
    onAccent = Color(0xFF2A0610),
    success = Color(0xFF35D399),
    warning = Color(0xFFFFB547),
    danger = Color(0xFFFF5C74),
    heroTop = Color(0xFF161216),
    heroBottom = Color(0xFF121215),
)

private val LightColors = ArkColors(
    isDark = false,
    backdropBg = Color(0xFFF6F6F8),
    surfaceBg = Color(0xFFFFFFFF),
    raisedBg = Color(0xFFF0F0F4),
    sunkenBg = Color(0xFFE8E8EE),
    border = Color(0xFFE1E1E8),
    borderStrong = Color(0xFFC9C9D4),
    textPrimary = Color(0xFF141418),
    textSecondary = Color(0xFF5B5B67),
    textTertiary = Color(0xFF86868F),
    accent = ArkCrimsonDeep,
    accentSoft = Color(0xFFB01238),
    accentContainer = Color(0xFFFDE8EC),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF0F9D63),
    warning = Color(0xFFB4690E),
    danger = Color(0xFFC8102E),
    heroTop = Color(0xFFFFF5F7),
    heroBottom = Color(0xFFFFFFFF),
)

val LocalArkColors = staticCompositionLocalOf { DarkColors }

// ---------------------------------------------------------------------------
// Material 3 colour schemes (kept in sync with ArkColors so M3 components —
// Switch, Slider, buttons — automatically match the refreshed palette)
// ---------------------------------------------------------------------------

private val ArkDarkColorScheme = darkColorScheme(
    primary = ArkCrimson,
    onPrimary = Color(0xFF2A0610),
    primaryContainer = DarkColors.accentContainer,
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFFFF8194),
    onSecondary = Color(0xFF2A0610),
    secondaryContainer = Color(0xFF24161B),
    onSecondaryContainer = Color(0xFFFFD0D8),
    tertiary = DarkColors.warning,
    background = DarkColors.backdropBg,
    onBackground = DarkColors.textPrimary,
    surface = DarkColors.surfaceBg,
    onSurface = DarkColors.textPrimary,
    surfaceVariant = DarkColors.raisedBg,
    onSurfaceVariant = DarkColors.textSecondary,
    surfaceContainer = DarkColors.raisedBg,
    surfaceContainerHigh = Color(0xFF22222A),
    surfaceContainerHighest = Color(0xFF2A2A33),
    outline = DarkColors.borderStrong,
    outlineVariant = DarkColors.border,
    error = DarkColors.danger,
    onError = Color(0xFF2A0610),
)

private val ArkLightColorScheme = lightColorScheme(
    primary = ArkCrimsonDeep,
    onPrimary = Color.White,
    primaryContainer = LightColors.accentContainer,
    onPrimaryContainer = Color(0xFF400010),
    secondary = Color(0xFF8C1231),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDE8EC),
    onSecondaryContainer = Color(0xFF380917),
    tertiary = LightColors.warning,
    background = LightColors.backdropBg,
    onBackground = LightColors.textPrimary,
    surface = LightColors.surfaceBg,
    onSurface = LightColors.textPrimary,
    surfaceVariant = LightColors.raisedBg,
    onSurfaceVariant = LightColors.textSecondary,
    surfaceContainer = LightColors.raisedBg,
    surfaceContainerHigh = Color(0xFFEAEAF0),
    surfaceContainerHighest = Color(0xFFE4E4EC),
    outline = LightColors.borderStrong,
    outlineVariant = LightColors.border,
    error = LightColors.danger,
    onError = Color.White,
)

// ---------------------------------------------------------------------------
// Shape & type scale
// ---------------------------------------------------------------------------

private val ArkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val ArkTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 30.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.8).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 19.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 14.5.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
    ),
)

/** Tabular-figure style so live numbers do not jitter while updating. */
val ArkNumericStyle = TextStyle(
    fontWeight = FontWeight.ExtraBold,
    fontFeatureSettings = "tnum",
    letterSpacing = (-1.4).sp,
)

@Composable
fun ArkBtTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    CompositionLocalProvider(LocalArkColors provides colors) {
        MaterialTheme(
            colorScheme = if (darkTheme) ArkDarkColorScheme else ArkLightColorScheme,
            shapes = ArkShapes,
            typography = ArkTypography,
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// Brand crest
// ---------------------------------------------------------------------------

@Composable
fun ArkCrestLogo(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val pulseAlphaState: State<Float> = if (animated) {
        val transition = rememberInfiniteTransition(label = "ArkCrestAura")
        transition.animateFloat(
            initialValue = 0.18f,
            targetValue = 0.46f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "auraAlpha",
        )
    } else {
        remember { mutableFloatStateOf(0.22f) }
    }

    val archColor = if (LocalArkColors.current.isDark) Color(0xFFF8FAFC) else Color(0xFF181115)

    Canvas(modifier = modifier) {
        val pulseAlpha = pulseAlphaState.value
        val w = size.width
        val h = size.height
        val u = minOf(w, h) / 108f
        val ox = (w - 108f * u) * 0.5f
        val oy = (h - 108f * u) * 0.5f

        fun pt(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)

        // Pulsing Crimson Aura
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    CrimsonPrimary.copy(alpha = pulseAlpha),
                    CrimsonDeep.copy(alpha = pulseAlpha * 0.35f),
                    Color.Transparent,
                ),
                center = pt(54f, 52f),
                radius = 50f * u,
            ),
            radius = 50f * u,
            center = pt(54f, 52f),
        )

        // Outer Xe.Ark Shield Crest
        val shield = Path().apply {
            moveTo(ox + 54f * u, oy + 14f * u)
            lineTo(ox + 85f * u, oy + 26.5f * u)
            lineTo(ox + 85f * u, oy + 55.5f * u)
            cubicTo(
                ox + 85f * u, oy + 73.5f * u,
                ox + 71.5f * u, oy + 86.5f * u,
                ox + 54f * u, oy + 93f * u,
            )
            cubicTo(
                ox + 36.5f * u, oy + 86.5f * u,
                ox + 23f * u, oy + 73.5f * u,
                ox + 23f * u, oy + 55.5f * u,
            )
            lineTo(ox + 23f * u, oy + 26.5f * u)
            close()
        }

        drawPath(
            path = shield,
            color = CrimsonPrimary.copy(alpha = 0.12f),
        )
        drawPath(
            path = shield,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFF3B5C), Color(0xFFE11D48), Color(0xFF7F1D1D)),
                start = pt(23f, 14f),
                end = pt(85f, 93f),
            ),
            style = Stroke(width = 5.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Inner Ark Arch (Monolith Gate)
        val arch = Path().apply {
            moveTo(ox + 39f * u, oy + 64f * u)
            lineTo(ox + 39f * u, oy + 49f * u)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    left = ox + 39f * u,
                    top = oy + 34f * u,
                    right = ox + 69f * u,
                    bottom = oy + 64f * u,
                ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            lineTo(ox + 69f * u, oy + 64f * u)
        }
        drawPath(
            path = arch,
            color = archColor,
            style = Stroke(width = 5.0f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Crimson Acoustic Crossbar
        val pulse = Path().apply {
            moveTo(ox + 32f * u, oy + 57f * u)
            lineTo(ox + 44.5f * u, oy + 57f * u)
            lineTo(ox + 49.5f * u, oy + 50.5f * u)
            lineTo(ox + 54f * u, oy + 62f * u)
            lineTo(ox + 58.5f * u, oy + 50.5f * u)
            lineTo(ox + 63.5f * u, oy + 57f * u)
            lineTo(ox + 76f * u, oy + 57f * u)
        }
        drawPath(
            path = pulse,
            color = CrimsonPrimary,
            style = Stroke(width = 3.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Central Crimson Core
        drawCircle(
            color = CrimsonPrimary.copy(alpha = 0.35f),
            radius = 7.2f * u,
            center = pt(54f, 43.5f),
        )
        drawCircle(
            color = CrimsonPrimary,
            radius = 4.4f * u,
            center = pt(54f, 43.5f),
        )

        // Dual Base Wave Arcs
        drawArc(
            color = CrimsonSecondary,
            startAngle = 25f,
            sweepAngle = 130f,
            useCenter = false,
            topLeft = pt(40f, 64f),
            size = Size(28f * u, 14f * u),
            style = Stroke(width = 3.4f * u, cap = StrokeCap.Round),
        )
        drawArc(
            color = CrimsonPrimary,
            startAngle = 35f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = pt(45f, 70f),
            size = Size(18f * u, 10f * u),
            style = Stroke(width = 2.6f * u, cap = StrokeCap.Round),
        )
    }
}
