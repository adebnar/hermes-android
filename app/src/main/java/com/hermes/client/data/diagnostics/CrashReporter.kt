package com.hermes.client.data.diagnostics

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captures otherwise-invisible crashes. On an uncaught exception the full stack trace (plus device
 * + OS info) is written to a file; on the next launch [MainActivity] reads it and shows it on a
 * screen the user can share. This turns a silent "app keeps crashing" into a copyable trace without
 * needing adb/logcat. The token is redacted so a shared trace can't leak credentials.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val trace = DebugLog.redact(sw.toString())
                val version = runCatching {
                    val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                    "${pi.versionName} (${pi.longVersionCode})"
                }.getOrDefault("?")
                val report = buildString {
                    appendLine("Hermes Beta — crash report")
                    appendLine("app: ${app.packageName} $version")
                    appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("thread: ${thread.name}")
                    appendLine()
                    append(trace)
                }
                app.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(report.toByteArray()) }
            }
            // Let the platform still terminate the process (and run any prior handler).
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved crash report, or null if there is none. */
    fun read(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE).delete() }
    }
}
