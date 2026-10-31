package com.homemeds.app

import android.app.Application

class HomeMedsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ReminderAlarms.createChannel(this)
        SyncWorker.schedulePeriodic(this)
    }
}
