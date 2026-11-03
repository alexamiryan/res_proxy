package dev.resproxy.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.resproxy.container
import dev.resproxy.data.AppSettings
import dev.resproxy.data.VpnChain
import dev.resproxy.net.WireGuard
import dev.resproxy.net.WireGuardConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings block for chaining the proxy connection through a WireGuard VPN. */
@Composable
fun ChainSection(s: AppSettings, connected: Boolean) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val chain by c.chain.chain.collectAsState(initial = null)
    var error by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }

    fun import(name: String, text: String) {
        val cfg = runCatching { WireGuardConfig.parse(text) }
        cfg.exceptionOrNull()?.let { error = it.message; return }
        error = null
        scope.launch {
            c.chain.save(VpnChain(name.ifBlank { cfg.getOrThrow().endpoint }, text))
            c.settings.update { it.copy(chainEnabled = true) }
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

    Text(
        "Connect to IPRoyal through your own VPN: apps → ResProxy → VPN → IPRoyal. Your network only sees " +
            "VPN traffic and IPRoyal never sees your real IP. Export a WireGuard config from your VPN provider " +
            "(Proton: account.protonvpn.com → Downloads → WireGuard). Keep the provider's own app disconnected — " +
            "Android allows only one VPN app, so ResProxy runs WireGuard itself.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (!WireGuard.available) {
        Text("This build doesn't include WireGuard.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        return
    }
    val current = chain
    if (current != null) {
        val cfg = remember(current.config) { runCatching { WireGuardConfig.parse(current.config) }.getOrNull() }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(current.name, style = MaterialTheme.typography.titleSmall)
                cfg?.let {
                    Text("Endpoint ${it.endpoint}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("Address ${it.addresses.joinToString()}  DNS ${it.dns.joinToString().ifEmpty { "1.1.1.1" }}",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Toggle(
        "Route through my VPN",
        if (current == null) "Import a WireGuard config first" else "Proxy connection goes through ${current.name}",
        s.chainEnabled && current != null,
    ) { v -> if (current != null) scope.launch { c.settings.update { it.copy(chainEnabled = v) } } }
    if (connected) Text("Reconnect for changes to take effect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("Import .conf") }
        OutlinedButton(onClick = { pasting = true }, modifier = Modifier.weight(1f)) { Text("Paste") }
        if (current != null) {
            OutlinedButton(onClick = {
                scope.launch { c.chain.save(null); c.settings.update { it.copy(chainEnabled = false) } }
            }, modifier = Modifier.weight(1f)) { Text("Remove") }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

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
}
