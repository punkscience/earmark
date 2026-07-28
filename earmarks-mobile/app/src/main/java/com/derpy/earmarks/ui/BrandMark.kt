package com.derpy.earmarks.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.derpy.earmarks.ui.theme.BuildAcid

// The Punk Science mark: three build bubbles. Opacity gradation 100/60/30, the
// full dot always leads (left), the faintest always trails (right) — never reversed.
private val BASE_ALPHA = floatArrayOf(1.0f, 0.6f, 0.3f)

/**
 * The three build dots. When [animated], they cycle in a left-to-right wave
 * (loading → almost-ready → settled) while preserving the fixed 100/60/30 order.
 */
@Composable
fun BuildDots(
    dotSize: Dp,
    modifier: Modifier = Modifier,
    color: Color = BuildAcid,
    animated: Boolean = false,
) {
    val gap = dotSize * 0.55f
    val phase by if (animated) {
        rememberInfiniteTransition(label = "build").animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
    } else {
        // Stable read so the composable shape matches in both branches.
        rememberStaticPhase()
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0..2) {
            val alpha = if (animated) {
                // Subtle wave: each dot brightens as the phase sweeps past it,
                // added on top of its fixed base so ordering never inverts.
                val d = (phase - i).coerceIn(0f, 1f)
                val wave = 0.5f * (1f - kotlin.math.abs(0.5f - d) * 2f)
                (BASE_ALPHA[i] + 0.35f * wave).coerceAtMost(1f)
            } else {
                BASE_ALPHA[i]
            }
            Canvas(Modifier.size(dotSize)) {
                drawCircle(color = color, alpha = alpha)
            }
        }
    }
}

@Composable
private fun rememberStaticPhase(): androidx.compose.runtime.State<Float> =
    androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }

/**
 * Horizontal lockup: build dots lead, then the "earmarks" wordmark in mono.
 * Dot diameter ≈ ½ the wordmark type size; gap to the wordmark ≈ 0.45× type size.
 */
@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    fontSizeSp: Int = 22,
    color: Color = MaterialTheme.colorScheme.onBackground,
    dotColor: Color = BuildAcid,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        BuildDots(dotSize = (fontSizeSp * 0.5f).dp, color = dotColor)
        Spacer(Modifier.width((fontSizeSp * 0.45f).dp))
        Text(
            "earmarks",
            color = color,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.015).sp,
        )
    }
}

/**
 * The branded splash: animated build dots stacked above the "earmarks" wordmark,
 * closed with the `[ ok ]` build-log sign-off in acid. Shown on the ink ground.
 */
@Composable
fun SplashScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BuildDots(dotSize = 28.dp, animated = true)
        Spacer(Modifier.height(28.dp))
        Text(
            "earmarks",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.015).sp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "[ ok ]",
            color = BuildAcid,
            fontSize = 14.sp,
            letterSpacing = 0.04.sp,
        )
    }
}
