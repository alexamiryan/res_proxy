package dev.resproxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dev.resproxy.data.ProfileRepository
import dev.resproxy.data.SecretStore
import dev.resproxy.data.SettingsRepository
import dev.resproxy.data.StatsRepository
import dev.resproxy.reputation.ReputationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val Context.dataStore by preferencesDataStore("resproxy")

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secrets = SecretStore()
    val profiles = ProfileRepository(context.dataStore, secrets)
    val settings = SettingsRepository(context.dataStore, secrets)
    val stats = StatsRepository(context.dataStore)
    val reputation = ReputationRepository(settings)
}

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_VPN, "VPN status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "IP reputation alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CHANNEL_VPN = "vpn"
        const val CHANNEL_ALERTS = "alerts"
    }
}

val Context.container: AppContainer get() = (applicationContext as App).container
