package com.fakeglucosense.companion.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fakeglucosense.core.model.AlertThresholds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Preferences backed by DataStore (ADR-0006). Reads are a Flow; writes are read-modify-write
 * transactions, so two writers cannot interleave a partial update.
 */
class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs.writeSettings(transform(prefs.toSettings()))
        }
    }

    private object Keys {
        val URGENT_LOW = intPreferencesKey("urgent_low")
        val LOW = intPreferencesKey("low")
        val HIGH = intPreferencesKey("high")
        val URGENT_HIGH = intPreferencesKey("urgent_high")
        val SOURCE = stringPreferencesKey("source")
        val TCP_HOST = stringPreferencesKey("tcp_host")
        val TCP_PORT = intPreferencesKey("tcp_port")
        val BLE_ADDRESS = stringPreferencesKey("ble_address")
        val BLE_NAME = stringPreferencesKey("ble_name")
        val UNIT = stringPreferencesKey("unit")
        val MONITORING = booleanPreferencesKey("monitoring")
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        // A corrupt or out-of-range stored threshold set falls back to defaults rather than crashing.
        val thresholds = runCatching {
            AlertThresholds(
                urgentLowMgdl = this[Keys.URGENT_LOW] ?: defaults.thresholds.urgentLowMgdl,
                lowMgdl = this[Keys.LOW] ?: defaults.thresholds.lowMgdl,
                highMgdl = this[Keys.HIGH] ?: defaults.thresholds.highMgdl,
                urgentHighMgdl = this[Keys.URGENT_HIGH] ?: defaults.thresholds.urgentHighMgdl,
            )
        }.getOrDefault(defaults.thresholds)
        return AppSettings(
            thresholds = thresholds,
            sourceKind = this[Keys.SOURCE]?.let { runCatching { SourceKind.valueOf(it) }.getOrNull() }
                ?: defaults.sourceKind,
            tcpHost = this[Keys.TCP_HOST] ?: defaults.tcpHost,
            tcpPort = this[Keys.TCP_PORT] ?: defaults.tcpPort,
            bleAddress = this[Keys.BLE_ADDRESS],
            bleName = this[Keys.BLE_NAME],
            displayUnit = this[Keys.UNIT]?.let { runCatching { DisplayUnit.valueOf(it) }.getOrNull() }
                ?: defaults.displayUnit,
            monitoringEnabled = this[Keys.MONITORING] ?: defaults.monitoringEnabled,
        )
    }

    private fun MutablePreferences.writeSettings(s: AppSettings) {
        this[Keys.URGENT_LOW] = s.thresholds.urgentLowMgdl
        this[Keys.LOW] = s.thresholds.lowMgdl
        this[Keys.HIGH] = s.thresholds.highMgdl
        this[Keys.URGENT_HIGH] = s.thresholds.urgentHighMgdl
        this[Keys.SOURCE] = s.sourceKind.name
        this[Keys.TCP_HOST] = s.tcpHost
        this[Keys.TCP_PORT] = s.tcpPort
        writeOrRemove(Keys.BLE_ADDRESS, s.bleAddress)
        writeOrRemove(Keys.BLE_NAME, s.bleName)
        this[Keys.UNIT] = s.displayUnit.name
        this[Keys.MONITORING] = s.monitoringEnabled
    }

    private fun MutablePreferences.writeOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }
}
