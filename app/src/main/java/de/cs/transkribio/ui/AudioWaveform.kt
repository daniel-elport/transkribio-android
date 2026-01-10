package de.cs.transkribio.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AudioWaveform(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    barWidth: Dp = 4.dp,
    barSpacing: Dp = 2.dp,
    minBarHeight: Dp = 4.dp,
    maxBarHeight: Dp = 48.dp,
    barColor: Color = MaterialTheme.colorScheme.primary,
    inactiveBarColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(maxBarHeight),
        horizontalArrangement = Arrangement.spacedBy(barSpacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        amplitudes.forEachIndexed { index, amplitude ->
            WaveformBar(
                amplitude = amplitude,
                barWidth = barWidth,
                minHeight = minBarHeight,
                maxHeight = maxBarHeight,
                activeColor = barColor,
                inactiveColor = inactiveBarColor
            )
        }
    }
}

@Composable
private fun WaveformBar(
    amplitude: Float,
    barWidth: Dp,
    minHeight: Dp,
    maxHeight: Dp,
    activeColor: Color,
    inactiveColor: Color
) {
    val animatedAmplitude by animateFloatAsState(
        targetValue = amplitude,
        animationSpec = tween(durationMillis = 50),
        label = "amplitude"
    )

    val barHeight = minHeight + (maxHeight - minHeight) * animatedAmplitude
    val isActive = animatedAmplitude > 0.05f

    Box(
        modifier = Modifier
            .width(barWidth)
            .height(barHeight)
            .clip(RoundedCornerShape(barWidth / 2))
            .background(if (isActive) activeColor else inactiveColor)
    )
}

@Composable
fun CompactWaveform(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    AudioWaveform(
        amplitudes = amplitudes,
        modifier = modifier,
        barWidth = 3.dp,
        barSpacing = 2.dp,
        minBarHeight = 3.dp,
        maxBarHeight = 32.dp,
        barColor = barColor,
        inactiveBarColor = barColor.copy(alpha = 0.2f)
    )
}
