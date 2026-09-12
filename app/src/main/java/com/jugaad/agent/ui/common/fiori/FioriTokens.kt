package com.jugaad.agent.ui.common.fiori

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jugaad.agent.domain.model.MachineStatus

/**
 * "Humane Minimalist Dark" design tokens (warm-neutral midnight canvas, tonal
 * layering instead of borders, a single crimson accent) plus the semantic status
 * vocabulary used across every screen. The `Fiori` names are kept for source
 * stability, so nothing outside the theme/common packages has to change, but the
 * values below are the Humane Minimalist Dark system, not SAP Fiori.
 */
enum class Semantic { POSITIVE, CRITICAL, NEGATIVE, INFORMATIVE, NEUTRAL }

/** HEALTHY -> Positive, WARNING -> Critical, CRITICAL -> Negative. */
fun MachineStatus.semantic(): Semantic = when (this) {
    MachineStatus.HEALTHY -> Semantic.POSITIVE
    MachineStatus.WARNING -> Semantic.CRITICAL
    MachineStatus.CRITICAL -> Semantic.NEGATIVE
}

object FioriColors {
    val Background = Color(0xFF07080A)
    val Surface = Color(0xFF111317)
    val SurfaceElevated = Color(0xFF181B20)
    val InputBackground = Color(0xFF0C0E12)
    val Hairline = Color(0x0FFFFFFF)
    val RowHighlight = Color(0x05FFFFFF)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF9CA3AF)
    val TextDisabled = Color(0xFF64748B)
    val Brand = Color(0xFFC30000)
    val BrandPressed = Color(0xFFA60000)
    val Positive = Color(0xFF48BB78)
    val Critical = Color(0xFFE0A437)
    val Negative = Color(0xFFFFB4AB)
    val NegativeContainer = Color(0xFF93000A)
    val Informative = Color(0xFFB8C4FF)
    val Neutral = Color(0xFF9CA3AF)
    val ChipNeutral = Color(0x0FFFFFFF)
    val ChipText = Color(0xFFD1D5DB)

    /** Status tint: [of] at alpha 0.14 over [Surface], used by chips and tags. */
    const val STATUS_TINT_ALPHA = 0.14f

    fun of(s: Semantic): Color = when (s) {
        Semantic.POSITIVE -> Positive
        Semantic.CRITICAL -> Critical
        Semantic.NEGATIVE -> Negative
        Semantic.INFORMATIVE -> Informative
        Semantic.NEUTRAL -> Neutral
    }
}

/** Cards and panels: 16 dp corners, the one radius rule for outer containers. */
val FioriRadius = RoundedCornerShape(16.dp)

/** Buttons, inputs and other interactive controls: 8 dp corners. */
val FioriControlRadius = RoundedCornerShape(8.dp)

/** Tags, chips and badges: 6 dp corners. */
val FioriTagRadius = RoundedCornerShape(6.dp)
