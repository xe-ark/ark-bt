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

val CrimsonPrimary = Color(0xFFFF2E4C)
val CrimsonSecondary = Color(0xFFFF6B81)
val CrimsonDeep = Color(0xFF991B1B)
val ObsidianBg = Color(0xFF07070A)
val ObsidianSurface = Color(0xFF110D10)
val ObsidianCard = Color(0xFF181115)
val ObsidianSubCard = Color(0xFF22161C)
val CrimsonBorder = Color(0xFF4A1522)

@Immutable
data class ArkBtExpressiveColors(
    val isDark: Boolean,
    val backdropBg: Color,
    val cardBg: Color,
    val heroTop: Color,
    val heroBottom: Color,
    val subCardBg: Color,
    val border: Color,
    val borderAccent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val crimson: Color,
    val crimsonSoft: Color,
    val emerald: Color,
    val amber: Color,
)

val LocalArkBtColors = staticCompositionLocalOf {
    ArkBtExpressiveColors(
        isDark = true,
        backdropBg = ObsidianBg,
        cardBg = ObsidianCard,
        heroTop = Color(0xFF280B14),
        heroBottom = ObsidianCard,
        subCardBg = ObsidianSubCard,
        border = CrimsonBorder,
        borderAccent = CrimsonPrimary,
        textPrimary = Color(0xFFF8FAFC),
        textSecondary = Color(0xFFB39EA5),
        crimson = CrimsonPrimary,
        crimsonSoft = CrimsonSecondary,
        emerald = Color(0xFF34D399),
        amber = Color(0xFFFBBF24),
    )
}

private val ArkDarkColorScheme = darkColorScheme(
    primary = CrimsonPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4C0D1E),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = CrimsonSecondary,
    onSecondary = Color(0xFF2B0810),
    secondaryContainer = Color(0xFF33151E),
    onSecondaryContainer = Color(0xFFFFD0D8),
    tertiary = Color(0xFFF59E0B),
    background = ObsidianBg,
    onBackground = Color(0xFFF8FAFC),
    surface = ObsidianSurface,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = ObsidianSubCard,
    onSurfaceVariant = Color(0xFFB8A3AA),
    outline = CrimsonBorder,
    error = Color(0xFFFF4D6A),
)

private val ArkLightColorScheme = lightColorScheme(
    primary = Color(0xFFD6133B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DF),
    onPrimaryContainer = Color(0xFF400010),
    secondary = Color(0xFF881337),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE4E8),
    onSecondaryContainer = Color(0xFF380917),
    tertiary = Color(0xFFB45309),
    background = Color(0xFFFAF6F7),
    onBackground = Color(0xFF140B0E),
    surface = Color(0xFFFFFBFC),
    onSurface = Color(0xFF140B0E),
    surfaceVariant = Color(0xFFF4E6E9),
    onSurfaceVariant = Color(0xFF5D464E),
    outline = Color(0xFFE3C2CA),
    error = Color(0xFFBA1A34),
)

private val ArkBtShapes = Shapes(
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val ArkBtTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 32.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 11.5.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun ArkBtTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Crimson-Black flagship identity prioritizes dark obsidian by default while still honoring light if desired;
    // keep dark obsidian expressive palette active across system themes or use rich contrast for light.
    val expressiveColors = if (darkTheme) {
        ArkBtExpressiveColors(
            isDark = true,
            backdropBg = ObsidianBg,
            cardBg = ObsidianCard,
            heroTop = Color(0xFF2C0B15),
            heroBottom = ObsidianCard,
            subCardBg = ObsidianSubCard,
            border = CrimsonBorder,
            borderAccent = CrimsonPrimary,
            textPrimary = Color(0xFFF8FAFC),
            textSecondary = Color(0xFFB39EA5),
            crimson = CrimsonPrimary,
            crimsonSoft = CrimsonSecondary,
            emerald = Color(0xFF34D399),
            amber = Color(0xFFFBBF24),
        )
    } else {
        ArkBtExpressiveColors(
            isDark = false,
            backdropBg = Color(0xFFFAF4F6),
            cardBg = Color.White,
            heroTop = Color(0xFFFFEEF1),
            heroBottom = Color.White,
            subCardBg = Color(0xFFF7E9ED),
            border = Color(0xFFE7C5CD),
            borderAccent = Color(0xFFD6133B),
            textPrimary = Color(0xFF140B0E),
            textSecondary = Color(0xFF644B53),
            crimson = Color(0xFFD6133B),
            crimsonSoft = Color(0xFFE11D48),
            emerald = Color(0xFF059669),
            amber = Color(0xFFD97706),
        )
    }

    CompositionLocalProvider(LocalArkBtColors provides expressiveColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) ArkDarkColorScheme else ArkLightColorScheme,
            shapes = ArkBtShapes,
            typography = ArkBtTypography,
            content = content,
        )
    }
}

@Composable
fun ArkCrestLogo(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val pulseAlphaState: State<Float> = if (animated) {
        val transition = rememberInfiniteTransition(label = "ArkCrestAura")
        transition.animateFloat(
            initialValue = 0.22f,
            targetValue = 0.52f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "auraAlpha",
        )
    } else {
        remember { mutableFloatStateOf(0.24f) }
    }

    val archColor = if (LocalArkBtColors.current.isDark) Color(0xFFF8FAFC) else Color(0xFF181115)

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
            color = CrimsonPrimary.copy(alpha = 0.14f),
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
