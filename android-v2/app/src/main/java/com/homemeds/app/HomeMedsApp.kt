package com.homemeds.app

import android.app.Application

class HomeMedsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.homemeds.app.net.ApiClient.init(this)
        ReminderAlarms.createChannel(this)
        SyncWorker.schedulePeriodic(this)
    }
}
