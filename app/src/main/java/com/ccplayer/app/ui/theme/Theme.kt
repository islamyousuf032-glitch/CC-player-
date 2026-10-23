package com.ccplayer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF07090D)
val Panel = Color(0xFF10161D)
val PanelRaised = Color(0xFF18222C)
val Mint = Color(0xFF70E1D1)
val Amber = Color(0xFFF4B860)
val TextPrimary = Color(0xFFF3F6F8)
val TextSecondary = Color(0xFF9AAAB7)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    secondary = Amber,
    onSecondary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = TextSecondary,
    error = Color(0xFFFF8A80),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C61),
    secondary = Color(0xFF8A5B00),
)

@Composable
fun CCPlayerTheme(content: @Composable () -> Unit) {
    // The editor canvas remains dark in both system modes to avoid misleading color perception.
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else DarkColors,
        content = content,
    )
}
