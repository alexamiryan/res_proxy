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
import dev.resproxy.ui.SettingsPage
import dev.resproxy.ui.SettingsPageScreen
import dev.resproxy.ui.SettingsScreen
import dev.resproxy.vpn.KeepAlive
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
        setContent {
            ResProxyTheme {
                val ready by produceState(false) { container.ready.await(); value = true }
                if (!ready) return@ResProxyTheme
                val nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onSettings = { nav.navigate("settings") },
                            onAccount = { p -> nav.navigate("account/${p.name}") },
                            onVpnSettings = { nav.navigate("settings/${SettingsPage.VPN.name}") },
                        )
                    }
                    composable("account/{provider}") { entry ->
                        val provider = Provider.valueOf(entry.arguments?.getString("provider") ?: Provider.IPROYAL.name)
                        AccountScreen(provider, canGoBack = true) {
                            nav.popBackStack()
                        }
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { nav.popBackStack() },
                            onAccount = { p -> nav.navigate("account/${p.name}") },
                            onPage = { page -> nav.navigate("settings/${page.name}") },
                        )
                    }
                    composable("settings/{page}") { entry ->
                        val page = SettingsPage.valueOf(entry.arguments?.getString("page") ?: SettingsPage.VPN.name)
                        SettingsPageScreen(page, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    /** Opening the app (or coming back to it) restarts the tunnel if the user had it on and it isn't running. */
    override fun onResume() {
        super.onResume()
        restoreConnection()
    }

    private fun restoreConnection() = lifecycleScope.launch {
        KeepAlive.ensureRunning(this@MainActivity, "app opened")
    }
}
