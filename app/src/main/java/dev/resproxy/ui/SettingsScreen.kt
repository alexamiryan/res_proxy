package dev.resproxy.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.resproxy.container
import dev.resproxy.data.ApiKeys
import dev.resproxy.data.AppSettings
import dev.resproxy.data.ConnectionSettings
import dev.resproxy.data.Profile
import dev.resproxy.data.Protocol
import dev.resproxy.data.Provider
import dev.resproxy.data.SplitMode
import dev.resproxy.data.TunnelMode
import dev.resproxy.data.Verdict
import dev.resproxy.provider.Presets
import dev.resproxy.vpn.TunnelState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onAccount: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ctx.container
    val settings by c.settings.settings.collectAsState(initial = null)
    val account by c.accounts.account(Provider.IPROYAL).collectAsState(initial = null)
    val usage by c.stats.totals.collectAsState(initial = emptyMap())
    val status by TunnelState.status.collectAsState()
    var keys by remember { mutableStateOf<ApiKeys?>(null) }
    var keysSaved by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var pickingApps by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { keys = c.settings.currentKeys() }

    fun update(f: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(f) }
    fun updateConn(f: (ConnectionSettings) -> ConnectionSettings) = update { it.copy(connection = f(it.connection)) }

    val s = settings ?: return
    if (pickingApps) {
        BackHandler { pickingApps = false }
        AppPickerScreen(selected = s.connection.apps, onDone = { apps -> updateConn { it.copy(apps = apps) }; pickingApps = false })
        return
    }

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
            Header("Account")
            Row(Modifier.fillMaxWidth().clickable(onClick = onAccount), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("IPRoyal")
                    Text(
                        account?.let { a -> a.username + if (a.apiToken.isNotBlank()) " · API token set" else " · no API token" } ?: "Not set up",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onAccount) { Text("Edit") }
            }

            Header("Protection")
            Toggle("Kill switch", "Block all traffic while connecting, rotating or when the proxy is down", s.killSwitch) { v ->
                update { it.copy(killSwitch = v) }
            }
            Toggle("Reconnect automatically", "Restore the last connection when the app or phone restarts", s.autoConnect) { v ->
                update { it.copy(autoConnect = v) }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "For protection even while the app is starting, enable Always-on VPN and " +
                            "\"Block connections without VPN\" for ResProxy in system settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }) { Text("Open system VPN settings") }
                }
            }

            Header("IP reputation")
            Toggle("Check on connect", "Look up the exit IP every time you connect or change IP", s.recheckOnConnect) { v ->
                update { it.copy(recheckOnConnect = v) }
            }
            Toggle("Auto-rotate bad IPs", "Keep switching to a new IP until the reputation is acceptable", s.autoRotate) { v ->
                update { it.copy(autoRotate = v) }
            }
            if (s.autoRotate) {
                Text("Accept an IP only if it is", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(s.rotateThreshold == Verdict.SUSPICIOUS, { update { it.copy(rotateThreshold = Verdict.SUSPICIOUS) } },
                        label = { Text("Clean") })
                    FilterChip(s.rotateThreshold == Verdict.BAD, { update { it.copy(rotateThreshold = Verdict.BAD) } },
                        label = { Text("Not bad") })
                }
                NumberField("Max IPs to try per connect", s.maxRotations) { v -> update { it.copy(maxRotations = v) } }
            }
            Text(
                "ipapi.is, proxycheck.io, ip-api.com and DNS blacklists work without keys. Keys raise limits and add " +
                    "AbuseIPDB, IPQualityScore and Scamalytics.",
                style = MaterialTheme.typography.bodySmall,
            )
            keys?.let { k ->
                fun edit(f: (ApiKeys) -> ApiKeys) { keys = f(k); keysSaved = false }
                KeyField("AbuseIPDB key", k.abuseIpDb) { v -> edit { it.copy(abuseIpDb = v) } }
                KeyField("IPQualityScore key", k.ipqs) { v -> edit { it.copy(ipqs = v) } }
                KeyField("proxycheck.io key", k.proxycheck) { v -> edit { it.copy(proxycheck = v) } }
                KeyField("ipapi.is key", k.ipapiIs) { v -> edit { it.copy(ipapiIs = v) } }
                OutlinedTextField(k.scamalyticsUser, { v -> edit { it.copy(scamalyticsUser = v.trim()) } },
                    label = { Text("Scamalytics username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                KeyField("Scamalytics key", k.scamalyticsKey) { v -> edit { it.copy(scamalyticsKey = v) } }
                Button(onClick = { scope.launch { c.settings.setKeys(k); keysSaved = true } }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (keysSaved) "Saved" else "Save keys")
                }
            }

            Header("Data used")
            val u = usage[Provider.IPROYAL.name]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("IPRoyal")
                    Text(
                        "↓ ${formatBytes(u?.rxBytes ?: 0)}  ↑ ${formatBytes(u?.txBytes ?: 0)}  total ${formatBytes((u?.rxBytes ?: 0) + (u?.txBytes ?: 0))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = { scope.launch { c.stats.reset(Provider.IPROYAL.name) } }) { Text("Reset") }
            }
            Text("Measured on-device; IPRoyal's billing may count protocol overhead differently.", style = MaterialTheme.typography.labelSmall)

            HorizontalDivider()
            Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }, verticalAlignment = Alignment.CenterVertically) {
                Text("Advanced", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (advanced) {
                val cs = s.connection
                if (status.isActive) {
                    Text("Changes here apply on the next connect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Section("Protocol")
                Choice(Protocol.entries, cs.protocol, { it.label }) { v -> updateConn { it.copy(protocol = v) } }
                Section("Session")
                Toggle("Sticky IP", "Keep the same IP until you tap New IP or the lifetime ends", cs.sticky) { v ->
                    updateConn { it.copy(sticky = v) }
                }
                if (cs.sticky) NumberField("Session lifetime (minutes)", cs.lifetimeMinutes) { v -> updateConn { it.copy(lifetimeMinutes = v) } }
                Section("Gateway")
                val preset = Presets.of(s.provider)
                OutlinedTextField(cs.hostOverride, { v -> updateConn { it.copy(hostOverride = v.trim()) } }, singleLine = true,
                    label = { Text("Host") }, placeholder = { Text(preset.defaultHost) }, modifier = Modifier.fillMaxWidth())
                NumberField("Port (0 = default ${preset.defaultPort(cs.protocol, cs.sticky)})", cs.portOverride) { v ->
                    updateConn { it.copy(portOverride = v) }
                }
                Section("Routing")
                Choice(TunnelMode.entries, cs.tunnelMode, { if (it == TunnelMode.FULL) "All traffic" else "Proxy only" }) { v ->
                    updateConn { it.copy(tunnelMode = v) }
                }
                Text(
                    if (cs.tunnelMode == TunnelMode.FULL) "All TCP traffic and DNS go through the proxy (IPRoyal has no UDP; apps fall back to TCP)."
                    else "Sets a system HTTP proxy (Android 10+). Only proxy-aware apps work; other traffic is blocked.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Choice(SplitMode.entries, cs.splitMode, {
                    when (it) { SplitMode.ALL -> "All apps"; SplitMode.ONLY_SELECTED -> "Only selected"; SplitMode.EXCEPT_SELECTED -> "All except" }
                }) { v -> updateConn { it.copy(splitMode = v) } }
                if (cs.splitMode != SplitMode.ALL) {
                    OutlinedButton(onClick = { pickingApps = true }, modifier = Modifier.fillMaxWidth()) { Text("Choose apps (${cs.apps.size} selected)") }
                }
                NumberField("Health check interval (seconds)", s.healthIntervalSec) { v -> update { it.copy(healthIntervalSec = v) } }
                account?.let { CredentialPreview(Profile.from(it, s, s.sessionId.ifEmpty { "xxxxxxxx" }), it.password) }
            }
        }
    }
}

@Composable
private fun CredentialPreview(p: Profile, basePassword: String) {
    val ep = Presets.build(p)
    val masked = if (basePassword.isNotEmpty() && ep.password.startsWith(basePassword)) "••••••" + ep.password.removePrefix(basePassword) else "••••••"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Generated proxy credentials", style = MaterialTheme.typography.labelMedium)
            Text("${ep.protocol.label.lowercase()}://${ep.host}:${ep.port}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("user: ${ep.username}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("pass: $masked", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
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
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text, { v -> text = v.filter(Char::isDigit).take(5); text.toIntOrNull()?.let(onChange) },
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
