package de.merzkevin.airrelay.core

/** Fixed receiver settings. There is no settings screen; these are the product decisions. */
object ReceiverConfig {
    /** Usual AirPlay port. If it is taken (another receiver app), the OS picks a free one;
     *  senders read the port from dns-sd, so any port works. */
    val PORTS_TO_TRY = listOf(7000, 0)

    /**
     * Frame rate announced to the sender (maxFPS / refreshRate). Chosen from a decode
     * measurement on the Fire TV Stick Lite, see README ("Bildrate").
     */
    const val MAX_FPS = 60

    /** ALAC is decoded in software (FFmpeg) if a sender uses it anyway; announced are
     *  AAC-LC and AAC-ELD only, as in the upstream defaults. */
    const val ADVERTISE_ALAC = false

    /** Upper bound of the keyframe group kept for decoder restarts. */
    const val GOP_MAX_BYTES = 4 * 1024 * 1024

    /** Live frames the decoder may fall behind before it is restarted at a keyframe. */
    const val MAX_PENDING_FRAMES = 60

    /** How long "press Back again to stop" stays armed. */
    const val STOP_HINT_MS = 3000L
}

/**
 * Result of one start attempt. [ip] null means no usable network; [nativeReady] false
 * means the native server could not be created; [boundPort] null means no port could be
 * bound.
 */
fun startOutcome(ip: String?, nativeReady: Boolean, boundPort: Int?): ServerStatus = when {
    ip == null -> ServerStatus.NO_NETWORK
    !nativeReady -> ServerStatus.START_FAILED
    boundPort == null -> ServerStatus.PORT_IN_USE
    else -> ServerStatus.RUNNING
}
