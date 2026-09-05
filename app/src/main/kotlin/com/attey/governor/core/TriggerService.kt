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
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

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
    private val armed = ConcurrentHashMap<Long, Boolean>()

    @Volatile private var appPollJob: Job? = null
    @Volatile private var screenOn = true
    @Volatile private var currentApp: String? = null
    @Volatile private var appOverrideApplied = false
    private val appStateMutex = Mutex()

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
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive == true
        store.loadAppOverride()?.let { saved ->
            currentApp = saved.packageName
            beforeApp = saved.restore
            appOverrideApplied = saved.applied
        }
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

    @Synchronized
    private fun reload() {
        triggers = store.loadTriggers().filter { it.enabled }
        profiles = store.loadProfiles()
        val activeIds = triggers.mapTo(HashSet()) { it.id }
        armed.keys.retainAll(activeIds)
        triggers.forEach { armed.putIfAbsent(it.id, true) }
        notify(if (triggers.isEmpty()) "no triggers set" else "${triggers.size} trigger(s) armed")
        val activeApp = currentApp
        if (beforeApp != null && (!screenOn || activeApp == null ||
                triggers.none {
                    it.type == TriggerType.APP_FOREGROUND && it.packageName == activeApp
                } || !UsageAccess.hasAccess(this))
        ) {
            scope.launch { restoreOrphanedAppOverride() }
        }
        syncAppPoll()
    }

    /** Restores an app override whose trigger was disabled or lost permission. */
    private suspend fun restoreOrphanedAppOverride() {
        appStateMutex.withLock {
            if (!restoreAppOverrideLocked("restored settings after app trigger stopped")) return
        }
        if (triggers.isEmpty()) stopSelf()
    }

    /** Caller owns [appStateMutex]. */
    private suspend fun restoreAppOverrideLocked(successMessage: String): Boolean {
        val restore = beforeApp ?: return true
        val shell = RootShell.get() ?: return false
        val device = DeviceProbe.probe(shell).also { model = it }
        val declined = ProfileEngine.apply(shell, device, restore)
        if (declined.isEmpty()) {
            if (!store.clearAppOverride()) {
                notify("settings restored, but the app-trigger marker could not be cleared")
                return false
            }
            beforeApp = null
            currentApp = null
            appOverrideApplied = false
            notify(successMessage)
            return true
        } else {
            notify("app-trigger restore failed: ${declined.size} setting(s) declined")
            return false
        }
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

    private suspend fun checkForegroundApp(): Unit = appStateMutex.withLock {
        val pkg = UsageAccess.foregroundPackage(this, APP_POLL_MS * 2) ?: return
        if (beforeApp != null && !appOverrideApplied &&
            !restoreAppOverrideLocked("recovered an interrupted app-trigger change")
        ) return
        if (pkg == currentApp && (beforeApp == null || appOverrideApplied)) return
        val previous = currentApp

        val entering = triggers.firstOrNull {
            it.type == TriggerType.APP_FOREGROUND && it.packageName == pkg
        }
        val leaving = previous != null && triggers.any {
            it.type == TriggerType.APP_FOREGROUND && it.packageName == previous
        }

        if (leaving && !restoreAppOverrideLocked("restored settings from before $previous")) return
        if (entering == null) {
            currentApp = pkg
            return
        }

        // This snapshot has to describe the transition, not service startup.
        val shell = RootShell.get() ?: return
        val device = DeviceProbe.probe(shell).also { model = it }
        val restore = beforeApp ?: ProfileEngine.snapshot(device, "before app")
        // Persist a pending state before touching the kernel. If the process dies
        // in between, the next service instance restores and safely retries.
        if (!store.saveAppOverride(pkg, restore, applied = false)) {
            notify("could not save the pre-app restore point; profile not applied")
            return
        }
        beforeApp = restore
        appOverrideApplied = false
        if (fire(entering)) {
            currentApp = pkg
            appOverrideApplied = true
            store.saveAppOverride(pkg, restore, applied = true)
        } else if (restoreAppOverrideLocked(
                "profile ${entering.profileName} was incomplete; restored prior settings"
            )
        ) {
            // Do not retry a profile the kernel declined every ten seconds. It
            // becomes eligible again after the app leaves and re-enters.
            currentApp = pkg
        }
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> screenOn = true
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
        val screenTransition = intent.action == Intent.ACTION_SCREEN_ON ||
            intent.action == Intent.ACTION_SCREEN_OFF
        if (matches.isEmpty() && !screenTransition) return
        scope.launch {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                val restored = appStateMutex.withLock {
                    restoreAppOverrideLocked("restored app-trigger settings when the screen turned off")
                }
                if (!restored) return@launch
            }
            matches.forEach { trigger ->
                if (!fire(trigger) && trigger.type.needsThreshold) armed[trigger.id] = true
            }
            if (intent.action == Intent.ACTION_SCREEN_ON) syncAppPoll()
        }
    }

    private fun thresholdMatches(intent: Intent): List<Trigger> {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val percent = if (level < 0) null else level * 100 / scale
        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }?.let { it / 10 }

        val out = mutableListOf<Trigger>()
        for (t in triggers) {
            // The reading this trigger watches, and which side of the threshold
            // it is on. Both come out of one lookup so the re-arm below cannot
            // read a value the test above never saw.
            val reading = when (t.type) {
                TriggerType.BATTERY_BELOW -> percent
                TriggerType.TEMP_ABOVE -> tempC
                else -> null
            } ?: continue
            val over = when (t.type) {
                TriggerType.BATTERY_BELOW -> reading < t.threshold
                else -> reading > t.threshold
            }

            val isArmed = armed[t.id] ?: true
            if (over && isArmed) {
                out += t
                armed[t.id] = false
            } else if (!over) {
                // Re-arm with a few points of hysteresis so a value sitting exactly
                // on the threshold does not oscillate.
                val clear = when (t.type) {
                    TriggerType.BATTERY_BELOW -> reading >= t.threshold + HYSTERESIS
                    else -> reading <= t.threshold - HYSTERESIS
                }
                if (clear) armed[t.id] = true
            }
        }
        return out
    }

    private suspend fun fire(trigger: Trigger): Boolean {
        val shell = RootShell.get() ?: return false
        val device = model ?: DeviceProbe.probe(shell).also { model = it }
        val profile = profiles.firstOrNull { it.name == trigger.profileName } ?: return false
        val rejections = ProfileEngine.apply(shell, device, profile)
        if (rejections.isEmpty()) store.saveActiveProfile(profile.name)
        notify(
            if (rejections.isEmpty()) "applied ${profile.name}"
            else "applied ${profile.name}, ${rejections.size} setting(s) declined"
        )
        return rejections.isEmpty()
    }

    // --- Notification

    private fun notify(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
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
            val store = ProfileStore(context)
            if (store.loadTriggers().any { it.enabled } || store.loadAppOverride() != null) {
                context.startForegroundService(intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
