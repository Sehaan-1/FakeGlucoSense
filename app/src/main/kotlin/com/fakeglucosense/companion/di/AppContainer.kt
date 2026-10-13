package com.fakeglucosense.companion.di

import android.content.Context
import androidx.room.Room
import com.fakeglucosense.companion.data.ble.BleCgmClient
import com.fakeglucosense.companion.data.ble.BleScanner
import com.fakeglucosense.companion.data.db.GlucoseDatabase
import com.fakeglucosense.companion.data.settings.SettingsStore
import com.fakeglucosense.companion.data.source.GlucoseSourceFactory
import com.fakeglucosense.companion.domain.GlucoseRepository
import com.fakeglucosense.companion.domain.LiveStatus
import com.fakeglucosense.companion.notify.AppNotifications

/**
 * Manual dependency container (ADR-0008). It is small enough that a DI framework would add more
 * code than it removes. Each dependency is created once per process.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database: GlucoseDatabase by lazy {
        Room.databaseBuilder(appContext, GlucoseDatabase::class.java, GlucoseDatabase.NAME).build()
    }

    val repository: GlucoseRepository by lazy { GlucoseRepository(database.glucoseDao()) }
    val settingsStore: SettingsStore = SettingsStore(appContext)
    val notifications: AppNotifications = AppNotifications(appContext)
    val live: LiveStatus = LiveStatus()
    val bleScanner: BleScanner = BleScanner(appContext)
    val sourceFactory: GlucoseSourceFactory = GlucoseSourceFactory(BleCgmClient(appContext))
}
