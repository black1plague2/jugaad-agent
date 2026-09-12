package com.jugaad.agent.ui.theme

import com.jugaad.agent.ui.common.fiori.FioriColors

// Legacy aliases kept for existing call sites across the app; canonical values now
// live in FioriColors (Humane Minimalist Dark tokens; the `Fiori` name is kept for
// source stability only).
val Ink            = FioriColors.Background
val InkElevated    = FioriColors.Surface
val InkCard        = FioriColors.SurfaceElevated
val InkLine        = FioriColors.Hairline
val TextHi         = FioriColors.TextPrimary
val TextMid        = FioriColors.TextSecondary
val TextLo         = FioriColors.TextDisabled

val Accent         = FioriColors.Brand

// Status
val StatusHealthy      = FioriColors.Positive
val StatusWarning      = FioriColors.Critical
val StatusCritical     = FioriColors.Negative
