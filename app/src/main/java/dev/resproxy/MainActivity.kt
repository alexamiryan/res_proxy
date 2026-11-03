package dev.resproxy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.resproxy.ui.HomeScreen
import dev.resproxy.ui.ProfileEditScreen
import dev.resproxy.ui.ProfilesScreen
import dev.resproxy.ui.ResProxyTheme
import dev.resproxy.ui.SettingsScreen

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
                val nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onProfiles = { nav.navigate("profiles") },
                            onSettings = { nav.navigate("settings") },
                            onNewProfile = { nav.navigate("profile/new") },
                        )
                    }
                    composable("profiles") {
                        ProfilesScreen(
                            onBack = { nav.popBackStack() },
                            onEdit = { id -> nav.navigate("profile/$id") },
                        )
                    }
                    composable("profile/{id}") { entry ->
                        ProfileEditScreen(
                            profileId = entry.arguments?.getString("id")?.takeIf { it != "new" },
                            onDone = { nav.popBackStack() },
                        )
                    }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}
