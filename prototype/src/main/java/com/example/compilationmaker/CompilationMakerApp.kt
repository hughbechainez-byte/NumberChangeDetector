package com.example.compilationmaker

import android.app.Application

class CompilationMakerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashRecorder(this)
        UpdateNotifier.ensureChannel(this)
        UpdateScheduler.scheduleBackgroundChecks(this)
    }
}
