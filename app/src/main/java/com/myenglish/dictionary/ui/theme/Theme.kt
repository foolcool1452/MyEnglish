package com.myenglish.dictionary.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF116A7B),
    onPrimary = Color.White,
    secondary = Color(0xFF6B705C),
    tertiary = Color(0xFFC06C54),
    background = Color(0xFFF7F7F2),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7E9DF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DD3E1),
    secondary = Color(0xFFC7D4A1),
    tertiary = Color(0xFFFFB49F),
    background = Color(0xFF181A1B),
    surface = Color(0xFF202326),
    surfaceVariant = Color(0xFF34383A),
)

@Composable
fun MyEnglishTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = androidx.compose.material3.Typography(),
        content = content,
    )
}
