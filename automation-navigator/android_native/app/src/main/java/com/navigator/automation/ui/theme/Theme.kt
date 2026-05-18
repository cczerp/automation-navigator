package com.navigator.automation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Purple    = Color(0xFF6650A4)
private val PurpleCnt = Color(0xFFEADDFF)
private val Teal      = Color(0xFF006874)

private val LightColors = lightColorScheme(
    primary   = Purple,
    secondary = Teal,
    background = Color(0xFFF8F5FF),
    surface    = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary   = PurpleCnt,
    secondary = Color(0xFF4FD8EB),
    background = Color(0xFF1C1B1F),
    surface    = Color(0xFF2B2930)
)

@Composable
fun AppTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
