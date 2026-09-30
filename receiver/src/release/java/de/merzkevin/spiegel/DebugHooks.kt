package de.merzkevin.spiegel

import android.content.Context
import android.content.Intent

/** Release build: no debug entry points. */
object DebugHooks {
    @Suppress("UNUSED_PARAMETER")
    fun onIntent(context: Context, intent: Intent?, service: ReceiverService) = Unit
}
