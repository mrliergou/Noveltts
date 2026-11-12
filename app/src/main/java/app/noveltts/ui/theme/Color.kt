package app.noveltts.ui.theme

import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF202D33)
val Muted = Color(0xFF647377)
val Teal = Color(0xFF007770)
val TealDark = Color(0xFF005B57)
val Accent = Color(0xFFBE5C2A)
val AppBackground = Color(0xFFF4F7F5)
private val PaleGreen = Color(0xFFEBF6F1)

val LightColors = androidx.compose.material3.lightColorScheme(
    primary = TealDark,
    onPrimary = Color.White,
    secondary = Teal,
    onSecondary = Color.White,
    background = AppBackground,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = PaleGreen,
    onSurfaceVariant = Muted,
    outline = Color(0xFFDDE7E2),
)
