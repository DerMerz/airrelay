package de.merzkevin.airrelay.core

/** What the Back key on the remote does. */
enum class BackAction {
    /** Close the licence overlay, stay on the current screen. */
    CLOSE_LICENSES,
    /** Leave the app; the receiver keeps running in the background. */
    LEAVE_APP,
    /** First Back during mirroring: show "press Back again to stop". */
    SHOW_STOP_HINT,
    /** Second Back while the hint is visible: end the mirroring session. */
    STOP_MIRRORING,
}

/**
 * Pure decision for the Back key.
 *
 * During mirroring one accidental press must not end the session, so the first press only
 * shows a hint and the second one (while the hint is visible) stops. Everywhere else Back
 * leaves the app, as on every other Fire TV app; the receiver stays reachable.
 */
fun backActionFor(screen: Screen, licensesOpen: Boolean, stopHintVisible: Boolean): BackAction {
    if (licensesOpen) return BackAction.CLOSE_LICENSES
    if (screen.showsVideo()) {
        return if (stopHintVisible) BackAction.STOP_MIRRORING else BackAction.SHOW_STOP_HINT
    }
    return BackAction.LEAVE_APP
}
