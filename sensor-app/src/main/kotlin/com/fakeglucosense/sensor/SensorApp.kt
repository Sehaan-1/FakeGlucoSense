package com.fakeglucosense.sensor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.fakeglucosense.sensor.service.SensorStatus

class SensorApp : Application() {

    /** Shared status between the service (writer) and the screen (reader). Not persisted. */
    val status: SensorStatus by lazy { SensorStatus() }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SENSOR,
                getString(R.string.channel_sensor),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_sensor_desc) },
        )
    }

    companion object {
        const val CHANNEL_SENSOR = "simulated_sensor"
        const val NOTIFICATION_ID = 7
    }
}
