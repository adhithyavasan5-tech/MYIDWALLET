package com.myid.wallet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF102B50)
private val LightScheme = lightColorScheme(
    primary = Navy,
    secondary = Color(0xFF193E68),
    background = Color(0xFFF6F8FC),
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Color(0xFF17243A),
    onSurface = Color(0xFF17243A)
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9DBCE5),
    secondary = Color(0xFFB5C9E5),
    background = Color(0xFF0B1422),
    surface = Color(0xFF142238),
    onPrimary = Color(0xFF102B50),
    onBackground = Color(0xFFEAF0F8),
    onSurface = Color(0xFFEAF0F8)
)

@Composable
fun MyIDTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}
