package com.example.cameratranslator

import android.app.Application
import com.example.cameratranslator.core.DiagnosticLog
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CameraTranslatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.initialize(this)
    }
}
