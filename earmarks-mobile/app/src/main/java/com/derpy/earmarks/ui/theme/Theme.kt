package com.derpy.earmarks.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Reversed-on-ink scheme: Hardcore Black ground, Nova White copy, Build Acid as the
// single signal (active controls, the mark), Halifax Cobalt reserved for links/success.
private val EarmarksColorScheme = darkColorScheme(
    primary = BuildAcid,
    onPrimary = HardcoreBlack,
    secondary = HalifaxCobalt,
    onSecondary = NovaWhite,
    tertiary = HalifaxCobalt,
    onTertiary = NovaWhite,
    background = HardcoreBlack,
    onBackground = NovaWhite,
    surface = HardcoreBlack,
    onSurface = NovaWhite,
    surfaceVariant = InkSurfaceVariant,
    onSurfaceVariant = MutedPaper,
    outline = InkOutline,
    error = SignalRed,
    onError = HardcoreBlack,
)

@Composable
fun EarmarksTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EarmarksColorScheme,
        typography = EarmarksTypography,
        content = content,
    )
}
