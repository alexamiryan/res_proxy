package dev.resproxy.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.resproxy.container
import dev.resproxy.data.AppSettings
import dev.resproxy.data.SplitMode
import dev.resproxy.data.TailnetSettings
import dev.resproxy.data.TunnelMode
import dev.resproxy.net.TailnetStatus
import dev.resproxy.vpn.Phase
import dev.resproxy.vpn.TailnetController
import dev.resproxy.vpn.TunnelController
import dev.resproxy.vpn.TunnelState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** One-line state for the settings hub. */
fun tailnetSummary(s: TailnetSettings, st: TailnetStatus?): String = when {
    !s.enabled -> "Off"
    st == null -> "On · not running"
    st.running -> listOfNotNull(st.dnsName.substringBefore('.').ifEmpty { null }, st.ips.firstOrNull()).joinToString(" · ").ifEmpty { "Connected" }
    st.needsLogin -> "Needs login"
    else -> "Connecting…"
}

/** Settings → Tailnet: this phone as a device in the user's tailnet, without the Tailscale app. */
@Composable
fun TailnetSettingsPage(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val ui by c.tailnet.ui.collectAsState()
    val tunnel by TunnelState.status.collectAsState()
    val t = s.tailnet
    fun edit(f: (TailnetSettings) -> TailnetSettings) = update { it.copy(tailnet = f(it.tailnet)) }
    var confirmLogout by remember { mutableStateOf(false) }
    var logoutError by remember { mutableStateOf<String?>(null) }
    // With the proxy off the tailnet runs as its own VPN, which needs Android's VPN permission like the proxy does.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) TunnelController.resume(ctx)
    }
    fun start() = VpnService.prepare(ctx)?.let { consent.launch(it) } ?: TunnelController.resume(ctx)

    SettingsGroup {
        SwitchRow("Join my tailnet", "This phone becomes a device in your Tailscale network, with the proxy on or off. No Tailscale app needed", t.enabled) { v ->
            scope.launch {
                c.settings.update { it.copy(tailnet = it.tailnet.copy(enabled = v)) }
                if (v) start()
            }
        }
    }
    Hint(
        "Tailnet devices are reached directly. Everything else goes through the proxy while it's on, and directly while " +
            "it's off. Android runs one VPN at a time, so turn off the Tailscale app's VPN on this phone.",
        Modifier.padding(horizontal = 16.dp),
    )

    if (t.enabled) {
        val st = ui.status
        SettingsGroup("Status") {
            when {
                ui.error != null -> SettingRow("Couldn't start", ui.error)
                (tunnel.phase == Phase.DISCONNECTED || tunnel.phase == Phase.ERROR) && st == null -> {
                    SettingRow("Not running", "Start it to connect this phone to the tailnet")
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Button(onClick = ::start) { Text("Start") } }
                }
                st == null -> SettingRow("Starting…")
                st.needsLogin -> {
                    SettingRow("Log in to add this phone", "Approve it in your browser, or paste an auth key below")
                    if (st.authUrl.isNotEmpty()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Button(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(st.authUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                                Text("Log in")
                            }
                        }
                    }
                }
                st.running -> {
                    SettingRow(st.dnsName.ifEmpty { "Connected" }, st.ips.joinToString(" · "))
                    if (st.keyExpiry > 0) {
                        GroupDivider()
                        SettingRow("Key expires", DateFormat.getDateInstance().format(Date(st.keyExpiry)) + " · then log in again (or disable key expiry in the admin console)")
                    }
                }
                else -> SettingRow("Connecting…", st.state)
            }
            if (st != null && st.error.isNotEmpty() && !st.running) Hint(st.error)
            if (tunnel.phase == Phase.TAILNET_ONLY && tunnel.message != null) Hint(tunnel.message!!)
        }

        if (st != null && st.running) {
            SettingsGroup("Devices") {
                if (st.peers.isEmpty()) Hint("No other devices in this tailnet yet.")
                st.peers.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider()
                    SettingRow(
                        p.shortName + if (p.online) "" else " · offline",
                        listOfNotNull(p.ips.firstOrNull(), p.os.ifEmpty { null }).joinToString(" · "),
                    )
                }
            }
            Hint("Apps reach devices by name (${st.peers.firstOrNull()?.shortName ?: "laptop"}, or the full name) or by their 100.x address.", Modifier.padding(horizontal = 16.dp))
        }

        SettingsGroup("This device") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextSetting("Device name", t.hostname, TailnetController.defaultHostname()) { v -> edit { it.copy(hostname = v.trim()) } }
                AuthKeyField()
                TextSetting("Coordination server", t.controlUrl, "Tailscale (default)") { v -> edit { it.copy(controlUrl = v.trim()) } }
                Text(
                    "Set the server only for Headscale. A new name or server restarts the tailnet connection.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsGroup("Reachable from the tailnet") {
            SwitchRow("Accept connections", "Devices can open services on this phone that listen on all interfaces (SSH in Termux, Syncthing, …)", t.acceptInbound) { v ->
                edit { it.copy(acceptInbound = v) }
            }
            GroupDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PortsField(t.loopbackPorts) { ports -> edit { it.copy(loopbackPorts = ports) } }
                Text(
                    "Services that listen only on 127.0.0.1 are private to the phone. List a port here only to share it with your tailnet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val notes = buildList {
            if (s.connection.splitMode != SplitMode.ALL) add("While the proxy is on, only apps that use the tunnel (Split tunnelling) can reach tailnet devices.")
            if (s.connection.tunnelMode == TunnelMode.PROXY_ONLY) add("While the proxy is on in proxy-only mode, only apps that use the system proxy reach tailnet devices.")
            add("To stay reachable while the phone sleeps, allow Protection → Run in the background.")
        }
        notes.forEach { Hint(it, Modifier.padding(horizontal = 16.dp)) }

        SettingsGroup {
            SettingRow("Log out of the tailnet", "Removes this phone from the tailnet and forgets its key", onClick = { confirmLogout = true })
        }
        logoutError?.let { Hint("Logged out here, but the server didn't confirm ($it). Remove the device in the admin console if it's still listed.", Modifier.padding(horizontal = 16.dp)) }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out of the tailnet?") },
            text = { Text("This phone leaves the tailnet. Turning it on again adds it as a new device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    c.appScope.launch { logoutError = c.tailnet.logout() }
                }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

/** Auth key (secret, saved as you type, never shown again in full). */
@Composable
private fun AuthKeyField() {
    val c = LocalContext.current.container
    var key by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { key = c.tailnetSecrets.get().authKey }
    LaunchedEffect(key) {
        val k = key ?: return@LaunchedEffect
        delay(800)
        if (k.trim() != c.tailnetSecrets.get().authKey) c.tailnetSecrets.setAuthKey(k)
    }
    key?.let { k ->
        OutlinedTextField(
            k, { key = it }, label = { Text("Auth key (optional)") }, placeholder = { Text("tskey-auth-…") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PortsField(ports: Set<Int>, onChange: (Set<Int>) -> Unit) {
    var text by remember { mutableStateOf(ports.sorted().joinToString(", ")) }
    OutlinedTextField(
        text, { v ->
            text = v.filter { it.isDigit() || it == ',' || it == ' ' }
            onChange(parsePorts(text))
        },
        label = { Text("Also share these 127.0.0.1 ports") }, placeholder = { Text("e.g. 8022, 8384") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
}

internal fun parsePorts(text: String): Set<Int> =
    text.split(',', ' ').mapNotNull { it.trim().toIntOrNull()?.takeIf { p -> p in 1..65535 } }.toSortedSet()
