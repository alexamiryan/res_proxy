package dev.resproxy.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.resproxy.App
import dev.resproxy.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Reconnects after reboot / app update if the user hadn't disconnected and VPN consent is still in place. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val container = (context.applicationContext as App).container
        container.appScope.launch {
            try {
                KeepAlive.ensureRunning(context, if (intent.action == Intent.ACTION_BOOT_COMPLETED) "boot" else "app updated")
            } finally {
                pending.finish()
            }
        }
    }
}

class ProxyTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        scope = CoroutineScope(Dispatchers.Main + Job()).also { s ->
            s.launch { TunnelState.status.collect { render(it) } }
        }
    }

    override fun onStopListening() {
        scope?.cancel(); scope = null
    }

    override fun onClick() {
        if (TunnelState.status.value.isActive) {
            TunnelController.disconnect(this)
        } else if (VpnService.prepare(this) != null) {
            openApp()
        } else {
            TunnelController.connect(this)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun render(s: TunnelStatus) {
        val tile = qsTile ?: return
        tile.state = if (s.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (s.phase) {
                Phase.CONNECTED -> s.exitIp ?: s.profileName
                Phase.CONNECTING, Phase.ROTATING -> "Connecting…"
                Phase.PROXY_DOWN -> "Proxy down"
                else -> null
            }
        }
        tile.updateTile()
    }
}
