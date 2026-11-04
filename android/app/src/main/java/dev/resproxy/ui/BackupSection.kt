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

private enum class BackupDialog { EXPORT, IMPORT, PASSPHRASE }

/** Settings block: passphrase-encrypted export / import of all settings and credentials. */
@Composable
fun BackupSection(importOnly: Boolean = false) {
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
    /** Backup text picked for import, waiting for its passphrase. */
    var loaded by remember { mutableStateOf<String?>(null) }

    fun reset() { dialog = null; pass = ""; pass2 = ""; busy = false; sealed = null; loaded = null }

    fun gotText(text: String?) {
        when {
            text.isNullOrBlank() -> { message = "Nothing to import"; reset() }
            !BackupCrypto.looksLikeBackup(text) -> { message = "That isn't a ResProxy backup"; reset() }
            else -> { loaded = text; pass = ""; dialog = BackupDialog.PASSPHRASE }
        }
    }

    /** Encrypts once per dialog; PBKDF2 is deliberately slow. */
    suspend fun sealedText(): String = sealed ?: withContext(Dispatchers.Default) {
        BackupCrypto.seal(c.exportBackup(), pass.toCharArray())
    }.also { sealed = it }

    fun decrypt() {
        val text = loaded ?: return
        busy = true; message = null
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
            gotText(text)
        }
    }

    if (importOnly) {
        OutlinedButton(onClick = { dialog = BackupDialog.IMPORT }, modifier = Modifier.fillMaxWidth()) { Text("Import backup", maxLines = 1) }
    } else Text(
        "Copy this setup to another Android profile or phone: accounts, VPN profiles, Mullvad account, API keys and " +
            "settings, encrypted with a passphrase you choose. Android keeps profiles apart, so move the file with Share " +
            "(a messenger, Drive, email to yourself), then Import on the other side. Each profile keeps its own Mullvad device.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (!importOnly) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { dialog = BackupDialog.EXPORT }, modifier = Modifier.weight(1f)) { Text("Export", maxLines = 1) }
        OutlinedButton(onClick = { dialog = BackupDialog.IMPORT }, modifier = Modifier.weight(1f)) { Text("Import", maxLines = 1) }
    }
    if (dialog == null) message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

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
            text = { Text("Choose the backup file, or paste backup text you copied.") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
                        gotText(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString())
                    }) { Text("Paste") }
                    TextButton(onClick = { openFile.launch(arrayOf("*/*")) }) { Text("Choose file") }
                }
            },
            dismissButton = { TextButton(onClick = ::reset) { Text("Cancel") } },
        )
        BackupDialog.PASSPHRASE -> AlertDialog(
            onDismissRequest = ::reset,
            title = { Text("Backup passphrase") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PassField("Passphrase", pass) { pass = it; message = null }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (busy) CircularProgressIndicator()
                }
            },
            confirmButton = { TextButton(enabled = pass.isNotEmpty() && !busy, onClick = ::decrypt) { Text("Unlock") } },
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
                        "• VPN profiles: ${b.vpnProfiles.joinToString { it.name }.ifEmpty { b.chain?.name ?: "none" }}\n" +
                        "• Mullvad: ${if (b.mullvadNumber.isNotEmpty()) "yes" else "no"}\n\n" +
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
