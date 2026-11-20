package com.example.displayconnect

import android.app.Application
import com.example.displayconnect.network.BleNavClient
import com.example.displayconnect.storage.SettingsRepository

class DisplayConnectApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var altitudeMonitor: com.example.displayconnect.navigation.AltitudeMonitor
        private set

    lateinit var navClient: BleNavClient
        private set

    lateinit var navigationTrace: com.example.displayconnect.navigation.FileNavigationTrace
        private set

    override fun onCreate() {
        super.onCreate()
        com.example.displayconnect.utils.AppLanguage.load(this)
        settingsRepository = SettingsRepository(this)
        navigationTrace = com.example.displayconnect.navigation.FileNavigationTrace(java.io.File(noBackupFilesDir, "navigation-trace"))
        navClient = BleNavClient(this)
        altitudeMonitor = com.example.displayconnect.navigation.AltitudeMonitor(this)
    }

    override fun onTerminate() {
        navClient.release()
        super.onTerminate()
    }
}
