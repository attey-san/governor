package com.attey.governor.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches for the conditions a user attached a profile to, and applies it.
 *
 * A foreground service because that is the only way to keep receiving
 * ACTION_SCREEN_OFF, and because a background process quietly reclocking someone's
 * CPU without a visible notification is the behaviour this app is supposed to be
 * the opposite of.
 */
class TriggerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { ProfileStore(this) }

    // Broadcasts arrive on the main thread while reload() runs in a coroutine.
    @Volatile private var model: DeviceModel? = null
    @Volatile private var triggers: List<Trigger> = emptyList()
    @Volatile private var profiles: List<Profile> = emptyList()

    /**
     * Threshold triggers fire on the *crossing*, not on the level.
     *
     * ACTION_BATTERY_CHANGED arrives every few seconds. Without this an armed
     * "below 30%" trigger would re-apply its profile a hundred times on the way
     * from 30 to 20, fighting anything the user did by hand in between.
     */
    private val armed = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    private var appPollJob: Job? = null
    @Volatile private var screenOn = true
    @Volatile private var currentApp: String? = null

    /**
     * What was in effect before an app trigger took over.
     *
     * An app profile that never came back off would be a trap: open a game once
     * and the phone stays clocked up until you notice. Entering the app snapshots
     * the live settings, leaving it puts them back.
     */
    @Volatile private var beforeApp: Profile? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = handle(intent)
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification("watching"))
        scope.launch {
            RootShell.get()?.let { model = DeviceProbe.probe(it) }
            reload()
        }
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            },
            // The battery and screen broadcasts are protected system ones; this
            // flag is required from API 34 and harmless below it.
            if (Build.VERSION.SDK_INT >= 34) RECEIVER_EXPORTED else 0,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch { reload() }
        return START_STICKY
    }

    private fun reload() {
        triggers = store.loadTriggers().filter { it.enabled }
        profiles = store.loadProfiles()
        armed.clear()
        triggers.forEach { armed[it.id] = true }
        notify(if (triggers.isEmpty()) "no triggers set" else "${triggers.size} trigger(s) armed")
        syncAppPoll()
    }

    /**
     * Starts or stops the foreground-app poll.
     *
     * Nothing polls unless an app trigger exists and the screen is on. A battery
     * app that quietly wakes every ten seconds forever would be exactly the kind
     * of thing it is supposed to help you find.
     */
    private fun syncAppPoll() {
        val wanted = screenOn && triggers.any { it.type == TriggerType.APP_FOREGROUND } &&
            UsageAccess.hasAccess(this)
        if (!wanted) {
            appPollJob?.cancel()
            appPollJob = null
            return
        }
        if (appPollJob?.isActive == true) return
        appPollJob = scope.launch {
            while (isActive) {
                checkForegroundApp()
                delay(APP_POLL_MS)
            }
        }
    }

    private suspend fun checkForegroundApp() {
        val pkg = UsageAccess.foregroundPackage(this) ?: return
        if (pkg == currentApp) return
        val previous = currentApp
        currentApp = pkg

        val entering = triggers.firstOrNull {
            it.type == TriggerType.APP_FOREGROUND && it.packageName == pkg
        }
        val leaving = previous != null && triggers.any {
            it.type == TriggerType.APP_FOREGROUND && it.packageName == previous
        }

        val shell = RootShell.get() ?: return
        val device = model ?: DeviceProbe.probe(shell).also { model = it }

        if (leaving && entering == null) {
            beforeApp?.let {
                ProfileEngine.apply(shell, device, it)
                notify("restored settings from before ${previous}")
            }
            beforeApp = null
            return
        }
        if (entering != null) {
            if (beforeApp == null) beforeApp = ProfileEngine.snapshot(device, "before app")
            fire(entering)
        }
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> { screenOn = true; syncAppPoll() }
            Intent.ACTION_SCREEN_OFF -> { screenOn = false; syncAppPoll() }
        }
        val matches = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> triggers.filter { it.type == TriggerType.PLUGGED_IN }
            Intent.ACTION_POWER_DISCONNECTED -> triggers.filter { it.type == TriggerType.UNPLUGGED }
            Intent.ACTION_SCREEN_ON -> triggers.filter { it.type == TriggerType.SCREEN_ON }
            Intent.ACTION_SCREEN_OFF -> triggers.filter { it.type == TriggerType.SCREEN_OFF }
            Intent.ACTION_BATTERY_CHANGED -> thresholdMatches(intent)
            else -> emptyList()
        }
        if (matches.isEmpty()) return
        scope.launch { matches.forEach { fire(it) } }
    }

    private fun thresholdMatches(intent: Intent): List<Trigger> {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val percent = if (level < 0) null else level * 100 / scale
        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }?.let { it / 10 }

        val out = mutableListOf<Trigger>()
        for (t in triggers) {
            val over = when (t.type) {
                TriggerType.BATTERY_BELOW -> percent?.let { it < t.threshold }
                TriggerType.TEMP_ABOVE -> tempC?.let { it > t.threshold }
                else -> null
            } ?: continue

            val isArmed = armed[t.id] ?: true
            if (over && isArmed) {
                out += t
                armed[t.id] = false
            } else if (!over) {
                // Re-arm with a few points of hysteresis so a value sitting exactly
                // on the threshold does not oscillate.
                val clear = when (t.type) {
                    TriggerType.BATTERY_BELOW -> percent!! >= t.threshold + HYSTERESIS
                    else -> tempC!! <= t.threshold - HYSTERESIS
                }
                if (clear) armed[t.id] = true
            }
        }
        return out
    }

    private suspend fun fire(trigger: Trigger) {
        val shell = RootShell.get() ?: return
        val device = model ?: DeviceProbe.probe(shell).also { model = it }
        val profile = profiles.firstOrNull { it.name == trigger.profileName } ?: return
        val rejections = ProfileEngine.apply(shell, device, profile)
        notify(
            if (rejections.isEmpty()) "applied ${profile.name}"
            else "applied ${profile.name}, ${rejections.size} setting(s) declined"
        )
    }

    // ------------------------------------------------------- Notification

    private fun notify(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Triggers", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shows which profile Governor last applied" }
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Governor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        appPollJob?.cancel()
        runCatching { unregisterReceiver(receiver) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "governor_triggers"
        private const val NOTIFICATION_ID = 1
        private const val HYSTERESIS = 3

        /** Slow on purpose. See [syncAppPoll]. */
        const val APP_POLL_MS = 10_000L

        /** Starts or refreshes the watcher. Safe to call repeatedly. */
        fun sync(context: Context) {
            val intent = Intent(context, TriggerService::class.java)
            if (ProfileStore(context).loadTriggers().any { it.enabled }) {
                context.startForegroundService(intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
