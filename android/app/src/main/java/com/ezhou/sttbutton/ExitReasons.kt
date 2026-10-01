package com.ezhou.sttbutton

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.time.Instant

class ExitRecord(
    val timestampMs: Long, val reason: String, val status: Int, val importance: Int,
    val pssKb: Long, val rssKb: Long, val description: String?,
)

/** Writes an `exit` line for each death of this app's process the log has not recorded yet (plan 07). */
object ExitReasons {
    fun fresh(records: List<ExitRecord>, newestLoggedMs: Long?): List<ExitRecord> =
        records.filter { newestLoggedMs == null || it.timestampMs > newestLoggedMs }.sortedBy { it.timestampMs }

    fun newestLogged(lines: List<String>): Long? = lines.asSequence()
        .filter { it.contains("\"event\":\"exit\"") }
        .mapNotNull { Regex("\"ts\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        .mapNotNull { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        .maxOrNull()

    fun record(context: Context, log: EditLog, logFile: java.io.File) {
        val infos = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 0)
        val records = infos.map {
            ExitRecord(it.timestamp, reasonName(it.reason), it.status, it.importance, it.pss, it.rss, it.description)
        }
        val lines = if (logFile.exists()) logFile.readLines() else emptyList()
        for (exit in fresh(records, newestLogged(lines))) {
            log.write("exit", "", "reason" to exit.reason, "status" to exit.status, "importance" to exit.importance,
                "pss_kb" to exit.pssKb, "rss_kb" to exit.rssKb, "description" to exit.description,
                tsMs = exit.timestampMs)
        }
    }

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "UNKNOWN"
    }
}
