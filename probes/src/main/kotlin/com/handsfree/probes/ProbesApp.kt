package com.handsfree.probes

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
open class ProbesApp : Application() {
    @Inject lateinit var controller: ProbeController

    override fun onCreate() {
        super.onCreate()
        controller.recoverMicrophoneMeasurement()
        controller.exports.recoverOnStartup()
    }
}
