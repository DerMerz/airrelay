package de.merzkevin.airrelay

import android.content.Context
import android.content.Intent

/** Release build: no debug entry points. */
object DebugHooks {
    @Suppress("UNUSED_PARAMETER")
    fun onIntent(context: Context, intent: Intent?, service: ReceiverService) = Unit

    fun wrapLocale(base: Context): Context = base

    @Suppress("UNUSED_PARAMETER")
    fun onActivityIntent(activity: android.app.Activity, intent: Intent?) = Unit
}
