package dev.resproxy.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.resproxy.data.DiagLog
import dev.resproxy.vpn.KeepAlive

/** Re-reads [KeepAlive.ignoresBatteryOptimizations] whenever the screen comes back (after the system dialog). */
@Composable
fun rememberBackgroundAllowed(): Boolean {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(KeepAlive.ignoresBatteryOptimizations(ctx)) }
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) { allowed = KeepAlive.ignoresBatteryOptimizations(ctx) }
    }
    return allowed
}

@SuppressLint("BatteryLife")
fun requestBackgroundUse(ctx: android.content.Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    runCatching { ctx.startActivity(direct) }.onFailure {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

@Composable
fun BackgroundUseRow() {
    val ctx = LocalContext.current
    val allowed = rememberBackgroundAllowed()
    SettingRow(
        "Run in the background",
        if (allowed) "Allowed: Android won't stop the tunnel to save battery"
        else "Restricted: Android may stop the tunnel when the phone is idle. Tap to allow",
        onClick = { requestBackgroundUse(ctx) },
    )
}

/** Home: shown while the tunnel should be on but Android may still stop it to save battery. */
@Composable
fun BackgroundUseCard() {
    val ctx = LocalContext.current
    if (rememberBackgroundAllowed()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Keep the tunnel on when the phone is idle", style = MaterialTheme.typography.titleSmall)
            Text(
                "Android may pause or stop ResProxy to save battery. Allow it to run in the background so it can " +
                    "reconnect on its own.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { requestBackgroundUse(ctx) }) { Text("Allow background use") }
        }
    }
}

@Composable
fun DiagnosticsSettings() {
    val clipboard = LocalClipboardManager.current
    var refresh by remember { mutableIntStateOf(0) }
    val text = remember(refresh) { DiagLog.read().lines().takeLast(400).joinToString("\n") }
    SettingsGroup("Stay connected") { BackgroundUseRow() }
    SettingsGroup("Connection log") {
        Hint("What the tunnel did and why: connects, drops, repairs, reconnects, and why Android ended the app. No passwords or keys.")
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { refresh++ }) { Text("Refresh") }
            TextButton(onClick = { clipboard.setText(AnnotatedString(DiagLog.read())) }) { Text("Copy all") }
            TextButton(onClick = { DiagLog.clear(); refresh++ }) { Text("Clear") }
        }
        Text(
            text.ifEmpty { "Nothing logged yet." },
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
        )
    }
}
