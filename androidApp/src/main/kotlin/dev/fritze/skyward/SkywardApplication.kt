package dev.fritze.skyward

import android.app.Application
import androidx.work.Configuration
import dev.fritze.skyward.alarm.NotificationChannels
import dev.fritze.skyward.alarm.SkywardWorkerFactory
import dev.fritze.skyward.data.AppContainer

class SkywardApplication : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationChannels.createAll(this)
        container.ensureDefaultRulesSeeded()
        container.scheduleBackgroundWork()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(SkywardWorkerFactory(container))
            .build()
}
