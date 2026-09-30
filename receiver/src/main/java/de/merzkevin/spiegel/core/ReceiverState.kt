package de.merzkevin.spiegel.core

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
) {
    fun screen(entitlement: Entitlement): Screen =
        screenFor(server, mirroring, firstFrameShown, audioSession, entitlement)

    /** CPU must stay awake while something is streamed; while idle it may sleep. */
    val needsWakeLock: Boolean get() = connections > 0 && (mirroring || audioSession)

    /** State after the last sender is gone: server fields stay, session fields reset. */
    fun withoutSession(): ReceiverState =
        copy(connections = 0, mirroring = false, firstFrameShown = false, audioSession = false, videoSize = null)
}
