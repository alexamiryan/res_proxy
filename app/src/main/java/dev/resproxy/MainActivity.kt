package dev.resproxy

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.resproxy.data.Provider
import dev.resproxy.ui.AccountScreen
import dev.resproxy.ui.HomeScreen
import dev.resproxy.ui.ResProxyTheme
import dev.resproxy.ui.SettingsScreen
import dev.resproxy.vpn.TunnelController
import dev.resproxy.vpn.TunnelState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) restoreConnection()
        setContent {
            ResProxyTheme {
                val ready by produceState(false) { container.ready.await(); value = true }
                if (!ready) return@ResProxyTheme
                val nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(onSettings = { nav.navigate("settings") }, onAccount = { p -> nav.navigate("account/${p.name}") })
                    }
                    composable("account/{provider}") { entry ->
                        val provider = Provider.valueOf(entry.arguments?.getString("provider") ?: Provider.IPROYAL.name)
                        AccountScreen(provider, canGoBack = true) {
                            nav.popBackStack()
                        }
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() }, onAccount = { p -> nav.navigate("account/${p.name}") })
                    }
                }
            }
        }
    }

    /** Reconnects on app start if the user was connected and didn't disconnect. */
    private fun restoreConnection() = lifecycleScope.launch {
        container.ready.await()
        val s = container.settings.current()
        if (s.autoConnect && s.wasConnected && !TunnelState.status.value.isActive &&
            container.accounts.get(s.provider) != null && VpnService.prepare(this@MainActivity) == null
        ) {
            TunnelController.connect(this@MainActivity)
        }
    }
}
