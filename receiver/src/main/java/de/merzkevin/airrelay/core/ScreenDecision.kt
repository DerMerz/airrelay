package de.merzkevin.airrelay.core

/** State of the local receiver server. */
enum class ServerStatus {
    /** Server is being started (or restarted after a network change). */
    STARTING,
    /** Listening and announced over dns-sd. */
    RUNNING,
    /** No Wi-Fi or Ethernet address, nothing to announce on. */
    NO_NETWORK,
    /** The listening socket could not be bound on any port that was tried. */
    PORT_IN_USE,
    /** Native initialisation failed (keys, dns-sd records, audio engine). */
    START_FAILED,
}

/**
 * Whether this device may use the receiver: the coarse form of [Access]
 * ([accessFor], Amazon subscription with a 7 day trial). [LicenseGate.current] decides,
 * [screenFor] shows.
 */
enum class Entitlement { UNLOCKED, LOCKED }

/** What the single activity shows. */
enum class Screen {
    STARTING,
    /** Waiting screen: receiver name, how-to, IP. */
    WAITING,
    /** Mirroring has started but no picture is on screen yet. */
    CONNECTING,
    /** Full-screen picture. */
    MIRRORING,
    /** Sound only (music from an iPhone), waiting layout with an audio hint. */
    AUDIO,
    NO_NETWORK,
    PORT_IN_USE,
    START_FAILED,
    LOCKED,
}

/**
 * The one place that decides what is on screen. Pure: no Android, no clock, no storage.
 *
 * Precedence: entitlement, then server health, then the session.
 * A running mirror session wins over audio, because mirroring carries its own sound.
 */
fun screenFor(
    server: ServerStatus,
    mirroring: Boolean,
    firstFrameShown: Boolean,
    audioSession: Boolean,
    entitlement: Entitlement,
): Screen {
    if (entitlement == Entitlement.LOCKED) return Screen.LOCKED
    return when (server) {
        ServerStatus.STARTING -> Screen.STARTING
        ServerStatus.NO_NETWORK -> Screen.NO_NETWORK
        ServerStatus.PORT_IN_USE -> Screen.PORT_IN_USE
        ServerStatus.START_FAILED -> Screen.START_FAILED
        ServerStatus.RUNNING -> when {
            mirroring && firstFrameShown -> Screen.MIRRORING
            mirroring -> Screen.CONNECTING
            audioSession -> Screen.AUDIO
            else -> Screen.WAITING
        }
    }
}

/** Screens that show the video surface instead of the waiting layout. */
fun Screen.showsVideo(): Boolean = this == Screen.CONNECTING || this == Screen.MIRRORING

/** Screens that report a problem and offer a way out. */
fun Screen.isError(): Boolean =
    this == Screen.NO_NETWORK || this == Screen.PORT_IN_USE || this == Screen.START_FAILED

/**
 * The one place the receiver asks "may a sender start?". Written by the subscription code
 * (billing/Subscriptions) on the main thread, read from UxPlay threads.
 * Starts closed: until the stored or fresh status is known, nobody is admitted.
 */
object LicenseGate {
    @Volatile var access: Access = Access.CHECKING

    fun current(): Entitlement = access.entitlement()

    /** The decision at a sender's first SETUP: only [Access.UNLOCKED] lets it in. */
    fun admitsSender(): Boolean = access.admitsSender
}
