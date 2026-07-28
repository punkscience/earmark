package com.derpy.earmarks.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.derpy.earmarks.R

// JetBrains Mono is the brand's one typeface — the wordmark, UI, and all body copy.
// "The mark IS the typeface": everything is set in mono.
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

// Material3 typography, every slot remapped to JetBrains Mono. Large display slots
// carry the brand's −0.015em tracking; small slots track at 0.
val EarmarksTypography = Typography().run {
    copy(
        displayLarge = displayLarge.mono(-0.015.em),
        displayMedium = displayMedium.mono(-0.015.em),
        displaySmall = displaySmall.mono(-0.015.em),
        headlineLarge = headlineLarge.mono(-0.015.em),
        headlineMedium = headlineMedium.mono(-0.015.em),
        headlineSmall = headlineSmall.mono(-0.015.em),
        titleLarge = titleLarge.mono(),
        titleMedium = titleMedium.mono(),
        titleSmall = titleSmall.mono(),
        bodyLarge = bodyLarge.mono(),
        bodyMedium = bodyMedium.mono(),
        bodySmall = bodySmall.mono(),
        labelLarge = labelLarge.mono(),
        labelMedium = labelMedium.mono(),
        labelSmall = labelSmall.mono(),
    )
}

private fun TextStyle.mono(tracking: androidx.compose.ui.unit.TextUnit = 0.sp) =
    copy(fontFamily = JetBrainsMono, letterSpacing = tracking)
