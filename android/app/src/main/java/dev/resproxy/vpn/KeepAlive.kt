package dev.resproxy.vpn

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.os.Build
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.resproxy.App
import dev.resproxy.R
import dev.resproxy.data.DiagLog
import kotlinx.coroutines.launch

/**
 * Keeps the tunnel on once the user turned it on (`wasConnected`), until they turn it off.
 * Pure policy here; [ProxyVpnService] runs the in-process supervisor, [KeepAliveJob] restarts a service that's gone.
 */
object KeepAlive {
    private var lastStartRequest = 0L

    /** Waits between reconnect attempts: 5 s, 10 s, 20 s, 40 s, 80 s, then every 2 min. */
    fun backoffMs(failures: Int): Long = if (failures <= 0) 0 else minOf(5_000L shl minOf(failures - 1, 5), 120_000L)

    /**
     * Should the supervisor (re)connect now? [plumbingUp]: a profile is loaded and the health/stats workers are alive
     * (a tunnel in PROXY_DOWN with workers running repairs itself). [busy]: a connect is in progress.
     */
    fun shouldReconnect(want: Boolean, plumbingUp: Boolean, busy: Boolean, now: Long, nextAttemptAt: Long): Boolean =
        want && !plumbingUp && !busy && now >= nextAttemptAt

    /**
     * Starts the tunnel if the user wants it on and it isn't running in this process (the service lives in the app
     * process, so a fresh process means it's gone). Returns true if a start was requested.
     */
    suspend fun ensureRunning(ctx: Context, why: String): Boolean {
        val container = (ctx.applicationContext as App).container
        container.ready.await()
        val s = container.settings.current()
        // A running service keeps itself up (its supervisor reconnects); starting it again would only reconnect twice.
        if (!s.wasConnected || !s.autoConnect || TunnelState.status.value.isActive || ProxyVpnService.alive) return false
        // Boot / update receiver, the watchdog and the app often fire together: start once.
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastStartRequest < 15_000) return false
            lastStartRequest = now
        }
        if (VpnService.prepare(ctx) != null) {
            DiagLog.log("keep-alive ($why): VPN permission is gone, can't reconnect")
            return false
        }
        DiagLog.log("keep-alive ($why): tunnel not running, starting it")
        return try {
            TunnelController.connect(ctx)
            true
        } catch (e: Exception) {
            // Android may refuse to start a foreground service from the background (battery optimized app).
            DiagLog.log("keep-alive ($why): Android refused to start the tunnel", e)
            notifyStopped(ctx)
            false
        }
    }

    fun ignoresBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Last resort when Android won't let us restart in the background: a tap (user action) is always allowed. */
    private fun notifyStopped(ctx: Context) {
        val connect = PendingIntent.getForegroundService(
            ctx, 3, Intent(ctx, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_CONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("ResProxy stopped")
            .setContentText("Android stopped the tunnel. Tap to reconnect. Allow background use in ResProxy → Protection.")
            .setContentIntent(connect)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(4, n)
    }
}

/**
 * Dead man's switch: while the tunnel should be on, the service keeps an exact alarm armed ~60 s ahead and re-arms it
 * every 20 s. If the process dies (low memory kill, crash), the alarm fires and restarts it. Android itself didn't:
 * a killed VPN service with Always-on stayed down for 15+ minutes in testing. Exact alarms may start a foreground
 * service from the background.
 */
object DeadManAlarm {
    private const val AHEAD_MS = 60_000L
    private const val REARM_MS = 20_000L
    @Volatile private var armedAt = 0L

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 5, Intent(ctx, KeepAliveReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Called often (every supervisor tick); only touches AlarmManager every [REARM_MS]. */
    fun arm(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - armedAt < REARM_MS) return
        armedAt = now
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = now + AHEAD_MS
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx))
        }.onFailure { DiagLog.log("can't arm the keep-alive alarm", it) }
    }

    fun disarm(ctx: Context) {
        armedAt = 0
        runCatching { ctx.getSystemService(AlarmManager::class.java).cancel(intent(ctx)) }
    }
}

/** The dead man's switch fired: the service didn't re-arm it, so it's probably gone. */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        (context.applicationContext as App).container.appScope.launch {
            try {
                KeepAlive.ensureRunning(context, "alarm")
            } finally {
                pending.finish()
            }
        }
    }
}

/** Periodic watchdog (every 15 min, survives reboots) plus one-shot restarts after the service was destroyed. */
class KeepAliveJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as App).container
        container.appScope.launch {
            try {
                KeepAlive.ensureRunning(applicationContext, if (params.jobId == SOON_ID) "restart" else "watchdog")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val PERIODIC_ID = 1001
        private const val SOON_ID = 1002

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(PERIODIC_ID) != null) return
            val job = JobInfo.Builder(PERIODIC_ID, ComponentName(ctx, KeepAliveJob::class.java))
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(job) }.onFailure { DiagLog.log("can't schedule the keep-alive job", it) }
        }

        /** Checks again shortly, e.g. after the service was destroyed while the user still wanted it on. */
        fun scheduleSoon(ctx: Context, delayMs: Long = 5_000) {
            val job = JobInfo.Builder(SOON_ID, ComponentName(ctx, KeepAliveJob::class.java))
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + 60_000)
                .build()
            runCatching { ctx.getSystemService(JobScheduler::class.java).schedule(job) }
        }
    }
}
