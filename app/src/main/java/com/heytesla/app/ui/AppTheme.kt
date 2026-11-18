package com.heytesla.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val AppSuccessColor = Color(0xFF74D9A0)
internal val AppWarningColor = Color(0xFFEDC477)

private val HeyTeslaColors = darkColorScheme(
    primary = Color(0xFFF4F4F5),
    onPrimary = Color(0xFF17181A),
    primaryContainer = Color(0xFF303237),
    onPrimaryContainer = Color(0xFFF4F4F5),
    secondary = Color(0xFF84B6FF),
    onSecondary = Color(0xFF101B2A),
    secondaryContainer = Color(0xFF26384E),
    onSecondaryContainer = Color(0xFFF4F4F5),
    background = Color(0xFF17181A),
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF222427),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF303237),
    onSurfaceVariant = Color(0xFFB5B8BE),
    surfaceContainerLowest = Color(0xFF17181A),
    surfaceContainerLow = Color(0xFF1C1E20),
    surfaceContainer = Color(0xFF222427),
    surfaceContainerHigh = Color(0xFF2A2C2F),
    surfaceContainerHighest = Color(0xFF303237),
    outline = Color(0xFF777D88),
    outlineVariant = Color(0xFF454A52),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF321313),
)

private val HeyTeslaTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
internal fun HeyTeslaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HeyTeslaColors,
        typography = HeyTeslaTypography,
        content = content,
    )
}
