package com.attey.governor.core

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

/** One launchable app, for the per-app trigger picker. */
data class InstalledApp(val packageName: String, val label: String)

/**
 * Foreground-app detection.
 *
 * Android has no broadcast for "the foreground app changed", so this is a poll,
 * and a poll inside a battery app has to justify itself. It runs only while at
 * least one app trigger is enabled *and* the screen is on, at
 * [TriggerService.APP_POLL_MS]. With no app triggers configured, none of this
 * code runs at all.
 */
object UsageAccess {

    /**
     * PACKAGE_USAGE_STATS is an appop, not a runtime permission. It cannot be
     * requested from a dialog -- the user has to grant it in Settings -- so the
     * UI has to be able to ask whether it is held.
     */
    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The package that most recently moved to the foreground, or null.
     *
     * Read from the event stream rather than `queryUsageStats`, whose buckets are
     * coarse enough to lag by minutes.
     */
    fun foregroundPackage(context: Context, sinceMs: Long = 10_000): String? {
        if (!hasAccess(context)) return null
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - sinceMs, now)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = event.packageName
        }
        return last
    }

    /** Launchable apps, alphabetically. */
    fun installedApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                InstalledApp(pkg, info.loadLabel(pm).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
