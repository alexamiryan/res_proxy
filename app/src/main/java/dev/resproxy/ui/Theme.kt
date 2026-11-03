package dev.resproxy.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.resproxy.data.Verdict
import java.util.Locale

@Composable
fun ResProxyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

object StatusColors {
    val connected = Color(0xFF2E9D5B)
    val pending = Color(0xFFE0A030)
    val down = Color(0xFFD64545)
    val idle = Color(0xFF8A8F98)
}

fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.CLEAN -> StatusColors.connected
    Verdict.SUSPICIOUS -> StatusColors.pending
    Verdict.BAD -> StatusColors.down
    Verdict.UNKNOWN -> StatusColors.idle
}

fun verdictLabel(v: Verdict) = when (v) {
    Verdict.CLEAN -> "Clean"
    Verdict.SUSPICIOUS -> "Suspicious"
    Verdict.BAD -> "Bad reputation"
    Verdict.UNKNOWN -> "Unknown"
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

fun flagEmoji(countryCode: String?): String {
    val cc = countryCode?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } } ?: return ""
    return String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'A')))
}
