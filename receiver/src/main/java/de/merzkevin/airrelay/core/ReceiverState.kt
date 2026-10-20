/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

/** Everything the UI needs, owned by the service, replaced as a whole on every change. */
data class ReceiverState(
    val server: ServerStatus = ServerStatus.STARTING,
    /** Name as announced (dns-sd may have appended a suffix on a name clash). */
    val name: String = FALLBACK_RECEIVER_NAME,
    val ip: String? = null,
    val port: Int = 0,
    val connections: Int = 0,
    val mirroring: Boolean = false,
    val firstFrameShown: Boolean = false,
    val audioSession: Boolean = false,
    val videoSize: Size? = null,
    /** Wi-Fi hint (T-31b). Survives the end of a session: the advice belongs to the network. */
    val wifiAdvice: WifiAdvice = WifiAdvice.NONE,
    /** Raised each time the hint should be shown again; the screen shows it once per value. */
    val wifiHintSeq: Int = 0,
) {
    fun screen(entitlement: Entitlement): Screen =
        screenFor(server, mirroring, firstFrameShown, audioSession, entitlement)

    /** CPU must stay awake while something is streamed; while idle it may sleep. */
    val needsWakeLock: Boolean get() = connections > 0 && (mirroring || audioSession)

    /**
     * New Wi-Fi reading. The hint is raised (shown once more) when there is advice and either
     * the moment calls for it ([announce]: receiver ready, mirroring starts) or the advice
     * changed. A reading after a stall alone does not repeat the same advice.
     */
    fun withWifiAdvice(advice: WifiAdvice, announce: Boolean): ReceiverState {
        val raise = advice != WifiAdvice.NONE && (announce || advice != wifiAdvice)
        return copy(wifiAdvice = advice, wifiHintSeq = if (raise) wifiHintSeq + 1 else wifiHintSeq)
    }

    /** State while the receiver (re)starts: everything fresh except the hint counter, which
     *  never goes back, so a value the screen has seen is not reused for a new hint. */
    fun restarting(name: String, ip: String?): ReceiverState =
        ReceiverState(server = ServerStatus.STARTING, name = name, ip = ip, wifiHintSeq = wifiHintSeq)

    /** State after the last sender is gone: server fields stay, session fields reset. */
    fun withoutSession(): ReceiverState =
        copy(connections = 0, mirroring = false, firstFrameShown = false, audioSession = false, videoSize = null)
}
