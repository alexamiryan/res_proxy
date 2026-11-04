package dev.resproxy.ui

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String, val system: Boolean, val info: ApplicationInfo)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(selected: Set<String>, onDone: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    var chosen by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(0)
                .filter { it.packageName != ctx.packageName }
                .map { AppEntry(it.packageName, it.loadLabel(pm).toString(), it.flags and ApplicationInfo.FLAG_SYSTEM != 0, it) }
                .sortedBy { it.label.lowercase() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apps (${chosen.size})") },
                actions = { IconButton(onClick = { onDone(chosen) }) { Icon(Icons.Default.Check, "Done") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                query, { query = it }, label = { Text("Search") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp)) {
                FilterChip(showSystem, { showSystem = !showSystem }, label = { Text("Show system apps") })
            }
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            val q = query.trim().lowercase()
            val visible = list.filter {
                (showSystem || !it.system || it.pkg in chosen) &&
                    (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q))
            }.sortedByDescending { it.pkg in selected }
            LazyColumn {
                items(visible, key = { it.pkg }) { app ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            chosen = if (app.pkg in chosen) chosen - app.pkg else chosen + app.pkg
                        }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                        }
                        Checkbox(app.pkg in chosen, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: AppEntry) {
    val pm = LocalContext.current.packageManager
    val icon by produceState<ImageBitmap?>(null, app.pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { app.info.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    icon?.let { Image(it, null, Modifier.size(40.dp)) } ?: Spacer(Modifier.size(40.dp))
}
