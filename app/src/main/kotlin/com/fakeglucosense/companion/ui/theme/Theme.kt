package com.fakeglucosense.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.fakeglucosense.companion.domain.GlucoseBand

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    secondary = Color(0xFF4A6360),
    background = Color(0xFFF5FBF9),
    surface = Color(0xFFFFFFFF),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5EE0C4),
    onPrimary = Color(0xFF003730),
    secondary = Color(0xFFB1CCC7),
    background = Color(0xFF0E1514),
    surface = Color(0xFF162120),
)

@Composable
fun FakeGlucoSenseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}

private val InRangeColor = Color(0xFF2E7D32)
private val CautionColor = Color(0xFFEF6C00)
private val UrgentColor = Color(0xFFC62828)

/** Foreground colour for a band. Shared by the chart, the hero card, and the trend labels. */
fun bandColor(band: GlucoseBand): Color = when (band) {
    GlucoseBand.IN_RANGE -> InRangeColor
    GlucoseBand.LOW, GlucoseBand.HIGH -> CautionColor
    GlucoseBand.URGENT_LOW, GlucoseBand.URGENT_HIGH -> UrgentColor
}
