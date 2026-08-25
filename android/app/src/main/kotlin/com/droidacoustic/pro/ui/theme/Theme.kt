package com.droidacoustic.pro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// =============================================================================
// Calibrated palette
// =============================================================================
//
// Material dynamic colour is deliberately NOT used. This app draws SPL data as
// colour, so the interface chrome must never drift into the hues the data
// occupies — a wallpaper-tinted accent could land on the same blue as the cold
// end of the coverage ramp and make the legend ambiguous.
//
// The rule throughout: chrome stays in neutrals, saturation is reserved for
// data and status.
// =============================================================================

object Instrument {

    // ── Neutrals (blue-biased, so they sit under the cyan accent) ────────────
    val Ground      = Color(0xFF0B0F14)   // app background, behind everything
    val Surface     = Color(0xFF141A22)   // panels, rails, cards
    val SurfaceHigh = Color(0xFF1B232D)   // raised: fields, chips, headers
    val Sunken      = Color(0xFF080B0F)   // viewport, code, wells
    val Outline     = Color(0xFF27323E)
    val OutlineSoft = Color(0xFF1C242E)

    val Ink         = Color(0xFFE9EEF3)
    val InkMuted    = Color(0xFFB4C2D0)
    val InkDim      = Color(0xFF7E8FA1)

    // ── Single accent, used for selection and interactive affordance only ────
    val Accent      = Color(0xFF3ABDF0)
    val OnAccent    = Color(0xFF06222E)
    val AccentSoft  = Color(0xFF12313F)

    // ── Status. Separate from the accent and from the SPL ramp. ──────────────
    val Critical    = Color(0xFFF2555A)
    val Warning     = Color(0xFFF98736)
    val Caution     = Color(0xFFE3B341)
    val Good        = Color(0xFF2DD4A0)

    // ── SPL ramp. Cold → hot. ────────────────────────────────────────────────
    // Muted and earthy rather than fully saturated: the vivid ramp read as a
    // rainbow, inventing boundaries at the yellow and cyan transitions that were
    // not in the data. Every SPL surface reads from this one list - the 3D field,
    // the viewport legend and the analysis strip - so the key always matches the
    // map it explains.
    val Spl = listOf(
        Color(0xFF2C4B7C),   // coldest - muted navy
        Color(0xFF356B73),   // teal
        Color(0xFF447757),   // green
        Color(0xFF6E7A45),   // olive
        Color(0xFF8A6B3E),   // bronze
        Color(0xFF8E4540)    // hottest - brick
    )

    // ── Light variants, for daylight use on site ─────────────────────────────
    val LGround      = Color(0xFFFBFCFD)
    val LSurface     = Color(0xFFFFFFFF)
    val LSurfaceHigh = Color(0xFFF1F5F8)
    val LSunken      = Color(0xFFEDF1F5)
    val LOutline     = Color(0xFFD3DDE6)
    val LOutlineSoft = Color(0xFFE4EBF1)
    val LInk         = Color(0xFF0F1821)
    val LInkMuted    = Color(0xFF3A4B5C)
    val LInkDim      = Color(0xFF65788A)
    val LAccent      = Color(0xFF0C7EA8)
    val LAccentSoft  = Color(0xFFE1F1F8)
}

private val DarkScheme = darkColorScheme(
    primary            = Instrument.Accent,
    onPrimary          = Instrument.OnAccent,
    primaryContainer   = Instrument.AccentSoft,
    onPrimaryContainer = Instrument.Accent,
    secondary          = Instrument.InkMuted,
    onSecondary        = Instrument.Ground,
    background         = Instrument.Ground,
    onBackground       = Instrument.Ink,
    surface            = Instrument.Surface,
    onSurface          = Instrument.Ink,
    surfaceVariant     = Instrument.SurfaceHigh,
    onSurfaceVariant   = Instrument.InkMuted,
    outline            = Instrument.Outline,
    outlineVariant     = Instrument.OutlineSoft,
    error              = Instrument.Critical,
    onError            = Color(0xFF2A1417)
)

private val LightScheme = lightColorScheme(
    primary            = Instrument.LAccent,
    onPrimary          = Color.White,
    primaryContainer   = Instrument.LAccentSoft,
    onPrimaryContainer = Instrument.LAccent,
    secondary          = Instrument.LInkMuted,
    onSecondary        = Color.White,
    background         = Instrument.LGround,
    onBackground       = Instrument.LInk,
    surface            = Instrument.LSurface,
    onSurface          = Instrument.LInk,
    surfaceVariant     = Instrument.LSurfaceHigh,
    onSurfaceVariant   = Instrument.LInkMuted,
    outline            = Instrument.LOutline,
    outlineVariant     = Instrument.LOutlineSoft,
    error              = Color(0xFFC42B32),
    onError            = Color.White
)

// =============================================================================
// Typography
// =============================================================================
//
// Every style that can contain a number uses tabular figures, so digits keep
// their column as values update. Without this, a live SPL readout jitters
// horizontally on every recalculation.
// =============================================================================

private const val TNUM = "tnum"

val InstrumentTypography = Typography(

    // Large numeric readouts — the analysis strip.
    displaySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = TNUM
    ),

    // Panel and section titles.
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),

    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 17.sp
    ),

    // Body copy and field values.
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontFeatureSettings = TNUM
    ),

    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = TNUM
    ),

    // Numeric entry.
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = TNUM
    ),

    // Field captions and units.
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
    ),

    // Uppercase section eyebrows.
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.1.sp
    )
)

@Composable
fun DroidAcousticTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = InstrumentTypography,
        content = content
    )
}
