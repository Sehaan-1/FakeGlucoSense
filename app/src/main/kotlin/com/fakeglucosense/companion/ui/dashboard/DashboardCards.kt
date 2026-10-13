package com.fakeglucosense.companion.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fakeglucosense.companion.data.settings.DisplayUnit
import com.fakeglucosense.companion.domain.GlucoseBand
import com.fakeglucosense.companion.domain.bandOf
import com.fakeglucosense.companion.ui.format.GlucoseFormat
import com.fakeglucosense.companion.ui.theme.bandColor
import com.fakeglucosense.core.analysis.GlucoseSummary
import com.fakeglucosense.core.model.AlertThresholds
import com.fakeglucosense.core.model.GlucoseReading

/** The latest reading: value, trend arrow, and how old it is. Tinted by band. */
@Composable
fun GlucoseHeroCard(
    latest: GlucoseReading?,
    thresholds: AlertThresholds,
    unit: DisplayUnit,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val band = latest?.let { thresholds.bandOf(it.mgdl) }
    val tint = band?.let { bandColor(it).copy(alpha = 0.16f) } ?: MaterialTheme.colorScheme.surfaceVariant
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tint),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            if (latest == null || band == null) {
                Text("--", style = MaterialTheme.typography.displayLarge)
                Text("Waiting for the first reading", style = MaterialTheme.typography.bodyLarge)
                return@Card
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = GlucoseFormat.value(latest.mgdl, unit),
                    style = MaterialTheme.typography.displayLarge,
                    color = bandColor(band),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = GlucoseFormat.unitLabel(unit),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(latest.trend.symbol, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(8.dp))
                Text(latest.trend.label, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = bandLabel(band) + " · " + GlucoseFormat.ago(latest.epochMillis, nowMillis),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Averages and time-in-range over the selected window. */
@Composable
fun GlucoseStatsCard(
    summary: GlucoseSummary?,
    unit: DisplayUnit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Window summary", style = MaterialTheme.typography.titleSmall)
            if (summary == null) {
                Text("No readings in this window yet.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            StatRow("Average", "${GlucoseFormat.number(summary.meanMgdl, unit)} ${GlucoseFormat.unitLabel(unit)}")
            StatRow("Range", "${GlucoseFormat.value(summary.minMgdl, unit)}–${GlucoseFormat.value(summary.maxMgdl, unit)}")
            StatRow("Time in range", percent(summary.timeInRangePercent))
            StatRow("Below / above", "${percent(summary.timeBelowPercent)} / ${percent(summary.timeAbovePercent)}")
            StatRow("Estimated A1c (GMI)", percent(summary.gmiPercent))
            StatRow("Variability (CV)", percent(summary.cvPercent))
            StatRow("Readings", summary.count.toString())
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun percent(value: Double): String = String.format(java.util.Locale.getDefault(), "%.0f%%", value)

private fun bandLabel(band: GlucoseBand): String = when (band) {
    GlucoseBand.URGENT_LOW -> "Urgent low"
    GlucoseBand.LOW -> "Low"
    GlucoseBand.IN_RANGE -> "In range"
    GlucoseBand.HIGH -> "High"
    GlucoseBand.URGENT_HIGH -> "Urgent high"
}
