package com.akane.inspiration.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.akane.inspiration.designsystem.R

/** Inter 4.1 static weights (SIL OFL 1.1), see FONTS.md. */
internal val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private const val TABULAR = "tnum"

private fun style(
    weight: FontWeight,
    size: Float,
    lineHeight: Float,
    tracking: Float,
    features: String? = null,
) = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    fontFeatureSettings = features,
)

/**
 * Full M3 scale, all proportional. Tabular figures (tnum) also widen hyphens ("X - Ray", "wake - ups"), so
 * prose styles never carry them; numbers that must align use [NumericTypography], or [withTabularFigures]
 * for chart axis labels.
 * Body is >= 16 sp on phones with ~1.45x line height; bodySmall is the caption tier.
 */
internal val InspirationTypography = Typography(
    displayLarge = style(FontWeight.SemiBold, 57f, 64f, -1.0f),
    displayMedium = style(FontWeight.SemiBold, 45f, 52f, -0.75f),
    displaySmall = style(FontWeight.SemiBold, 36f, 44f, -0.5f),
    headlineLarge = style(FontWeight.SemiBold, 32f, 40f, -0.5f),
    headlineMedium = style(FontWeight.SemiBold, 28f, 36f, -0.25f),
    headlineSmall = style(FontWeight.SemiBold, 24f, 32f, -0.1f),
    titleLarge = style(FontWeight.SemiBold, 22f, 28f, 0f),
    titleMedium = style(FontWeight.Medium, 16f, 24f, 0.1f),
    titleSmall = style(FontWeight.Medium, 14f, 20f, 0.1f),
    bodyLarge = style(FontWeight.Normal, 17f, 25f, 0f),
    bodyMedium = style(FontWeight.Normal, 16f, 23f, 0f),
    bodySmall = style(FontWeight.Normal, 14f, 20f, 0.1f),
    labelLarge = style(FontWeight.Medium, 14f, 20f, 0.1f),
    labelMedium = style(FontWeight.Medium, 12f, 16f, 0.3f),
    labelSmall = style(FontWeight.Medium, 11f, 16f, 0.4f),
)

/** [this] with tabular figures, for text that is all numbers (chart axis labels, durations in a column). */
internal fun TextStyle.withTabularFigures(): TextStyle = copy(fontFeatureSettings = TABULAR)

/** Styles for telemetry values (percentages, temperatures, durations). All use tabular figures. */
@Immutable
data class NumericTypography(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
)

internal val InspirationNumericTypography = NumericTypography(
    display = style(FontWeight.SemiBold, 45f, 52f, -0.75f, TABULAR),
    headline = style(FontWeight.SemiBold, 28f, 36f, -0.25f, TABULAR),
    title = style(FontWeight.SemiBold, 22f, 28f, 0f, TABULAR),
    body = style(FontWeight.Medium, 16f, 23f, 0f, TABULAR),
    label = style(FontWeight.Medium, 12f, 16f, 0.3f, TABULAR),
)

internal val LocalNumericTypography = staticCompositionLocalOf { InspirationNumericTypography }

val MaterialTheme.numericTypography: NumericTypography
    @Composable @ReadOnlyComposable
    get() = LocalNumericTypography.current
