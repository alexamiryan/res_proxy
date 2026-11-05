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
import dev.resproxy.net.LatencyProbe
import androidx.compose.material.icons.filled.Speed
import java.text.DateFormat
import java.util.Date
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
    var signingOutHere by remember { mutableStateOf(false) }
    var mullvadNotice by remember { mutableStateOf<String?>(null) }
    // Opening the page checks that this install's device still exists on the account.
    LaunchedEffect(mullvad?.device?.id) {
        if (mullvad?.device != null && c.verifyMullvadDevice() == false) {
            mullvadNotice = "This device was removed from your Mullvad account (on mullvad.net or by another install). Log in to register a new one."
        }
    }
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
            // All 5 slots taken: let the user see which installs hold them and free one.
            if (error?.contains("5 devices") == true && MullvadClient.normalize(number).length >= 10) {
                GroupDivider()
                MullvadDevices(MullvadClient.normalize(number), thisDeviceId = null, startOpen = true)
            }
        } else {
            val masked = "•••• " + acc.number.takeLast(4)
            val device = acc.device
            SettingRow(
                "Account $masked",
                listOfNotNull(
                    when {
                        device != null -> "Signed in as device ${device.name.ifBlank { device.id.take(8) }}"
                        acc.signedOut -> "Signed out on this phone profile"
                        else -> "Signs in on first connect"
                    },
                    acc.expiry.takeIf { it.isNotBlank() }?.let { "paid until ${it.take(10)}" },
                ).joinToString(" · "),
            )
            mullvadNotice?.let { Hint(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            GroupDivider()
            val login = {
                busy = true; error = null
                scope.launch {
                    runCatching { c.mullvadLogin(); c.mullvad.refreshRelays(force = true) }
                        .onSuccess { mullvadNotice = null }.onFailure { error = it.message }
                    busy = false
                }
                Unit
            }
            val spinner: @Composable () -> Unit = { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
            if (device == null) {
                SettingRow("Log in", "Registers this phone profile as a new device on the account", Icons.Default.Refresh, onClick = login, trailing = spinner)
            } else {
                SettingRow("Refresh account and server list", icon = Icons.Default.Refresh, onClick = login, trailing = spinner)
            }
            GroupDivider()
            MullvadDevices(acc.number, thisDeviceId = device?.id)
            if (device != null) {
                GroupDivider()
                SettingRow("Sign out on this phone profile", "Forgets this device here; it stays on your account. Then log in as a new device.",
                    onClick = { signingOutHere = true })
            }
            GroupDivider()
            SettingRow("Remove Mullvad account", "Removes this device from the account and forgets the account here", Icons.Default.Delete, onClick = { removingMullvad = true })
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

    if (profiles.isNotEmpty() || mullvad != null) LatencySection(s, profiles, mullvad != null)

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
    if (signingOutHere) {
        AlertDialog(
            onDismissRequest = { signingOutHere = false },
            title = { Text("Sign out on this phone profile?") },
            text = { Text("Forgets the Mullvad device “${mullvad?.device?.name.orEmpty()}” here. It stays on your account, so other profiles or browsers sharing it keep working. Log in again to register this profile as a new device.") },
            confirmButton = {
                TextButton(onClick = {
                    signingOutHere = false
                    scope.launch { c.signOutMullvadHere(); mullvadNotice = null }
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { signingOutHere = false }) { Text("Cancel") } },
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

/**
 * The account's devices. Every install registers its own: each Android profile, each browser with the extension.
 * Mullvad names them (e.g. "Tidy Owl"); [thisDeviceId] marks this install's. Others can be removed to free a slot;
 * an install whose device was removed registers a new one when it next connects.
 */
@Composable
private fun MullvadDevices(number: String, thisDeviceId: String?, startOpen: Boolean = false) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(startOpen) }
    var devices by remember { mutableStateOf<List<dev.resproxy.net.MullvadDeviceInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<dev.resproxy.net.MullvadDeviceInfo?>(null) }
    fun load() {
        loadError = null
        scope.launch { runCatching { c.mullvad.devices(number) }.onSuccess { devices = it }.onFailure { loadError = it.message } }
    }
    LaunchedEffect(open, number) { if (open && devices == null) load() }
    SettingRow(
        "Devices on this account" + (devices?.let { " (${it.size}/5)" } ?: ""),
        if (open) null else "See which installs use your 5 slots",
        if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
        onClick = { open = !open },
    )
    if (!open) return
    when {
        loadError != null -> Hint(loadError!!, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        devices == null -> Hint("Loading…", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        else -> devices!!.forEach { d ->
            val mine = d.id == thisDeviceId
            SettingRow(
                d.name.ifBlank { d.id.take(8) },
                listOfNotNull(if (mine) "This phone profile" else null, d.created.takeIf { it.isNotBlank() }?.let { "added ${it.take(10)}" })
                    .joinToString(" · "),
                if (mine) Icons.Default.Check else null,
                trailing = { if (!mine) IconButton(onClick = { removing = d }) { Icon(Icons.Default.Delete, "Remove ${d.name}") } },
            )
        }
    }
    removing?.let { d ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove “${d.name}”?") },
            text = { Text("Frees a slot on your Mullvad account. Installs using this device (other phone profiles or browsers) are signed out; they can log in again as a new device.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch {
                        runCatching { c.mullvad.removeDevice(number, d.id) }.onFailure { loadError = it.message }
                        load()
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** Pings every Mullvad server and imported profile from the phone and lists them fastest first. */
@Composable
private fun LatencySection(s: AppSettings, profiles: List<VpnProfile>, hasMullvad: Boolean) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val probe by c.latency.state.collectAsState()
    val relays by c.mullvad.relays.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    val targets = remember(profiles, relays, hasMullvad) { LatencyProbe.targets(profiles, if (hasMullvad) relays else emptyList()) }
    val selected = if (s.chainEnabled) s.vpnExit else ""

    SettingsGroup("Fastest servers") {
        SettingRow(
            if (probe.running) "Testing ${probe.done} of ${probe.total}…" else "Test latency to all servers",
            if (probe.finishedAt > 0 && !probe.running) {
                "${probe.results.count { it.value != null }} of ${probe.results.size} answered · tested " +
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(probe.finishedAt))
            } else "Pings ${targets.size} servers directly from this phone",
            Icons.Default.Speed,
            onClick = if (probe.running) null else ({
                scope.launch {
                    val list = if (hasMullvad) runCatching { c.mullvad.refreshRelays() }.getOrDefault(relays) else emptyList()
                    c.latency.run(LatencyProbe.targets(profiles, list))
                }
            }),
            trailing = { if (probe.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, null) },
        )
        val ranked = targets.mapNotNull { t -> probe.results[t.exit]?.let { t to it } }.sortedBy { it.second }
        val shown = if (showAll) ranked else ranked.take(10)
        shown.forEach { (t, ms) ->
            GroupDivider()
            SettingRow(
                (t.countryCode?.let { flagEmoji(it) + "  " } ?: "") + t.label,
                t.detail,
                if (t.exit == selected) Icons.Default.Check else null,
                onClick = { scope.launch { c.settings.update { it.copy(chainEnabled = true, vpnExit = t.exit) } } },
                trailing = { LatencyText(ms) },
            )
        }
        if (ranked.size > 10) {
            GroupDivider()
            SettingRow(if (showAll) "Show top 10" else "Show all ${ranked.size}", onClick = { showAll = !showAll }, trailing = {})
        }
        targets.filter { it.exit.startsWith("wg:") && probe.results.containsKey(it.exit) && probe.results[it.exit] == null }.forEach { t ->
            GroupDivider()
            SettingRow(t.label, "${t.detail} · doesn't answer ping (may still work)", trailing = { Text("—") })
        }
    }
}

@Composable
private fun LatencyText(ms: Long) {
    val color = when {
        ms < 60 -> StatusColors.connected
        ms < 150 -> StatusColors.pending
        else -> StatusColors.down
    }
    Text("$ms ms", color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
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
    val ms = c.latency.state.collectAsState().value.results
    fun best(rs: List<MullvadRelay>) = rs.mapNotNull { ms[VpnExit.mullvad(it.hostname)] }.minOrNull()
    fun withMs(text: String, v: Long?) = if (v == null) text else "$text · $v ms"

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
                // Fastest first once latencies were measured (Settings → VPN), otherwise alphabetical.
                val byCountry = relays.groupBy { it.countryCode }.entries
                    .sortedWith(compareBy({ best(it.value) ?: Long.MAX_VALUE }, { it.value.first().country }))
                    .associate { it.key to it.value }
                LazyColumn(Modifier.fillMaxSize()) {
                    if (q.isEmpty()) {
                        item { PickRow("Off", "Connect to the proxy directly", selected == "", bold = true) { onPick(false, s.vpnExit) } }
                        if (profiles.isNotEmpty()) item { GroupLabel("My WireGuard profiles") }
                        items(profiles, key = { "wg" + it.id }) { p ->
                            val code = VpnExit.imported(p.id)
                            PickRow(p.name, ms[code]?.let { "$it ms" }, selected == code) { onPick(true, code) }
                        }
                    }
                    if (mullvad == null) {
                        item { Hint("Add a Mullvad account or import WireGuard configs in Settings → VPN.", Modifier.padding(16.dp)) }
                    } else {
                        item { GroupLabel(if (relays.isEmpty()) "Mullvad (loading servers…)" else "Mullvad") }
                        byCountry.forEach { (cc, list) ->
                            val country = list.first().country
                            val cities = list.groupBy { it.location }.entries
                                .sortedWith(compareBy({ best(it.value) ?: Long.MAX_VALUE }, { it.value.first().city }))
                                .associate { it.key to it.value }
                            val countryHit = q.isEmpty() || country.lowercase().contains(q) || cc == q
                            val cityHits = cities.filter { (_, rs) -> q.isNotEmpty() && rs.first().city.lowercase().contains(q) }
                            val serverHits = if (q.length >= 3) list.filter { it.hostname.contains(q) } else emptyList()
                            if (!countryHit && cityHits.isEmpty() && serverHits.isEmpty()) return@forEach
                            val open = cc in expanded || (q.isNotEmpty() && !countryHit)
                            item(key = "mc$cc") {
                                PickRow(
                                    "${flagEmoji(cc)}  $country", withMs("${cities.size} cit${if (cities.size == 1) "y" else "ies"} · ${list.size} servers", best(list)),
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
                                        PickRow("      ${rs.first().city}", withMs("      ${rs.size} servers", best(rs)), selected == VpnExit.mullvad(loc)) {
                                            onPick(true, VpnExit.mullvad(loc))
                                        }
                                    }
                                }
                            }
                            serverHits.forEach { r ->
                                item(key = "mh${r.hostname}") {
                                    PickRow("      ${r.hostname}", withMs("      ${r.city}" + if (r.owned) " · Mullvad-owned" else "", ms[VpnExit.mullvad(r.hostname)]), selected == VpnExit.mullvad(r.hostname)) {
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
