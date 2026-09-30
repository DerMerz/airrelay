package de.merzkevin.spiegel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the receiver after boot. (Switching this off is phase 2.) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ReceiverService.start(context)
    }
}
