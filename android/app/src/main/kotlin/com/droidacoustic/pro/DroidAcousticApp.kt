package com.droidacoustic.pro

import android.app.Application
import android.util.Log
import com.google.android.filament.utils.Utils

/**
 * Application-level singleton.
 * Responsible for one-time initialisation that must happen before any
 * Activity starts (e.g. loading the native library).
 */
class DroidAcousticApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Load Filament's native library before any Engine is created.
        // Must be called on the main thread once per process.
        Utils.init()
        Log.i(TAG, "DroidAcoustic Pro starting — Phase 0")
    }

    companion object {
        private const val TAG = "DroidAcousticApp"
    }
}
