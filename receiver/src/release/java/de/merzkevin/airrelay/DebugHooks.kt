/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
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
