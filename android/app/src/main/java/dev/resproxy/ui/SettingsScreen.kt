package dev.resproxy.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import dev.resproxy.data.Gateway
import dev.resproxy.data.Profile
import dev.resproxy.data.Protocol
import dev.resproxy.data.Provider
import dev.resproxy.data.SplitMode
import dev.resproxy.data.TunnelMode
import dev.resproxy.data.Verdict
import dev.resproxy.provider.Presets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SettingsPage(val title: String) {
    VPN("VPN"), SPLIT("Split tunnelling"), PROTECTION("Protection"), REPUTATION("IP reputation"), BACKUP("Backup & transfer"), USAGE("Data usage"), ADVANCED("Advanced"), DIAGNOSTICS("Diagnostics")
}

/** Settings home: accounts plus one row per page. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onAccount: (Provider) -> Unit, onPage: (SettingsPage) -> Unit) {
    val c = LocalContext.current.container
    val settings by c.settings.settings.collectAsState(initial = null)
    val accounts by c.accounts.accounts.collectAsState(initial = emptyMap())
    val profiles by c.vpn.profiles.collectAsState(initial = emptyList())
    val mullvad by c.vpn.mullvad.collectAsState(initial = null)
    val s = settings ?: return

    Page("Settings", onBack) {
        SettingsGroup("Proxy accounts") {
            for (p in listOf(Provider.IPROYAL, Provider.GEONODE)) {
                val a = accounts[p]
                SettingRow(
                    p.label + if (p == s.provider) " · in use" else "",
                    // Pasted Geonode usernames carry targeting parameters (stripped at connect); show the base name.
                    if (a == null) "Not set up" else if (p == Provider.GEONODE) a.username.substringBefore('-') else a.username,
                    Icons.Default.AccountCircle,
                    onClick = { onAccount(p) },
                )
            }
        }
        SettingsGroup {
            val vpnSummary = listOfNotNull(
                mullvad?.let { "Mullvad" },
                profiles.size.takeIf { it > 0 }?.let { "$it profile" + if (it > 1) "s" else "" },
            ).joinToString(" · ").ifEmpty { "Not set up" }
            SettingRow("VPN", vpnSummary, Icons.Default.VpnLock, onClick = { onPage(SettingsPage.VPN) })
            GroupDivider()
            val cs = s.connection
            SettingRow(
                "Split tunnelling",
                when (cs.splitMode) {
                    SplitMode.ALL -> "All apps use the proxy"
                    SplitMode.ONLY_SELECTED -> "Only ${cs.apps.size} selected app" + if (cs.apps.size == 1) "" else "s"
                    SplitMode.EXCEPT_SELECTED -> "All apps except ${cs.apps.size}"
                },
                Icons.AutoMirrored.Filled.CallSplit, onClick = { onPage(SettingsPage.SPLIT) },
            )
            GroupDivider()
            SettingRow(
                "Protection",
                listOfNotNull(if (s.killSwitch) "Kill switch on" else "Kill switch off", "LAN bypassed".takeIf { s.bypassLan }).joinToString(" · "),
                Icons.Default.Shield, onClick = { onPage(SettingsPage.PROTECTION) },
            )
            GroupDivider()
            SettingRow(
                "IP reputation", if (s.autoRotate) "Auto-rotate until ${if (s.rotateThreshold == Verdict.SUSPICIOUS) "clean" else "not bad"}" else "Check only",
                Icons.Default.VerifiedUser, onClick = { onPage(SettingsPage.REPUTATION) },
            )
            GroupDivider()
            SettingRow(
                "Backup & transfer", "Copy this setup to other profiles or phones",
                Icons.Default.Backup, onClick = { onPage(SettingsPage.BACKUP) },
            )
            GroupDivider()
            SettingRow("Data usage", "Traffic per provider", Icons.Default.DataUsage, onClick = { onPage(SettingsPage.USAGE) })
            GroupDivider()
            SettingRow("Advanced", "Protocol, session, gateway, routing", Icons.Default.Tune, onClick = { onPage(SettingsPage.ADVANCED) })
            GroupDivider()
            SettingRow("Diagnostics", "Connection log: drops, repairs, reconnects", Icons.Default.BugReport, onClick = { onPage(SettingsPage.DIAGNOSTICS) })
        }
    }
}

@Composable
fun SettingsPageScreen(page: SettingsPage, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsState(initial = null)
    var pickingApps by remember { mutableStateOf(false) }
    val s = settings ?: return
    fun update(f: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(f) }
    fun updateConn(f: (ConnectionSettings) -> ConnectionSettings) = update { it.copy(connection = f(it.connection)) }

    if (pickingApps) {
        BackHandler { pickingApps = false }
        AppPickerScreen(selected = s.connection.apps, onDone = { apps -> updateConn { it.copy(apps = apps) }; pickingApps = false })
        return
    }

    Page(page.title, onBack) {
        when (page) {
            SettingsPage.VPN -> VpnSettings(s)
            SettingsPage.SPLIT -> SplitSettings(s, ::updateConn) { pickingApps = true }
            SettingsPage.PROTECTION -> {
                SettingsGroup {
                    SwitchRow("Kill switch", "Block traffic while connecting, repairing or when the proxy is down", s.killSwitch) { v ->
                        update { it.copy(killSwitch = v) }
                    }
                    GroupDivider()
                    SwitchRow("Reconnect automatically", "Restore the last connection when the app or phone restarts", s.autoConnect) { v ->
                        update { it.copy(autoConnect = v) }
                    }
                    GroupDivider()
                    SwitchRow("Bypass for local network", "Printers, casting, router pages and other LAN devices connect directly", s.bypassLan) { v ->
                        update { it.copy(bypassLan = v) }
                    }
                }
                SettingsGroup("Stay connected") {
                    BackgroundUseRow()
                }
                SettingsGroup("Always-on VPN") {
                    Hint("For protection even before the app starts, turn on Always-on VPN and \"Block connections without VPN\" for ResProxy.")
                    SettingRow("Open system VPN settings", onClick = { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) })
                }
                SettingsGroup("Connection health") {
                    Hint(
                        "ResProxy watches whether apps' connections actually get answers, the VPN handshake and the proxy. " +
                            "When something breaks while it still looks connected, it rebuilds the connection on its own; " +
                            "the kill switch keeps traffic blocked meanwhile.",
                    )
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        NumberField("Proxy probe interval (seconds)", s.healthIntervalSec) { v -> update { it.copy(healthIntervalSec = v) } }
                    }
                }
            }
            SettingsPage.REPUTATION -> ReputationSettings(s, ::update)
            SettingsPage.BACKUP -> {
                SettingsGroup { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { BackupSection() } }
            }
            SettingsPage.USAGE -> UsageSettings()
            SettingsPage.ADVANCED -> AdvancedSettings(s, ::updateConn)
            SettingsPage.DIAGNOSTICS -> DiagnosticsSettings()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReputationSettings(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var keys by remember { mutableStateOf<ApiKeys?>(null) }
    LaunchedEffect(Unit) { keys = c.settings.currentKeys() }
    // Save as you type (debounced); the running tunnel re-checks the IP when keys change.
    LaunchedEffect(keys) {
        val k = keys ?: return@LaunchedEffect
        delay(800)
        if (k != c.settings.currentKeys()) c.settings.setKeys(k)
    }

    SettingsGroup {
        SwitchRow("Check on connect", "Look up the exit IP every time you connect or change IP", s.recheckOnConnect) { v ->
            update { it.copy(recheckOnConnect = v) }
        }
        GroupDivider()
        SwitchRow("Auto-rotate bad IPs", "Switch to a new IP until the reputation is acceptable", s.autoRotate) { v ->
            update { it.copy(autoRotate = v) }
        }
        if (s.autoRotate) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Accept an IP only if it is", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(s.rotateThreshold == Verdict.SUSPICIOUS, { update { it.copy(rotateThreshold = Verdict.SUSPICIOUS) } }, label = { Text("Clean") })
                    FilterChip(s.rotateThreshold == Verdict.BAD, { update { it.copy(rotateThreshold = Verdict.BAD) } }, label = { Text("Not bad") })
                }
            }
        }
    }
    SettingsGroup("API keys") {
        Hint("ipapi.is, proxycheck.io, ip-api.com and DNS blacklists work without keys. Keys raise limits and add AbuseIPDB, IPQualityScore and Scamalytics.")
        keys?.let { k ->
            fun edit(f: (ApiKeys) -> ApiKeys) { keys = f(k) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyField("AbuseIPDB", k.abuseIpDb) { v -> edit { it.copy(abuseIpDb = v) } }
                KeyField("IPQualityScore", k.ipqs) { v -> edit { it.copy(ipqs = v) } }
                KeyField("proxycheck.io", k.proxycheck) { v -> edit { it.copy(proxycheck = v) } }
                KeyField("ipapi.is", k.ipapiIs) { v -> edit { it.copy(ipapiIs = v) } }
                OutlinedTextField(k.scamalyticsUser, { v -> edit { it.copy(scamalyticsUser = v.trim()) } },
                    label = { Text("Scamalytics username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                KeyField("Scamalytics key", k.scamalyticsKey) { v -> edit { it.copy(scamalyticsKey = v) } }
                Text("Saved automatically. While connected, the IP is re-checked with the new keys.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UsageSettings() {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val accounts by c.accounts.accounts.collectAsState(initial = emptyMap())
    val usage by c.stats.totals.collectAsState(initial = emptyMap())
    SettingsGroup {
        val shown = listOf(Provider.IPROYAL, Provider.GEONODE).filter { it in accounts || usage[it.name] != null }
        if (shown.isEmpty()) Hint("No traffic yet.")
        shown.forEachIndexed { i, p ->
            if (i > 0) GroupDivider()
            val u = usage[p.name]
            val rx = u?.rxBytes ?: 0; val tx = u?.txBytes ?: 0
            SettingRow(p.label, "${formatBytes(rx + tx)} total · ↓ ${formatBytes(rx)} ↑ ${formatBytes(tx)}", trailing = {
                TextButton(onClick = { scope.launch { c.stats.reset(p.name) } }) { Text("Reset", maxLines = 1) }
            })
        }
    }
    Hint("Measured on this device; providers' billing may count protocol overhead differently.", Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun AdvancedSettings(s: AppSettings, updateConn: ((ConnectionSettings) -> ConnectionSettings) -> Unit) {
    val c = LocalContext.current.container
    val accounts by c.accounts.accounts.collectAsState(initial = emptyMap())
    var gatewayPicks by remember { mutableStateOf(0) }
    val cs = s.connection

    SettingsGroup("Protocol") {
        Column(Modifier.padding(16.dp)) {
            Choice(Protocol.entries, cs.protocol, { it.label }) { v -> updateConn { it.copy(protocol = v) } }
        }
    }
    SettingsGroup("Session") {
        SwitchRow("Sticky IP", "Keep the same IP until you tap New IP or the lifetime ends", cs.sticky) { v -> updateConn { it.copy(sticky = v) } }
        if (cs.sticky) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            NumberField("Session lifetime (minutes)", cs.lifetimeMinutes) { v -> updateConn { it.copy(lifetimeMinutes = v) } }
            Hint("Max: IPRoyal 7 days (10080), Geonode 24 h (1440, applied automatically).", Modifier.padding(top = 4.dp))
        }
    }
    SettingsGroup("Gateway · ${s.provider.label}") {
        val preset = Presets.of(s.provider)
        val gw = cs.gateways[s.provider] ?: Gateway()
        fun setGw(g: Gateway) = updateConn { it.copy(gateways = it.gateways + (s.provider to g)) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.provider == Provider.GEONODE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "France", "us.proxy.geonode.io" to "US", "sg.proxy.geonode.io" to "Singapore").forEach { (h, label) ->
                        FilterChip(gw.host == h, { setGw(gw.copy(host = h)); gatewayPicks++ }, label = { Text(label) })
                    }
                }
            }
            key(s.provider, gatewayPicks) {
                TextSetting("Host", gw.host, placeholder = preset.defaultHost) { v -> setGw(gw.copy(host = v.trim())) }
            }
            key(s.provider) {
                NumberField("Port (0 = default ${preset.defaultPort(cs.protocol, cs.sticky)})", gw.port) { v -> setGw(gw.copy(port = v)) }
            }
            accounts[s.provider]?.let { CredentialPreview(Profile.from(it, s, s.sessionId.ifEmpty { "xxxxxxxx" }), it.password) }
        }
    }
    SettingsGroup("Routing") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(TunnelMode.entries, cs.tunnelMode, { if (it == TunnelMode.FULL) "All traffic" else "Proxy only" }) { v ->
                updateConn { it.copy(tunnelMode = v) }
            }
            Text(
                if (cs.tunnelMode == TunnelMode.FULL) "All TCP traffic and DNS go through the proxy (IPRoyal has no UDP; apps fall back to TCP)."
                else "Sets a system HTTP proxy (Android 10+). Only proxy-aware apps work; other traffic is blocked.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Hint("Changes apply to a running connection automatically.", Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SplitSettings(s: AppSettings, updateConn: ((ConnectionSettings) -> ConnectionSettings) -> Unit, onPickApps: () -> Unit) {
    val cs = s.connection
    SettingsGroup("Which apps use the proxy") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(SplitMode.entries, cs.splitMode, {
                when (it) { SplitMode.ALL -> "All apps"; SplitMode.ONLY_SELECTED -> "Only selected"; SplitMode.EXCEPT_SELECTED -> "All except" }
            }) { v -> updateConn { it.copy(splitMode = v) } }
            Text(
                when (cs.splitMode) {
                    SplitMode.ALL -> "Every app goes through the proxy."
                    SplitMode.ONLY_SELECTED -> "Only the apps you choose go through the proxy; the rest connect directly."
                    SplitMode.EXCEPT_SELECTED -> "The apps you choose connect directly; everything else goes through the proxy."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (cs.splitMode != SplitMode.ALL) {
            GroupDivider()
            SettingRow("Choose apps", "${cs.apps.size} selected", onClick = onPickApps)
        }
    }
    Hint("Changes apply to a running connection automatically.", Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun CredentialPreview(p: Profile, basePassword: String) {
    val ep = Presets.build(p)
    val masked = if (basePassword.isNotEmpty() && ep.password.startsWith(basePassword)) "••••••" + ep.password.removePrefix(basePassword) else "••••••"
    Column(Modifier.padding(top = 4.dp)) {
        Text("Generated credentials", style = MaterialTheme.typography.labelMedium)
        Text("${ep.protocol.label.lowercase()}://${ep.host}:${ep.port}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text("user: ${ep.username}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text("pass: $masked", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

/** Keeps its own text state: settings are persisted asynchronously, so binding straight to them races typing. */
@Composable
internal fun TextSetting(label: String, value: String, placeholder: String = "", onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        text, { v -> text = v; onChange(v) }, singleLine = true,
        label = { Text(label) }, placeholder = { Text(placeholder) }, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
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
