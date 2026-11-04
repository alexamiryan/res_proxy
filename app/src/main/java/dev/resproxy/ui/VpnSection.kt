package dev.resproxy.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.resproxy.container
import dev.resproxy.data.AppSettings
import dev.resproxy.data.VpnExit
import dev.resproxy.data.VpnProfile
import dev.resproxy.net.MullvadClient
import dev.resproxy.net.MullvadRelay
import dev.resproxy.net.WireGuard
import dev.resproxy.net.WireGuardConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → VPN: Mullvad account and imported WireGuard profiles. */
@Composable
fun VpnSettings(s: AppSettings) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val profiles by c.vpn.profiles.collectAsState(initial = emptyList())
    val mullvad by c.vpn.mullvad.collectAsState(initial = null)
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<VpnProfile?>(null) }
    var removingMullvad by remember { mutableStateOf(false) }
    val exit = VpnExit.parse(s.vpnExit)

    if (!WireGuard.available) {
        Hint("This build doesn't include WireGuard.")
        return
    }

    fun import(name: String, text: String) {
        val cfg = runCatching { WireGuardConfig.parse(text) }
        cfg.exceptionOrNull()?.let { error = it.message; return }
        error = null
        scope.launch {
            val p = VpnProfile(name = name.ifBlank { cfg.getOrThrow().endpoint }, config = text)
            c.vpn.upsert(p)
            if (!s.chainEnabled || s.vpnExit.isEmpty()) c.settings.update { it.copy(chainEnabled = true, vpnExit = VpnExit.imported(p.id)) }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (name, text) = withContext(Dispatchers.IO) {
                val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) cur.getString(0) else null
                }.orEmpty().substringBeforeLast('.')
                name to (ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: "")
            }
            import(name, text)
        }
    }

    Hint(
        "Chain the proxy through a VPN: apps → ResProxy → VPN → proxy. Your network only sees VPN traffic and the proxy " +
            "never sees your real IP. Pick the VPN server on the home screen. Keep the VPN provider's own app disconnected — " +
            "Android allows one VPN app, so ResProxy runs WireGuard itself.",
        Modifier.padding(horizontal = 16.dp),
    )

    SettingsGroup("Mullvad") {
        val acc = mullvad
        if (acc == null) {
            var number by remember { mutableStateOf("") }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    number, { number = it.filter { ch -> ch.isDigit() || ch == ' ' }.take(24) },
                    label = { Text("Mullvad account number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = MullvadClient.normalize(number).length >= 10 && !busy,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching {
                                c.setMullvadNumber(number)
                                c.mullvadLogin()
                                c.mullvad.refreshRelays(force = true)
                            }.onFailure { e -> error = e.message; c.vpn.saveMullvad(null) }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Signing in…" else "Add Mullvad account") }
                Hint("Registers this phone profile as one of your account's 5 devices.", Modifier)
            }
        } else {
            val masked = "•••• " + acc.number.takeLast(4)
            SettingRow(
                "Account $masked",
                listOfNotNull(
                    acc.device?.name?.takeIf { it.isNotBlank() }?.let { "Device: $it" } ?: "Signs in on first connect",
                    acc.expiry.takeIf { it.isNotBlank() }?.let { "paid until ${it.take(10)}" },
                ).joinToString(" · "),
            )
            GroupDivider()
            SettingRow("Refresh account and server list", icon = Icons.Default.Refresh, onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { c.mullvadLogin(); c.mullvad.refreshRelays(force = true) }.onFailure { error = it.message }
                    busy = false
                }
            }, trailing = { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) })
            GroupDivider()
            SettingRow("Remove Mullvad account", "Also removes this device from the account", Icons.Default.Delete, onClick = { removingMullvad = true })
        }
    }

    SettingsGroup("WireGuard profiles") {
        profiles.forEach { p ->
            val cfg = remember(p.config) { runCatching { WireGuardConfig.parse(p.config) }.getOrNull() }
            val inUse = s.chainEnabled && exit == VpnExit.Imported(p.id)
            SettingRow(
                p.name,
                cfg?.endpoint ?: "Invalid config",
                if (inUse) Icons.Default.Check else null,
                onClick = { scope.launch { c.settings.update { it.copy(chainEnabled = true, vpnExit = VpnExit.imported(p.id)) } } },
                trailing = { IconButton(onClick = { deleting = p }) { Icon(Icons.Default.Delete, "Delete") } },
            )
            GroupDivider()
        }
        SettingRow("Import .conf file", "From Proton, Mullvad, AirVPN, your own server…", Icons.Default.FileOpen, onClick = { picker.launch(arrayOf("*/*")) })
        GroupDivider()
        SettingRow("Paste config", icon = Icons.Default.ContentPaste, onClick = { pasting = true })
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }

    if (pasting) {
        var name by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text("Paste WireGuard config") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. Proton NL)") }, singleLine = true)
                    OutlinedTextField(
                        text, { text = it }, label = { Text("[Interface] … [Peer] …") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.heightIn(min = 160.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { import(name, text); pasting = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text("Cancel") } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${p.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        c.vpn.remove(p.id)
                        c.settings.update { if (VpnExit.parse(it.vpnExit) == VpnExit.Imported(p.id)) it.copy(chainEnabled = false, vpnExit = "") else it }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    if (removingMullvad) {
        AlertDialog(
            onDismissRequest = { removingMullvad = false },
            title = { Text("Remove Mullvad account?") },
            text = { Text("This phone profile is removed from your Mullvad devices. Other profiles keep theirs.") },
            confirmButton = {
                TextButton(onClick = {
                    removingMullvad = false
                    scope.launch {
                        val acc = c.vpn.mullvad()
                        acc?.device?.let { d -> runCatching { c.mullvad.removeDevice(acc.number, d.id) } }
                        c.vpn.saveMullvad(null)
                        c.settings.update { if (VpnExit.parse(it.vpnExit) is VpnExit.Mullvad) it.copy(chainEnabled = false, vpnExit = "") else it }
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removingMullvad = false }) { Text("Cancel") } },
        )
    }
}

/** Human label for the current VPN choice. */
fun vpnLabel(s: AppSettings, profiles: List<VpnProfile>, relays: List<MullvadRelay>): String {
    if (!s.chainEnabled) return "Off"
    return when (val e = VpnExit.parse(s.vpnExit)) {
        is VpnExit.Imported -> profiles.firstOrNull { it.id == e.id }?.name ?: "Removed profile"
        is VpnExit.Mullvad -> "Mullvad · " + MullvadClient.label(e.code, relays)
        null -> "Not chosen"
    }
}

/** Full-screen picker: no VPN, an imported profile, or a Mullvad country / city / server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnPickerDialog(s: AppSettings, onDismiss: () -> Unit, onManage: () -> Unit, onPick: (enabled: Boolean, exit: String) -> Unit) {
    val c = LocalContext.current.container
    val profiles by c.vpn.profiles.collectAsState(initial = emptyList())
    val mullvad by c.vpn.mullvad.collectAsState(initial = null)
    val relays by c.mullvad.relays.collectAsState()
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(mullvad != null) { if (mullvad != null) runCatching { c.mullvad.refreshRelays() } }
    val selected = if (s.chainEnabled) s.vpnExit else ""

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("VPN") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = { TextButton(onClick = onManage) { Text("Manage") } },
                )
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                if (mullvad != null) OutlinedTextField(
                    query, { query = it }, singleLine = true, label = { Text("Search Mullvad countries, cities, servers") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                val q = query.trim().lowercase()
                val byCountry = relays.groupBy { it.countryCode }.toSortedMap(compareBy { relays.first { r -> r.countryCode == it }.country })
                LazyColumn(Modifier.fillMaxSize()) {
                    if (q.isEmpty()) {
                        item { PickRow("Off", "Connect to the proxy directly", selected == "", bold = true) { onPick(false, s.vpnExit) } }
                        if (profiles.isNotEmpty()) item { GroupLabel("My WireGuard profiles") }
                        items(profiles, key = { "wg" + it.id }) { p ->
                            val code = VpnExit.imported(p.id)
                            PickRow(p.name, null, selected == code) { onPick(true, code) }
                        }
                    }
                    if (mullvad == null) {
                        item { Hint("Add a Mullvad account or import WireGuard configs in Settings → VPN.", Modifier.padding(16.dp)) }
                    } else {
                        item { GroupLabel(if (relays.isEmpty()) "Mullvad (loading servers…)" else "Mullvad") }
                        byCountry.forEach { (cc, list) ->
                            val country = list.first().country
                            val cities = list.groupBy { it.location }
                            val countryHit = q.isEmpty() || country.lowercase().contains(q) || cc == q
                            val cityHits = cities.filter { (_, rs) -> q.isNotEmpty() && rs.first().city.lowercase().contains(q) }
                            val serverHits = if (q.length >= 3) list.filter { it.hostname.contains(q) } else emptyList()
                            if (!countryHit && cityHits.isEmpty() && serverHits.isEmpty()) return@forEach
                            val open = cc in expanded || (q.isNotEmpty() && !countryHit)
                            item(key = "mc$cc") {
                                PickRow(
                                    "${flagEmoji(cc)}  $country", "${cities.size} cit${if (cities.size == 1) "y" else "ies"} · ${list.size} servers",
                                    selected == VpnExit.mullvad(cc),
                                    trailing = {
                                        IconButton(onClick = { expanded = if (cc in expanded) expanded - cc else expanded + cc }) {
                                            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Cities")
                                        }
                                    },
                                ) { onPick(true, VpnExit.mullvad(cc)) }
                            }
                            if (open || cityHits.isNotEmpty()) {
                                val shown = if (open && (countryHit || q.isEmpty())) cities else cityHits
                                shown.forEach { (loc, rs) ->
                                    item(key = "ml$loc") {
                                        PickRow("      ${rs.first().city}", "      ${rs.size} servers", selected == VpnExit.mullvad(loc)) {
                                            onPick(true, VpnExit.mullvad(loc))
                                        }
                                    }
                                }
                            }
                            serverHits.forEach { r ->
                                item(key = "mh${r.hostname}") {
                                    PickRow("      ${r.hostname}", "      ${r.city}" + if (r.owned) " · Mullvad-owned" else "", selected == VpnExit.mullvad(r.hostname)) {
                                        onPick(true, VpnExit.mullvad(r.hostname))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(t: String) {
    Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun PickRow(
    title: String, subtitle: String?, selected: Boolean, bold: Boolean = false,
    trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (bold || selected) FontWeight.SemiBold else FontWeight.Normal)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (selected) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
            trailing?.invoke()
        }
        HorizontalDivider()
    }
}
