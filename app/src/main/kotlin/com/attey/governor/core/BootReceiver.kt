package com.attey.governor.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Never restore a pre-app snapshot from the previous boot.
        ProfileStore(context).clearAppOverride()
        TriggerService.sync(context)
    }
}
