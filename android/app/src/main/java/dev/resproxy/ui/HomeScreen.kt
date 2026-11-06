package dev.resproxy.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.resproxy.container
import dev.resproxy.data.VpnExit
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextAlign
import dev.resproxy.data.Location
import dev.resproxy.data.Provider
import dev.resproxy.provider.CatalogSource
import dev.resproxy.provider.LocationCatalog
import kotlinx.coroutines.launch
import dev.resproxy.data.Verdict
import dev.resproxy.reputation.ReputationReport
import dev.resproxy.reputation.SourceResult
import dev.resproxy.vpn.Phase
import dev.resproxy.vpn.TunnelController
import dev.resproxy.vpn.TunnelState
import dev.resproxy.vpn.TunnelStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onSettings: () -> Unit, onAccount: (Provider) -> Unit, onVpnSettings: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val status by TunnelState.status.collectAsState()
    val settings by c.settings.settings.collectAsState(initial = null)
    val loadedAccounts by c.accounts.accounts.collectAsState(initial = null)
    val accounts = loadedAccounts.orEmpty()
    val provider = settings?.provider ?: Provider.IPROYAL
    val account = accounts[provider]
    val locations = c.locations(provider)
    val catalog by locations.catalog.collectAsState()

    val vpnProfiles by c.vpn.profiles.collectAsState(initial = emptyList())
    val relays by c.mullvad.relays.collectAsState()
    val mullvadAccount by c.vpn.mullvad.collectAsState(initial = null)
    var pickingVpn by remember { mutableStateOf(false) }

    LaunchedEffect(provider, account) { locations.refresh(account) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) TunnelController.connect(ctx)
    }

    fun toggle() {
        when {
            status.isActive -> TunnelController.disconnect(ctx)
            account == null -> onAccount(provider)
            else -> VpnService.prepare(ctx)?.let { consent.launch(it) } ?: TunnelController.connect(ctx)
        }
    }

    fun setLocation(loc: Location) {
        scope.launch {
            c.settings.update { it.copy(location = loc).restoreSession() }
            TunnelController.applyLocation(ctx)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ResProxy") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            PowerButton(status, onClick = ::toggle)
            Spacer(Modifier.height(16.dp))
            Text(phaseLabel(status), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            status.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = if (status.phase == Phase.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(20.dp))

            if (loadedAccounts?.isEmpty() == true) {
                WelcomeCard(onAccount)
                return@Column
            }
            Choice(PROVIDERS, provider, { it.label }) { p ->
                scope.launch {
                    c.settings.switchProvider(p)
                    if (accounts[p] == null) {
                        TunnelController.disconnect(ctx)
                        onAccount(p)
                    } else {
                        TunnelController.applyLocation(ctx)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            settings?.let { s ->
                val hint = when {
                    account == null -> "Set up your ${provider.label} account to connect."
                    !locations.canFetch(account) && provider == Provider.IPROYAL ->
                        "Add your IPRoyal API token in Settings → Accounts to choose from the list of available cities."
                    !locations.canFetch(account) ->
                        "Add your Geonode login in Settings → Accounts to choose from the list of states and cities."
                    else -> null
                }
                val freeText = catalog.source == CatalogSource.BUILT_IN
                // Geonode targets states in every country; IPRoyal only in the US (built-in list).
                LocationCard(s.location, catalog, hint, freeTextCities = freeText,
                    freeTextStates = freeText && provider == Provider.GEONODE, onChange = ::setLocation)
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        PickerRow("VPN before proxy", vpnLabel(s, vpnProfiles, relays)) { pickingVpn = true }
                        val device = mullvadAccount?.device?.name?.takeIf { it.isNotBlank() }
                        val mullvadLine = when {
                            device != null -> "Mullvad device: $device"
                            mullvadAccount?.signedOut == true -> "Signed out of Mullvad — log in in Settings → VPN"
                            else -> null
                        }
                        if (s.chainEnabled && VpnExit.parse(s.vpnExit) is VpnExit.Mullvad && mullvadLine != null) {
                            Text(mullvadLine, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }
                }
                if (pickingVpn) VpnPickerDialog(
                    s,
                    onDismiss = { pickingVpn = false },
                    onManage = { pickingVpn = false; onVpnSettings() },
                ) { enabled, exit ->
                    pickingVpn = false
                    scope.launch { c.settings.update { it.copy(chainEnabled = enabled, vpnExit = exit) } }
                }
            }

            if (settings?.wasConnected == true) {
                Spacer(Modifier.height(12.dp))
                BackgroundUseCard()
            }
            if (status.isActive) {
                Spacer(Modifier.height(12.dp))
                ConnectionCard(status)
                Spacer(Modifier.height(12.dp))
                ReputationCard(status.report, status.checking, status.rotationAttempt)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = { TunnelController.rotate(ctx) },
                        enabled = status.phase == Phase.CONNECTED || status.phase == Phase.PROXY_DOWN,
                        modifier = Modifier.weight(1f),
                    ) { Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(8.dp)); Text("New IP") }
                    FilledTonalButton(
                        onClick = { TunnelController.recheck(ctx) },
                        enabled = !status.checking && status.phase == Phase.CONNECTED,
                        modifier = Modifier.weight(1f),
                    ) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Re-check") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val PROVIDERS = listOf(Provider.IPROYAL, Provider.GEONODE)

/** First run: set up a provider account, or bring everything over from another profile/phone. */
@Composable
private fun WelcomeCard(onAccount: (Provider) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Get started", style = MaterialTheme.typography.titleMedium)
            Text("Add your residential proxy account:", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PROVIDERS.forEach { p ->
                    FilledTonalButton(onClick = { onAccount(p) }, modifier = Modifier.weight(1f)) { Text("Set up ${p.label}") }
                }
            }
            HorizontalDivider()
            Text("Or import a backup from another profile or phone:", style = MaterialTheme.typography.bodyMedium)
            BackupSection(importOnly = true)
        }
    }
}

private enum class Picking { COUNTRY, STATE, CITY }

@Composable
private fun LocationCard(
    loc: Location,
    catalog: LocationCatalog,
    hint: String?,
    freeTextCities: Boolean,
    freeTextStates: Boolean,
    onChange: (Location) -> Unit,
) {
    var picking by remember { mutableStateOf<Picking?>(null) }
    val country = catalog.country(loc.country)
    val state = country?.states?.firstOrNull { it.code == loc.state }
    // A city can be picked without a state: then offer every city in the country.
    val cities = state?.cities?.takeIf { it.isNotEmpty() }
        ?: country?.let { c -> (c.cities + c.states.flatMap { it.cities }).distinctBy { it.code }.sortedBy { it.name } }.orEmpty()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Location", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(top = 8.dp))
                if (catalog.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            PickerRow("Country", if (loc.country.isEmpty()) "Any country" else "${flagEmoji(loc.country)}  ${loc.countryName}") {
                picking = Picking.COUNTRY
            }
            if (loc.country.isNotEmpty() && (country?.states?.isNotEmpty() == true || loc.state.isNotEmpty() || freeTextStates)) {
                PickerRow("State", loc.stateName.ifEmpty { "Any state" }) { picking = Picking.STATE }
            }
            if (loc.country.isNotEmpty()) {
                PickerRow("City", loc.cityName.ifEmpty { "Any city" }) { picking = Picking.CITY }
            }
            (catalog.error ?: hint)?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
        }
    }

    when (picking) {
        Picking.COUNTRY -> PlacePickerDialog("Country", "Any country", catalog.countries, allowCustom = false, showFlags = true,
            onDismiss = { picking = null }) { p ->
            picking = null
            onChange(Location(country = p?.code.orEmpty(), countryName = p?.name.orEmpty()))
        }
        Picking.STATE -> PlacePickerDialog("State", "Any state", country?.states.orEmpty(), allowCustom = freeTextStates,
            onDismiss = { picking = null }) { p ->
            picking = null
            onChange(loc.copy(state = p?.code.orEmpty(), stateName = p?.name.orEmpty(), city = "", cityName = ""))
        }
        Picking.CITY -> PlacePickerDialog("City", "Any city", cities, allowCustom = cities.isEmpty() || freeTextCities,
            onDismiss = { picking = null }) { p ->
            picking = null
            onChange(loc.copy(city = p?.code.orEmpty(), cityName = p?.name.orEmpty()))
        }
        null -> Unit
    }
}

@Composable
private fun PowerButton(status: TunnelStatus, onClick: () -> Unit) {
    val target = when (status.phase) {
        Phase.CONNECTED -> if (status.trafficBlocked) StatusColors.pending else StatusColors.connected
        Phase.CONNECTING, Phase.ROTATING -> StatusColors.pending
        Phase.PROXY_DOWN, Phase.ERROR -> StatusColors.down
        Phase.DISCONNECTED -> StatusColors.idle
    }
    val color by animateColorAsState(target, label = "power")
    Box(contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = color.copy(alpha = 0.15f),
            modifier = Modifier.size(200.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(150.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PowerSettingsNew, "Connect", tint = Color.White, modifier = Modifier.size(72.dp))
                }
            }
        }
        if (status.phase == Phase.CONNECTING || status.phase == Phase.ROTATING) {
            CircularProgressIndicator(Modifier.size(200.dp), color = color, strokeWidth = 4.dp)
        }
    }
}

private fun phaseLabel(s: TunnelStatus) = when (s.phase) {
    Phase.DISCONNECTED -> "Not connected"
    Phase.CONNECTING -> "Connecting…"
    Phase.CONNECTED -> if (!s.trafficBlocked) "Connected" else if (s.checking) "Checking IP · traffic blocked" else "Traffic blocked"
    Phase.ROTATING -> "Getting a new IP…"
    Phase.PROXY_DOWN -> "Proxy down · traffic blocked"
    Phase.ERROR -> "Connection failed"
}

@Composable
private fun ConnectionCard(s: TunnelStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Exit IP", style = MaterialTheme.typography.labelMedium)
            Text(s.exitIp ?: if (s.checking) "Detecting…" else "—", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            s.report?.geo?.let { g ->
                val place = listOfNotNull(g.city, g.region, g.country).distinct().joinToString(", ")
                if (place.isNotEmpty()) Text("${flagEmoji(g.countryCode)} $place", style = MaterialTheme.typography.bodyMedium)
                val net = listOfNotNull(g.isp, g.asn, g.connectionType).joinToString(" · ")
                if (net.isNotEmpty()) Text(net, style = MaterialTheme.typography.bodySmall)
            }
            s.chainName?.let {
                Text("Through VPN: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("↓ Down", "${formatBytes(s.downBps)}/s")
                Stat("↑ Up", "${formatBytes(s.upBps)}/s")
                Stat("Session", formatBytes(s.sessionDown + s.sessionUp))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (s.chainName != null) Stat("Ping to VPN", s.vpnPingMs?.let { "$it ms" } ?: "—")
                Stat(if (s.chainName != null) "Ping to proxy (via VPN)" else "Ping to proxy", s.proxyPingMs?.let { "$it ms" } ?: "—")
                if (s.repairs > 0) Stat("Auto-repairs", s.repairs.toString())
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ReputationCard(report: ReputationReport?, checking: Boolean, attempt: Int) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("IP reputation", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    if (attempt > 0) Text("  rotation $attempt", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (report == null) {
                Text(if (checking) "Checking…" else "Not checked yet", style = MaterialTheme.typography.titleMedium)
                return@Column
            }
            Spacer(Modifier.height(6.dp))
            Surface(color = verdictColor(report.verdict), shape = RoundedCornerShape(50)) {
                Text(
                    verdictLabel(report.verdict), color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            if (report.reasons.isEmpty() && report.verdict == Verdict.CLEAN) {
                Text("No abuse reports, blacklist hits or datacenter flags.", style = MaterialTheme.typography.bodyMedium)
            }
            report.reasons.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(verdictColor(r.verdict), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(r.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (report.proxyFlags > 0) {
                Text(
                    "Detected as proxy by ${report.proxyFlags} source(s) — normal for residential proxies",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ok = report.results.count { it.ok }
                Text("$ok/${report.results.size} sources answered", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded) report.results.forEach { SourceRow(it) }
        }
    }
}

@Composable
private fun SourceRow(r: SourceResult) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(r.source.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            describe(r), style = MaterialTheme.typography.bodySmall,
            color = if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}

private fun describe(r: SourceResult): String {
    if (!r.ok) return "Unavailable: ${r.error}"
    val parts = buildList {
        r.listed?.let { add(if (it) "LISTED" else "not listed") }
        r.score?.let { add("score $it") }
        r.riskLabel?.let { add("risk $it") }
        r.reports?.let { add("$it reports") }
        if (r.proxy == true) add("proxy")
        if (r.vpn == true) add("VPN")
        if (r.tor == true) add("Tor")
        if (r.datacenter == true) add("datacenter")
        if (r.abuser == true) add("abuser")
        if (r.recentAbuse == true) add("recent abuse")
        r.connectionType?.let { add(it) }
        r.note?.let { add(it) }
    }
    return parts.joinToString(" · ").ifEmpty { "no flags" }
}
