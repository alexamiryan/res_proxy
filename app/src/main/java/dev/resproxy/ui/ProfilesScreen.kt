package dev.resproxy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.resproxy.container
import dev.resproxy.data.AppSettings
import dev.resproxy.data.Profile
import dev.resproxy.data.Protocol
import dev.resproxy.data.Provider
import dev.resproxy.data.SplitMode
import dev.resproxy.data.TunnelMode
import dev.resproxy.provider.Presets
import dev.resproxy.vpn.TunnelState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(onBack: () -> Unit, onEdit: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by ctx.container.profiles.profiles.collectAsState(initial = emptyList())
    val settings by ctx.container.settings.settings.collectAsState(initial = AppSettings())
    var confirmDelete by remember { mutableStateOf<Profile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profiles") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { onEdit("new") }) { Icon(Icons.Default.Add, "Add profile") } },
    ) { pad ->
        if (profiles.isEmpty()) {
            Text("No profiles yet. Tap + to add your IPRoyal, Geonode or custom proxy.", Modifier.padding(pad).padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles, key = { it.id }) { p ->
                val selected = p.id == (settings.selectedProfileId ?: profiles.firstOrNull()?.id)
                Card(Modifier.fillMaxWidth().clickable {
                    scope.launch { ctx.container.settings.update { it.copy(selectedProfileId = p.id) } }
                }) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = {
                            scope.launch { ctx.container.settings.update { it.copy(selectedProfileId = p.id) } }
                        })
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${p.provider.label} · ${p.protocol.label}" +
                                    (p.country.takeIf { it.isNotBlank() }?.let { " · ${flagEmoji(it)} ${it.uppercase()}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { onEdit(p.id) }) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = { confirmDelete = p }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text("The saved credentials for this profile will be removed.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { ctx.container.profiles.delete(p.id) }; confirmDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(profileId: String?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<Profile?>(null) }
    var pickingApps by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(profileId) {
        draft = ctx.container.profiles.get(profileId) ?: Profile(name = "IPRoyal")
    }
    val p = draft ?: return

    if (pickingApps) {
        BackHandler { pickingApps = false }
        AppPickerScreen(
            selected = p.apps,
            onDone = { apps -> draft = p.copy(apps = apps); pickingApps = false },
        )
        return
    }

    val preset = Presets.of(p.provider)
    fun set(f: (Profile) -> Profile) { draft = f(p) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (profileId == null) "New profile" else "Edit profile") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            ctx.container.profiles.save(p)
                            if (profileId == null) ctx.container.settings.update { it.copy(selectedProfileId = p.id) }
                            onDone()
                        }
                    }) { Text("Save") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(p.name, { v -> set { it.copy(name = v) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Section("Provider")
            Choice(Provider.entries, p.provider, { it.label }) { v -> set { it.copy(provider = v, host = "", port = 0) } }
            Choice(Protocol.entries, p.protocol, { it.label }) { v -> set { it.copy(protocol = v, port = 0) } }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    p.host, { v -> set { it.copy(host = v.trim()) } }, singleLine = true,
                    label = { Text("Host") }, placeholder = { Text(preset.defaultHost.ifEmpty { "proxy.example.com" }) },
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    if (p.port > 0) p.port.toString() else "", { v -> set { it.copy(port = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) } },
                    singleLine = true, label = { Text("Port") }, placeholder = { Text(preset.defaultPort(p.protocol, p.sticky).toString()) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
            }
            if (p.provider != Provider.GENERIC) {
                Text("Leave host/port empty for the preset defaults; change them if your dashboard shows different ones.",
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(p.username, { v -> set { it.copy(username = v) } }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                p.password, { v -> set { it.copy(password = v) } }, label = { Text("Password") }, singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show password")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (p.provider != Provider.GENERIC) {
                Section("Targeting")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(p.country, { v -> set { it.copy(country = v.take(2)) } }, label = { Text("Country") },
                        placeholder = { Text("us") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(p.state, { v -> set { it.copy(state = v) } }, label = { Text("State") }, singleLine = true, modifier = Modifier.weight(1.5f))
                    OutlinedTextField(p.city, { v -> set { it.copy(city = v) } }, label = { Text("City") }, singleLine = true, modifier = Modifier.weight(1.5f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sticky session")
                        Text("Keep the same IP until you tap New IP or it expires", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(p.sticky, { v -> set { it.copy(sticky = v, port = 0) } })
                }
                if (p.sticky) {
                    OutlinedTextField(
                        p.lifetimeMinutes.toString(), { v -> set { it.copy(lifetimeMinutes = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 1) } },
                        label = { Text("Session lifetime (minutes)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                }
                CredentialPreview(p)
            }

            Section("Routing")
            Choice(TunnelMode.entries, p.tunnelMode, { if (it == TunnelMode.FULL) "Full tunnel" else "Proxy only" }) { v -> set { it.copy(tunnelMode = v) } }
            Text(
                if (p.tunnelMode == TunnelMode.FULL) "All TCP/UDP and DNS of the selected apps goes through the proxy."
                else "Sets a system HTTP proxy (Android 10+). Only proxy-aware apps work; other traffic is blocked.",
                style = MaterialTheme.typography.bodySmall,
            )
            Choice(SplitMode.entries, p.splitMode, {
                when (it) { SplitMode.ALL -> "All apps"; SplitMode.ONLY_SELECTED -> "Only selected"; SplitMode.EXCEPT_SELECTED -> "All except" }
            }) { v -> set { it.copy(splitMode = v) } }
            if (p.splitMode != SplitMode.ALL) {
                OutlinedButton(onClick = { pickingApps = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose apps (${p.apps.size} selected)")
                }
            }
            if (TunnelState.status.value.profileId == p.id && TunnelState.status.value.isActive) {
                Text("Reconnect for changes to take effect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    ctx.container.profiles.save(p)
                    if (profileId == null) ctx.container.settings.update { it.copy(selectedProfileId = p.id) }
                    onDone()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}

@Composable
private fun CredentialPreview(p: Profile) {
    val ep = Presets.build(if (p.sticky && p.sessionId.isEmpty()) p.copy(sessionId = "xxxxxxxx") else p)
    val base = p.password.trim()
    val maskedPass = if (ep.password.startsWith(base) && base.isNotEmpty()) "•".repeat(6) + ep.password.removePrefix(base) else "•".repeat(6)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Generated credentials", style = MaterialTheme.typography.labelMedium)
            Text("${ep.protocol.label.lowercase()}://${ep.host}:${ep.port}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("user: ${ep.username}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("pass: $maskedPass", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(Presets.of(p.provider).formatHint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label(o), maxLines = 1) }
        }
    }
}
