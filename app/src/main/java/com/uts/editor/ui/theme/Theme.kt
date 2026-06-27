package com.uts.editor.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.uts.editor.data.ThemeMode
import com.uts.editor.editor.SyntaxColors

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    secondary = Color(0xFF00796B),
    background = Color(0xFFFDFDFD),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF1F5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF64B5F6),
    onPrimary = Color(0xFF062B52),
    secondary = Color(0xFF4DB6AC),
    background = Color(0xFF121212),
    surface = Color(0xFF1B1B1D),
    surfaceVariant = Color(0xFF2A2D31),
)

@Composable
fun UtsTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}

/** Syntax palette derived from whether we're in dark mode. */
fun syntaxColorsFor(dark: Boolean): SyntaxColors = if (dark) {
    SyntaxColors(
        keyword = Color(0xFF569CD6),
        string = Color(0xFFCE9178),
        comment = Color(0xFF6A9955),
        number = Color(0xFFB5CEA8),
        tag = Color(0xFF569CD6),
        attribute = Color(0xFF9CDCFE),
        punctuation = Color(0xFFD4D4D4),
    )
} else {
    SyntaxColors(
        keyword = Color(0xFF0000FF),
        string = Color(0xFFA31515),
        comment = Color(0xFF008000),
        number = Color(0xFF098658),
        tag = Color(0xFF800000),
        attribute = Color(0xFFFF0000),
        punctuation = Color(0xFF333333),
    )
}
