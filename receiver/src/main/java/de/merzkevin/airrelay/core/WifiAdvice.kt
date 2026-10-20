/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

/**
 * Wi-Fi hint (T-31b). A stalling picture is almost always the network between the sender and
 * the stick. The hint names the one thing the viewer can change. Nothing of this is stored.
 */
object WifiThresholds {
    /**
     * Below this the signal counts as weak. Voice and video Wi-Fi design guides (Cisco Voice
     * over WLAN and Wireless LAN Design for video) ask for at least -67 dBm at every spot
     * where real-time media runs; -70 leaves three dB of headroom against false alarms.
     * Measured on the stick on 19.10.2026 while the picture stalled: -78 dBm.
     */
    const val WEAK_RSSI_DBM = -70

    /** Android reports this when no value is known (WifiManager.INVALID_RSSI). */
    const val INVALID_RSSI = -127

    /** One stall can be chance (a microwave, a neighbour's download); two on the same
     *  network since the receiver started are a pattern. */
    const val MANY_CATCH_UPS = 2

    /** The hint goes away by itself after this. */
    const val HINT_SHOW_MS = 12_000L

    /** Narrowest free side strip next to the picture that can carry the hint, in dp. With
     *  20 dp margins that leaves 220 dp, three lines of the German text at 20 sp (24 dp
     *  margins left a German line at 720p only 5 px to spare, ScreenFitTest 20.10.2026); a
     *  portrait phone (355 dp strips) and a portrait iPad (277 dp) both qualify.
     *  ScreenFitTest runs a picture whose strip is just above this. */
    const val PILLAR_MIN_DP = 260
}

enum class RssiClass { UNKNOWN, STRONG, WEAK }
enum class BandClass { UNKNOWN, GHZ_24, GHZ_5_PLUS }
enum class CatchUpClass { NONE, ONE, MANY }

enum class WifiAdvice {
    NONE,
    /** Signal too weak: move closer or add an extender. */
    WEAK_SIGNAL,
    /** Signal fine but 2.4 GHz and the picture keeps falling behind: switch to 5 GHz. */
    BUSY_24GHZ,
}

/** @param rssiDbm null when not on Wi-Fi or unreadable */
fun rssiClass(rssiDbm: Int?): RssiClass = when {
    rssiDbm == null || rssiDbm <= WifiThresholds.INVALID_RSSI || rssiDbm >= 0 -> RssiClass.UNKNOWN
    rssiDbm < WifiThresholds.WEAK_RSSI_DBM -> RssiClass.WEAK
    else -> RssiClass.STRONG
}

/** @param frequencyMhz null or a non-positive value when unknown */
fun bandClass(frequencyMhz: Int?): BandClass = when {
    frequencyMhz == null -> BandClass.UNKNOWN
    frequencyMhz in 2400..2500 -> BandClass.GHZ_24
    frequencyMhz >= 4900 -> BandClass.GHZ_5_PLUS
    else -> BandClass.UNKNOWN
}

fun catchUpClass(catchUps: Long): CatchUpClass = when {
    catchUps >= WifiThresholds.MANY_CATCH_UPS -> CatchUpClass.MANY
    catchUps >= 1 -> CatchUpClass.ONE
    else -> CatchUpClass.NONE
}

/** The one place that decides the advice. Full cross product in wifi-table.txt. */
fun wifiAdvice(rssi: RssiClass, band: BandClass, catchUps: CatchUpClass): WifiAdvice = when {
    rssi == RssiClass.WEAK -> WifiAdvice.WEAK_SIGNAL
    band == BandClass.GHZ_24 && catchUps == CatchUpClass.MANY -> WifiAdvice.BUSY_24GHZ
    else -> WifiAdvice.NONE
}

/** Where the hint may appear. */
enum class HintPlace {
    NONE,
    /** One line on the waiting screen, above the IP address. */
    LINE,
    /** In the free strip beside a portrait picture, never on top of it. */
    PILLAR,
}

/**
 * Full cross product in hint-table.txt. While a landscape picture fills the screen there is
 * no free place, so the hint waits for the waiting screen instead of covering the picture.
 * Error, subscription and sound screens already carry their own message (one thing per screen).
 */
fun wifiHintPlace(screen: Screen, advice: WifiAdvice, pillarFree: Boolean): HintPlace = when {
    advice == WifiAdvice.NONE -> HintPlace.NONE
    screen == Screen.WAITING -> HintPlace.LINE
    screen == Screen.MIRRORING && pillarFree -> HintPlace.PILLAR
    else -> HintPlace.NONE
}

/**
 * The wider free strip beside [video] inside [container], or null if it is narrower than
 * [minWidth] (a landscape picture leaves none). Same units as the arguments.
 */
fun hintSlot(video: Rect, container: Size, minWidth: Int): Rect? {
    val left = video.left
    val right = container.width - (video.left + video.width)
    val wide = maxOf(left, right)
    if (wide < minWidth || wide <= 0) return null
    return if (right >= left) Rect(video.left + video.width, 0, right, container.height)
    else Rect(0, 0, left, container.height)
}
