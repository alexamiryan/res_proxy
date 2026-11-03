package dev.resproxy.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.resproxy.container
import dev.resproxy.data.BackupCrypto
import dev.resproxy.data.BackupPayload
import dev.resproxy.vpn.TunnelController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private enum class BackupDialog { EXPORT, IMPORT }

/** Settings block: passphrase-encrypted export / import of all settings and credentials. */
@Composable
fun BackupSection() {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<BackupDialog?>(null) }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<BackupPayload?>(null) }
    var sealed by remember { mutableStateOf<String?>(null) }

    fun reset() { dialog = null; pass = ""; pass2 = ""; busy = false; sealed = null }

    /** Encrypts once per dialog; PBKDF2 is deliberately slow. */
    suspend fun sealedText(): String = sealed ?: withContext(Dispatchers.Default) {
        BackupCrypto.seal(c.exportBackup(), pass.toCharArray())
    }.also { sealed = it }

    fun decrypt(text: String?) {
        if (text.isNullOrBlank()) { message = "Nothing to import"; return }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { BackupCrypto.open(text, pass.toCharArray()) } }
            busy = false
            result.onSuccess { pending = it; reset() }.onFailure { message = it.message }
        }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = sealedText()
            withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            message = "Backup saved"; reset()
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
            decrypt(text)
        }
    }

    Text(
        "Move this setup to another Android profile or phone: accounts, locations, VPN chain, API keys and " +
            "settings. The backup is encrypted with a passphrase you choose. Profiles can't see each other's files, " +
            "so use Share (e.g. Quick Share, a messenger or Drive) or Copy, then Import on the other side.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { dialog = BackupDialog.EXPORT }, modifier = Modifier.weight(1f)) { Text("Export") }
        OutlinedButton(onClick = { dialog = BackupDialog.IMPORT }, modifier = Modifier.weight(1f)) { Text("Import") }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

    when (dialog) {
        BackupDialog.EXPORT -> {
            val valid = pass.length >= 8 && pass == pass2
            AlertDialog(
                onDismissRequest = ::reset,
                title = { Text("Export backup") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Contains your proxy passwords and VPN key. You'll need this passphrase to import it.",
                            style = MaterialTheme.typography.bodySmall)
                        PassField("Passphrase (8+ characters)", pass) { pass = it; sealed = null }
                        PassField("Repeat passphrase", pass2) { pass2 = it }
                        if (busy) CircularProgressIndicator()
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(enabled = valid && !busy, onClick = { saveFile.launch("resproxy-backup.txt") }) { Text("Save file") }
                            TextButton(enabled = valid && !busy, onClick = {
                                busy = true
                                scope.launch {
                                    val text = sealedText()
                                    ctx.startActivity(Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share backup"))
                                    reset()
                                }
                            }) { Text("Share") }
                            TextButton(enabled = valid && !busy, onClick = {
                                busy = true
                                scope.launch { copySensitive(ctx, sealedText()); message = "Backup copied"; reset() }
                            }) { Text("Copy") }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = ::reset) { Text("Cancel") } },
            )
        }
        BackupDialog.IMPORT -> AlertDialog(
            onDismissRequest = ::reset,
            title = { Text("Import backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PassField("Passphrase", pass) { pass = it }
                    if (busy) CircularProgressIndicator()
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = pass.isNotEmpty() && !busy, onClick = { openFile.launch(arrayOf("*/*")) }) { Text("Open file") }
                        TextButton(enabled = pass.isNotEmpty() && !busy, onClick = {
                            val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
                            decrypt(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString())
                        }) { Text("Paste") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = ::reset) { Text("Cancel") } },
        )
        null -> Unit
    }

    pending?.let { b ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Replace current setup?") },
            text = {
                Text(
                    "Backup from ${DateFormat.getDateTimeInstance().format(Date(b.createdAt))}:\n" +
                        "• Accounts: ${b.accounts.joinToString { it.provider.label }.ifEmpty { "none" }}\n" +
                        "• Location: ${b.settings.provider.label}, ${b.settings.location.label}\n" +
                        "• VPN chain: ${b.chain?.name ?: "none"}\n\n" +
                        "This replaces all accounts and settings here and disconnects."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        TunnelController.disconnect(ctx)
                        c.importBackup(b)
                        message = "Backup imported — tap Connect to start"
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PassField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
}

private fun copySensitive(ctx: Context, text: String) {
    val clip = ClipData.newPlainText("ResProxy backup", text)
    // Hides the content from the clipboard preview (Android 13+).
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
