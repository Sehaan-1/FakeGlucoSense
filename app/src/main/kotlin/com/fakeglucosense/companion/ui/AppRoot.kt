package com.fakeglucosense.companion.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fakeglucosense.companion.FakeGlucoSenseApp
import com.fakeglucosense.companion.R
import com.fakeglucosense.companion.data.ble.BlePermissions
import com.fakeglucosense.companion.data.settings.AppSettings
import com.fakeglucosense.companion.di.AppContainer
import com.fakeglucosense.companion.service.GlucoseMonitorService
import com.fakeglucosense.companion.ui.dashboard.DashboardScreen
import com.fakeglucosense.companion.ui.settings.SettingsScreen

/** The process-wide container, reachable from any composable. */
@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as FakeGlucoSenseApp).container

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val container = appContainer()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val running by container.live.running.collectAsStateWithLifecycle()

    RequestNotificationPermission()

    // Monitoring is a user choice that survives restarts. Resume it when the UI comes back.
    LaunchedEffect(settings.monitoringEnabled) {
        if (settings.monitoringEnabled && !container.live.running.value) {
            if (BlePermissions.missingForMonitoring(context).isEmpty()) {
                GlucoseMonitorService.start(context)
            } else {
                // Without the permission the service cannot go foreground. Record monitoring as off, so
                // the Start button (which asks for the permission) matches what is actually running.
                container.settingsStore.update { it.copy(monitoringEnabled = false) }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
            )
        },
        bottomBar = {
            Text(
                text = stringResource(R.string.disclaimer_simulated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Glucose") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Settings") })
            }
            when (tab) {
                0 -> DashboardScreen()
                else -> SettingsScreen()
            }
        }
    }
}

/** Asks once for notification permission (API 33+). Without it the foreground notification is hidden. */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Runs once per entry into composition. The system dialog itself is only shown when needed.
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
