package io.github.jeromekai.latent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.github.jeromekai.latent.core.model.AppTheme

/** The camera web UI's five themes: a pastel gradient and an accent. */
data class Palette(val top: Color, val bottom: Color, val accent: Color)

val AppTheme.palette: Palette
    get() = when (this) {
        // Gradients are the web UI's own --screen-bg values.
        AppTheme.AQUA -> Palette(Color(0xFFEEF8FF), Color(0xFFBFEEF8), Color(0xFF0E7490))
        AppTheme.SILVER -> Palette(Color(0xFFF7F7F7), Color(0xFFD7DDE4), Color(0xFF475569))
        AppTheme.LAVENDER -> Palette(Color(0xFFF6F1FF), Color(0xFFD8D0FF), Color(0xFF6D4AE0))
        AppTheme.MINT -> Palette(Color(0xFFF0FFF8), Color(0xFFC8F0DF), Color(0xFF0F7A55))
        AppTheme.SUNSET -> Palette(Color(0xFFFFF5EF), Color(0xFFFFD7BF), Color(0xFFC2511F))
    }

/** Amber, for the queue badge and magic mode, as on the camera. */
val Amber = Color(0xFFFFC23D)

@Composable
fun LatentTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val palette = theme.palette
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            secondary = palette.accent,
            background = palette.top,
            surface = Color.White,
            surfaceVariant = palette.bottom,
            onSurface = Color(0xFF121212),
            onBackground = Color(0xFF121212),
        ),
        content = content,
    )
}

/** The web UI's frosted screen background. */
@Composable
fun ThemedBackground(theme: AppTheme, content: @Composable BoxScope.() -> Unit) {
    val palette = theme.palette
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.top, palette.bottom))),
        content = content,
    )
}
