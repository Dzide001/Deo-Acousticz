package com.droidacoustic.pro.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─── Fallback colour schemes (used on Android < 12 where dynamic colour unavailable) ───

private val DarkColours = darkColorScheme(
    primary          = androidx.compose.ui.graphics.Color(0xFF82B1FF),  // Blue-ish accent
    onPrimary        = androidx.compose.ui.graphics.Color(0xFF003087),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF0047B3),
    surface          = androidx.compose.ui.graphics.Color(0xFF1A1C1E),
    background       = androidx.compose.ui.graphics.Color(0xFF121416),
)

private val LightColours = lightColorScheme(
    primary          = androidx.compose.ui.graphics.Color(0xFF0047B3),
    onPrimary        = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFD8E6FF),
    surface          = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
    background       = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
)

// ─── Typography ───────────────────────────────────────────────────────────────

val DroidAcousticTypography = Typography(
    // Used for SPL readouts and numeric values in the results panel.
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize   = 22.sp,
        lineHeight = 28.sp,
    ),
    // Labels in the properties panel.
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
    ),
    // Axis tick labels on the heatmap legend.
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize   = 11.sp,
        lineHeight = 16.sp,
    ),
)

// ─── Theme entry point ────────────────────────────────────────────────────────

@Composable
fun DroidAcousticTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,  // Material 3 dynamic color (Android 12+)
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColours
        else      -> LightColours
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = DroidAcousticTypography,
        content     = content
    )
}
