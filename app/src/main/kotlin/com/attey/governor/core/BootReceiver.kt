package com.attey.governor.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the trigger watcher back after a reboot.
 *
 * This is not how a profile survives a reboot -- that is the generated Magisk
 * module, which runs late enough to beat vendor init. This only restarts the
 * watcher so that *future* triggers still fire.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        TriggerService.sync(context)
    }
}
