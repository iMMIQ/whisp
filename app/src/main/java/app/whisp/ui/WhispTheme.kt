// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF17101F)
val Surface = Color(0xFF24192D)
val SurfaceLow = Color(0xFF1D1426)
val SurfaceHigh = Color(0xFF2B1F36)
val Raised = Color(0xFF33253E)
val Rose = Color(0xFFEE9EBE)
val RoseContainer = Color(0xFF4A2A40)
val PlumContainer = Color(0xFF3B2744)
val Cream = Color(0xFFF9EDF3)
val Muted = Color(0xFFBBA9C5)
val ErrorRose = Color(0xFFF28B95)
val ErrorContainerDark = Color(0xFF4E1E28)
val ScrimDark = Color(0xFF0A060E)

private val AmoledInk = Color(0xFF000000)
private val AmoledSurfaceLow = Color(0xFF0E0A13)
private val AmoledSurface = Color(0xFF140F1B)
private val AmoledSurfaceHigh = Color(0xFF1C1525)
private val AmoledRaised = Color(0xFF261D31)

internal val LocalReducedMotion = staticCompositionLocalOf { false }

internal val LocalAmoledBlack = staticCompositionLocalOf { false }

internal val TabularNumeralStyle = TextStyle(fontFeatureSettings = "tnum")

private val WhispTypography =
    Typography(
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                letterSpacing = (-0.4).sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 31.sp,
                letterSpacing = (-0.25).sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 27.sp,
                letterSpacing = (-0.15).sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                lineHeight = 25.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 25.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            ),
    )

private val WhispShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(22.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

// Spatial motion uses critically damped springs for interruptible gesture continuity.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private class WhispMotionScheme(private val reduced: Boolean) : MotionScheme {
    private fun <T> spatial(stiffness: Float): FiniteAnimationSpec<T> =
        if (reduced) snap()
        else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness)

    override fun <T> defaultSpatialSpec() = spatial<T>(450f)

    override fun <T> fastSpatialSpec() = spatial<T>(700f)

    override fun <T> slowSpatialSpec() = spatial<T>(300f)

    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(if (reduced) 0 else 150)

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(if (reduced) 0 else 100)

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(if (reduced) 0 else 220)
}

private fun whispColorScheme(amoledBlack: Boolean): ColorScheme {
    val bg = if (amoledBlack) AmoledInk else Ink
    val low = if (amoledBlack) AmoledSurfaceLow else SurfaceLow
    val mid = if (amoledBlack) AmoledSurface else Surface
    val high = if (amoledBlack) AmoledSurfaceHigh else SurfaceHigh
    val highest = if (amoledBlack) AmoledRaised else Raised
    return darkColorScheme(
        primary = Rose,
        onPrimary = Ink,
        primaryContainer = RoseContainer,
        onPrimaryContainer = Cream,
        secondary = Rose,
        onSecondary = Ink,
        secondaryContainer = PlumContainer,
        onSecondaryContainer = Rose,
        tertiary = Color(0xFFD5B1E6),
        onTertiary = Ink,
        tertiaryContainer = Color(0xFF39274A),
        onTertiaryContainer = Cream,
        background = bg,
        onBackground = Cream,
        surface = mid,
        onSurface = Cream,
        surfaceVariant = highest,
        onSurfaceVariant = Muted,
        surfaceContainerLowest = bg,
        surfaceContainerLow = low,
        surfaceContainer = mid,
        surfaceContainerHigh = high,
        surfaceContainerHighest = highest,
        surfaceTint = Color.Transparent,
        outline = Muted.copy(alpha = .34f),
        outlineVariant = Cream.copy(alpha = .10f),
        error = ErrorRose,
        onError = Ink,
        errorContainer = ErrorContainerDark,
        onErrorContainer = Color(0xFFFFD9DE),
        inverseSurface = Color(0xFF3A2B47),
        inverseOnSurface = Cream,
        inversePrimary = Rose,
        scrim = ScrimDark,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WhispTheme(
    reduceMotion: Boolean = false,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = remember(amoledBlack) { whispColorScheme(amoledBlack) }
    CompositionLocalProvider(
        LocalReducedMotion provides reduceMotion,
        LocalAmoledBlack provides amoledBlack,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = WhispTypography,
            shapes = WhispShapes,
            motionScheme = remember(reduceMotion) { WhispMotionScheme(reduceMotion) },
            content = content,
        )
    }
}
