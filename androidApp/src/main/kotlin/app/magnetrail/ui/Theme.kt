package app.magnetrail.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MagnetrailColors = darkColorScheme(
    primary = Color(0xFF4FD1C5),
    onPrimary = Color(0xFF00201D),
    secondary = Color(0xFF9BB4CF),
    onSecondary = Color(0xFF0B1E2C),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF151A22),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF202733),
    onSurfaceVariant = Color(0xFFB6BFCB),
    error = Color(0xFFFF6B6B),
    outline = Color(0xFF2C3542)
)

@Composable
fun MagnetrailTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MagnetrailColors, content = content)
}
