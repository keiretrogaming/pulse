package com.kei.pulse

import android.app.Application
import android.content.Context

class PulseApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: PulseApp
            private set

        val context: Context
            get() = instance.applicationContext
    }
}
