package com.fakeglucosense.companion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fakeglucosense.companion.data.settings.DisplayUnit
import com.fakeglucosense.companion.ui.format.GlucoseFormat
import com.fakeglucosense.core.model.AlertThresholds

private const val STEP_MGDL = 5

/**
 * Four threshold rows with +/- steppers. Edits go to a draft. Save is enabled only when the draft
 * passes the same validation the core model applies, so an invalid set never reaches storage.
 */
@Composable
fun ThresholdEditor(
    saved: AlertThresholds,
    unit: DisplayUnit,
    onSave: (AlertThresholds) -> Unit,
) {
    var urgentLow by remember(saved) { mutableStateOf(saved.urgentLowMgdl) }
    var low by remember(saved) { mutableStateOf(saved.lowMgdl) }
    var high by remember(saved) { mutableStateOf(saved.highMgdl) }
    var urgentHigh by remember(saved) { mutableStateOf(saved.urgentHighMgdl) }

    val errors = AlertThresholds.validationErrors(urgentLow, low, high, urgentHigh)
    val changed = urgentLow != saved.urgentLowMgdl || low != saved.lowMgdl ||
        high != saved.highMgdl || urgentHigh != saved.urgentHighMgdl

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThresholdRow("Urgent low", urgentLow, AlertThresholds.URGENT_LOW_RANGE, unit) { urgentLow = it }
        ThresholdRow("Low", low, AlertThresholds.LOW_RANGE, unit) { low = it }
        ThresholdRow("High", high, AlertThresholds.HIGH_RANGE, unit) { high = it }
        ThresholdRow("Urgent high", urgentHigh, AlertThresholds.URGENT_HIGH_RANGE, unit) { urgentHigh = it }

        errors.forEach { problem ->
            Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(AlertThresholds(urgentLow, low, high, urgentHigh)) },
                enabled = changed && errors.isEmpty(),
            ) { Text("Save thresholds") }
            OutlinedButton(
                onClick = {
                    urgentLow = saved.urgentLowMgdl
                    low = saved.lowMgdl
                    high = saved.highMgdl
                    urgentHigh = saved.urgentHighMgdl
                },
                enabled = changed,
            ) { Text("Reset") }
        }
    }
}

@Composable
private fun ThresholdRow(
    label: String,
    value: Int,
    range: IntRange,
    unit: DisplayUnit,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(120.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onChange((value - STEP_MGDL).coerceIn(range)) },
                enabled = value - STEP_MGDL >= range.first,
            ) { Text("−") }
            Text(
                text = "${GlucoseFormat.value(value, unit)} ${GlucoseFormat.unitLabel(unit)}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.width(120.dp),
            )
            OutlinedButton(
                onClick = { onChange((value + STEP_MGDL).coerceIn(range)) },
                enabled = value + STEP_MGDL <= range.last,
            ) { Text("+") }
        }
    }
}
