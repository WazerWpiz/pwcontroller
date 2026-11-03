package com.pwcontroller

import android.app.Application
import com.pwcontroller.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PWControllerApplication : Application() {

    companion object {
        lateinit var instance: PWControllerApplication
            private set
    }

    val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var preferenceManager: PreferenceManager

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferenceManager = PreferenceManager(this)
    }
}