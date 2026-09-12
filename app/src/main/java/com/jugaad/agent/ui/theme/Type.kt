@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.jugaad.agent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jugaad.agent.R

/**
 * Work Sans, the Humane Minimalist Dark system's typeface, as a single variable
 * TrueType resource instanced per weight via [FontVariation]. If the resource is
 * ever missing this fails to resolve at build time, not silently at runtime;
 * that tradeoff is intentional so a broken font ships loud, not quiet.
 */
val WorkSans = FontFamily(
    Font(R.font.work_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.work_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.work_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

private const val TABULAR_NUMERALS = "tnum"

private val base = Typography()
private fun TextStyle.inWorkSans() = copy(fontFamily = WorkSans)

/**
 * Humane Minimalist Dark type scale. Only the styles the design system names
 * explicitly get their own size/weight/line-height; every other Material default
 * style keeps its metrics but moves onto Work Sans so nothing on screen falls
 * back to the platform sans-serif. Tabular numerals stay on for every
 * number-bearing style via [TABULAR_NUMERALS].
 */
val Typography = Typography(
    displayLarge = base.displayLarge.inWorkSans(),
    displayMedium = base.displayMedium.inWorkSans(),
    displaySmall = base.displaySmall.inWorkSans(),
    headlineLarge = base.headlineLarge.inWorkSans(),
    headlineMedium = base.headlineMedium.copy(
        fontFamily = WorkSans, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 32.sp, fontFeatureSettings = TABULAR_NUMERALS,
    ),
    headlineSmall = base.headlineSmall.copy(
        fontFamily = WorkSans, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp, fontFeatureSettings = TABULAR_NUMERALS,
    ),
    titleLarge = base.titleLarge.copy(
        fontFamily = WorkSans, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 26.sp,
    ),
    titleMedium = base.titleMedium.copy(
        fontFamily = WorkSans, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp,
    ),
    titleSmall = base.titleSmall.inWorkSans(),
    bodyLarge = base.bodyLarge.copy(
        fontFamily = WorkSans, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 24.sp, fontFeatureSettings = TABULAR_NUMERALS,
    ),
    bodyMedium = base.bodyMedium.copy(
        fontFamily = WorkSans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 22.sp, fontFeatureSettings = TABULAR_NUMERALS,
    ),
    bodySmall = base.bodySmall.inWorkSans(),
    labelLarge = base.labelLarge.copy(
        fontFamily = WorkSans, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = TABULAR_NUMERALS,
    ),
    labelMedium = base.labelMedium.copy(
        fontFamily = WorkSans, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp,
    ),
    labelSmall = base.labelSmall.copy(
        fontFamily = WorkSans, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
)
