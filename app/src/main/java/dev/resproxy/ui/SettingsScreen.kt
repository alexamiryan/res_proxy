package dev.resproxy.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.resproxy.container
import dev.resproxy.data.ApiKeys
import dev.resproxy.data.AppSettings
import dev.resproxy.data.Verdict
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ctx.container
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    val profiles by c.profiles.profiles.collectAsState(initial = emptyList())
    val usage by c.stats.totals.collectAsState(initial = emptyMap())
    var keys by remember { mutableStateOf<ApiKeys?>(null) }
    var keysSaved by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { keys = c.settings.currentKeys() }

    fun update(f: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header("Connection")
            Toggle("Connect on boot", "Reconnect the last profile after the phone restarts", settings.autoConnectOnBoot) { v ->
                update { it.copy(autoConnectOnBoot = v) }
            }
            NumberField("Health check interval (seconds)", settings.healthIntervalSec) { v -> update { it.copy(healthIntervalSec = v) } }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Kill switch", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "While connected, traffic is blocked if the proxy goes down. To also block traffic while the app " +
                            "isn't running, enable Always-on VPN and \"Block connections without VPN\" for ResProxy.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }) { Text("Open system VPN settings") }
                }
            }

            Header("IP reputation")
            Toggle("Check on connect", "Look up the exit IP each time you connect or rotate", settings.recheckOnConnect) { v ->
                update { it.copy(recheckOnConnect = v) }
            }
            Toggle("Auto-rotate bad IPs", "Get a new sticky session until the IP passes the threshold", settings.autoRotate) { v ->
                update { it.copy(autoRotate = v) }
            }
            if (settings.autoRotate) {
                Text("Rotate when the verdict is at least", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Verdict.SUSPICIOUS, Verdict.BAD).forEach { v ->
                        FilterChip(settings.rotateThreshold == v, { update { it.copy(rotateThreshold = v) } }, label = { Text(verdictLabel(v)) })
                    }
                }
                NumberField("Max rotations per connect", settings.maxRotations) { v -> update { it.copy(maxRotations = v) } }
            }

            Header("API keys (optional)")
            Text(
                "ipapi.is, proxycheck.io, ip-api.com and DNS blacklists work without keys. Adding keys raises limits " +
                    "and enables AbuseIPDB, IPQualityScore and Scamalytics. Keys are stored encrypted on this device.",
                style = MaterialTheme.typography.bodySmall,
            )
            keys?.let { k ->
                fun edit(f: (ApiKeys) -> ApiKeys) { keys = f(k); keysSaved = false }
                KeyField("AbuseIPDB", k.abuseIpDb) { v -> edit { it.copy(abuseIpDb = v) } }
                KeyField("IPQualityScore", k.ipqs) { v -> edit { it.copy(ipqs = v) } }
                KeyField("proxycheck.io", k.proxycheck) { v -> edit { it.copy(proxycheck = v) } }
                KeyField("ipapi.is", k.ipapiIs) { v -> edit { it.copy(ipapiIs = v) } }
                OutlinedTextField(k.scamalyticsUser, { v -> edit { it.copy(scamalyticsUser = v.trim()) } },
                    label = { Text("Scamalytics username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                KeyField("Scamalytics key", k.scamalyticsKey) { v -> edit { it.copy(scamalyticsKey = v) } }
                Button(onClick = { scope.launch { c.settings.setKeys(k); keysSaved = true } }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (keysSaved) "Saved" else "Save keys")
                }
            }

            Header("Data used")
            if (profiles.isEmpty()) Text("No profiles", style = MaterialTheme.typography.bodySmall)
            profiles.forEach { p ->
                val u = usage[p.id]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name)
                        Text(
                            "↓ ${formatBytes(u?.rxBytes ?: 0)}  ↑ ${formatBytes(u?.txBytes ?: 0)}  total ${formatBytes((u?.rxBytes ?: 0) + (u?.txBytes ?: 0))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = { scope.launch { c.stats.reset(p.id) } }) { Text("Reset") }
                }
            }
            Text("Measured on-device; your provider's billing may count protocol overhead differently.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Header(t: String) {
    Column {
        HorizontalDivider(Modifier.padding(bottom = 8.dp))
        Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text, { v -> text = v.filter(Char::isDigit).take(4); text.toIntOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.trim()) }, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
    )
}
