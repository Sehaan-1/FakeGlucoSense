package com.fakeglucosense.companion

import android.app.Application
import com.fakeglucosense.companion.di.AppContainer
import com.fakeglucosense.companion.work.WorkScheduler

class FakeGlucoSenseApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Channels must exist before any notification is posted, including from the service.
        container.notifications.createChannels()
        WorkScheduler.ensureScheduled(this)
    }
}
