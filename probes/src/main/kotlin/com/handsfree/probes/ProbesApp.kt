package com.handsfree.probes

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ProbesApp : Application() {
    @Inject lateinit var controller: ProbeController
}
