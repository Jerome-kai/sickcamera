package io.github.jeromekai.latent.crash

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date
import kotlin.system.exitProcess

/**
 * Shows an app crash on the phone instead of just closing the app.
 *
 * Sideloaded apps give their owner no crash dialog worth reading and no log
 * without a computer, so "it does not open" is otherwise all anyone can say.
 * This catches the crash, saves it, and opens [CrashActivity] with the details
 * to copy or share.
 *
 * The screen lives in its OWN PROCESS (":crash"), so it still appears when the
 * crash is in the app's own start-up code, and it uses nothing from the app
 * itself -- no stores, no Compose -- so it cannot fail for the same reason.
 */
object CrashReporter {
    const val EXTRA_REPORT = "report"
    private const val FILE = "last_crash.txt"

    /** This process's name, from the kernel: available at any point, on any API level. */
    private fun processName(): String = try {
        File("/proc/self/cmdline").readText().substringBefore('\u0000')
    } catch (_: Exception) {
        ""
    }

    /** True inside the crash screen's process, which must stay minimal. */
    fun isCrashProcess(): Boolean = processName().endsWith(":crash")

    /** Call first thing, from Application.attachBaseContext. */
    fun install(context: Context) {
        if (isCrashProcess()) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = buildReport(context, thread, error)
                runCatching { File(context.filesDir, FILE).writeText(report) }
                context.startActivity(
                    Intent(context, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(EXTRA_REPORT, report.take(MAX_EXTRA_CHARS)),
                )
                // Let the system hand the screen over before this process goes.
                Thread.sleep(500)
            } catch (_: Throwable) {
                // Whatever went wrong here, the original crash still has to
                // reach the system's own handling.
                previous?.uncaughtException(thread, error)
                return@setDefaultUncaughtExceptionHandler
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    /** The report saved by the last crash, if any. */
    fun readLast(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.isFile }?.readText() }.getOrNull()

    // An Intent extra travels through Binder, which has a 1 MB budget shared by everything in flight.
    private const val MAX_EXTRA_CHARS = 60_000

    @Suppress("DEPRECATION")
    private fun versionCode(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun buildReport(context: Context, thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${versionCode(info)})"
        } catch (_: PackageManager.NameNotFoundException) {
            "unknown"
        }
        return buildString {
            appendLine("Latent crash report")
            appendLine("When:     ${Date()}")
            appendLine("App:      $version")
            appendLine("Android:  ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, patch ${Build.VERSION.SECURITY_PATCH})")
            appendLine("Device:   ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABIs:     ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Thread:   ${thread.name}")
            appendLine()
            append(trace)
        }
    }
}
