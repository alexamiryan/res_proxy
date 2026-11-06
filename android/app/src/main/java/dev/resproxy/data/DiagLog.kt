package dev.resproxy.data

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent event log (connects, failures, repairs, phase changes, process exits), so what happened overnight can
 * be read in the morning: Settings → Diagnostics, or `run-as dev.resproxy cat files/diag.log`.
 * Never log secrets here (passwords, API keys, backup passphrases).
 */
object DiagLog {
    private const val TAG = "ResProxy"
    private const val MAX_BYTES = 512 * 1024L
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(dir: File) {
        file = File(dir, "diag.log")
    }

    fun log(message: String, e: Throwable? = null) {
        if (e == null) Log.i(TAG, message) else Log.w(TAG, message, e)
        val f = file ?: return
        val line = buildString {
            synchronized(fmt) { append(fmt.format(Date())) }
            append(' ').append(message)
            if (e != null) append(" — ").append(e.javaClass.simpleName).append(": ").append(e.message)
            append('\n')
        }
        synchronized(this) {
            runCatching {
                if (f.length() > MAX_BYTES) {
                    // Keep the newer half.
                    val text = f.readText()
                    f.writeText(text.substring(text.length / 2).substringAfter('\n'))
                }
                f.appendText(line)
            }
        }
    }

    fun read(): String = synchronized(this) { runCatching { file?.readText() }.getOrNull().orEmpty() }

    fun clear() = synchronized(this) { runCatching { file?.writeText("") } }
}
