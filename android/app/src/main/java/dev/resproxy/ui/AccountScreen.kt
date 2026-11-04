package dev.resproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.resproxy.container
import dev.resproxy.data.Account
import dev.resproxy.data.Provider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(provider: Provider, canGoBack: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var apiLogin by remember { mutableStateOf("") }
    var apiPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(provider) {
        c.accounts.get(provider)?.let {
            username = it.username; password = it.password; token = it.apiToken
            apiLogin = it.apiLogin; apiPassword = it.apiPassword
        }
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${provider.label} account") },
                navigationIcon = {
                    if (canGoBack) IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        if (!loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when (provider) {
                    Provider.IPROYAL -> "Use the proxy username and password from your IPRoyal dashboard (Residential → Proxy access) — " +
                        "not your website login."
                    else -> "Use the proxy username and password from your Geonode dashboard (Proxies → Residential), " +
                        "usually a username like geonode_abc123."
                } + " You can also paste the whole proxy line (host:port:username:password).",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                username,
                { v ->
                    val parsed = parseProxyLine(v)
                    if (parsed != null) { username = parsed.first; password = parsed.second } else username = v.trim()
                },
                label = { Text("Proxy username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = username.contains('@'),
                supportingText = if (username.contains('@')) {
                    { Text("This looks like your website login email. The proxy username is a different, generated value.") }
                } else null,
            )
            OutlinedTextField(
                password, { password = it.trim() }, label = { Text("Proxy password") }, singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show password")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (provider == Provider.IPROYAL) {
                Section("Location lists (optional)")
                Text(
                    "IPRoyal only shares its list of available states and cities with an API token " +
                        "(dashboard → Settings → API). Without it you can still pick any country and US state, and type a city.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    token, { token = it.trim() }, label = { Text("API token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Section("Location lists (optional)")
                Text(
                    "Geonode's API for the list of available states and cities uses your dashboard login " +
                        "(the email and password for app.geonode.com). Without it you can still pick any country and type a state or city.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(apiLogin, { apiLogin = it.trim() }, label = { Text("Dashboard login (email)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    apiPassword, { apiPassword = it }, label = { Text("Dashboard password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        val acc = if (provider == Provider.IPROYAL) Account(provider, username, password, apiToken = token)
                        else Account(provider, username, password, apiLogin = apiLogin, apiPassword = apiPassword)
                        c.accounts.save(acc)
                        c.appScope.launch { c.locations(provider).refresh(acc, force = true) }
                        onDone()
                    }
                },
                enabled = username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            Text("Stored encrypted on this device only.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** `host:port:user:pass` or `user:pass@host:port` (as providers show them) → (user, pass). */
internal fun parseProxyLine(text: String): Pair<String, String>? {
    val t = text.trim().removePrefix("socks5://").removePrefix("http://")
    Regex("""^[^\s:@]+\.[^\s:@]+:\d+:([^\s:]+):(\S+)$""").find(t)?.let { return it.groupValues[1] to it.groupValues[2] }
    Regex("""^([^\s:@]+):(\S+)@[^\s:@]+:\d+$""").find(t)?.let { return it.groupValues[1] to it.groupValues[2] }
    return null
}
