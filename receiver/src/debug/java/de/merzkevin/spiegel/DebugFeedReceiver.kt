package de.merzkevin.spiegel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug build only. Forwards a feed request to the running service without bringing the
 * activity up, so the "bring to front on connect" path can be tested:
 *   adb shell am broadcast -a de.merzkevin.spiegel.DEBUG_FEED -n <appId>/de.merzkevin.spiegel.DebugFeedReceiver \
 *       --es debug_feed test.h264 --ei debug_fps 30
 */
class DebugFeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        context.startService(Intent(context, ReceiverService::class.java).putExtras(extras))
    }
}
