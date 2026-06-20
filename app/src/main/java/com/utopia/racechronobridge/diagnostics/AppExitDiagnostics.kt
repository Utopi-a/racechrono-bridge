package com.utopia.racechronobridge.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object AppExitDiagnostics {
    private const val PREFS_NAME = "racechrono_bridge_exit_diagnostics"
    private const val KEY_LAST_CRASH = "last_crash"
    private const val KEY_LAST_LIFECYCLE = "last_lifecycle"
    private const val MAX_STACK_CHARS = 3_000
    private val installed = AtomicBoolean(false)
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) {
            return
        }

        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordCrash(appContext, thread, throwable)
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun recordLifecycle(context: Context, state: String) {
        preferences(context).edit()
            .putString(KEY_LAST_LIFECYCLE, "$state at ${formatTime(System.currentTimeMillis())}")
            .apply()
    }

    fun startupMessages(context: Context): List<String> {
        val messages = mutableListOf<String>()
        readAndClearLastCrash(context)?.let { messages.add(it) }
        preferences(context).getString(KEY_LAST_LIFECYCLE, null)?.let { lifecycle ->
            messages.add("Previous lifecycle before process exit: $lifecycle")
        }
        latestHistoricalExit(context)?.let { exit ->
            messages.add(exit.toLogLine())
        }
        return messages
    }

    private fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = StringWriter().also { writer ->
            throwable.printStackTrace(PrintWriter(writer))
        }.toString().take(MAX_STACK_CHARS)

        val message = buildString {
            append("Last uncaught crash: ")
            append(throwable.javaClass.name)
            throwable.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            append(" on thread ").append(thread.name)
            append(" at ").append(formatTime(System.currentTimeMillis()))
            append("\n").append(stackTrace)
        }

        preferences(context).edit()
            .putString(KEY_LAST_CRASH, message)
            .commit()
    }

    private fun readAndClearLastCrash(context: Context): String? {
        val prefs = preferences(context)
        val crash = prefs.getString(KEY_LAST_CRASH, null)
        if (crash != null) {
            prefs.edit().remove(KEY_LAST_CRASH).apply()
        }
        return crash
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun latestHistoricalExit(context: Context): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        val activityManager = context.getSystemService(ActivityManager::class.java)
        return activityManager
            .getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull()
    }

    private fun ApplicationExitInfo.toLogLine(): String {
        val details = listOfNotNull(
            description?.takeIf { it.isNotBlank() }?.let { "description=$it" },
            "importance=$importance",
            "pss=${pss / 1024}MB",
            "rss=${rss / 1024}MB",
        ).joinToString(separator = " ")
        return "Previous Android exit: ${reasonLabel(reason)} at ${formatTime(timestamp)} $details"
    }

    private fun reasonLabel(reason: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "UNAVAILABLE"
        }
        return when (reason) {
            ApplicationExitInfo.REASON_UNKNOWN -> "REASON_UNKNOWN"
            ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "REASON_USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "REASON_DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER"
            else -> "REASON_$reason"
        }
    }

    private fun formatTime(timestampMillis: Long): String = timeFormat.format(Instant.ofEpochMilli(timestampMillis))
}
