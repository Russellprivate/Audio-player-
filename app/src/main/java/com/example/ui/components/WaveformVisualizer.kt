package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPink
import kotlin.math.sin

@Composable
fun WaveformVisualizer(
    isPlaying: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
    barCount: Int = 36,
    activeColor: Color = NeonCyan,
    inactiveColor: Color = MaterialTheme.colorScheme.outline
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val gradient = Brush.verticalGradient(
        colors = listOf(NeonCyan, NeonPink)
    )

    Canvas(modifier = modifier.fillMaxWidth().height(48.dp)) {
        val totalWidth = size.width
        val barWidth = (totalWidth / (barCount * 1.5f)).coerceAtLeast(3f)
        val gap = (totalWidth - (barCount * barWidth)) / (barCount - 1).coerceAtLeast(1)
        val maxHeight = size.height

        for (i in 0 until barCount) {
            val normalizedIndex = i.toFloat() / barCount
            val baseHeightFactor = (0.25f + 0.65f * kotlin.math.abs(sin(normalizedIndex * Math.PI))).toFloat()
            val dynamicFactor = if (isPlaying) {
                0.2f * sin(phase + i * 0.4f)
            } else 0f
            val barHeight = (maxHeight * (baseHeightFactor + dynamicFactor).coerceIn(0.15f, 1f))
            val x = i * (barWidth + gap)
            val y = (maxHeight - barHeight) / 2f

            val isPastProgress = normalizedIndex <= progress
            val brush = if (isPastProgress) gradient else Brush.linearGradient(listOf(inactiveColor, inactiveColor))

            drawRoundRect(
                brush = brush,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
