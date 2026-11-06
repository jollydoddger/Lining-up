package com.jollydoddger.liningup

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notify.channels(this)
        Sync.schedule(this)
        Reminder.schedule(this)
    }
}
