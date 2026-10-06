package de.merzkevin.airrelay.bridge

/**
 * Called by native code from UxPlay's own threads (method names and signatures are looked
 * up by name in android_raop_callbacks.c and kept by proguard-rules.pro).
 * Implementations must be thread-safe and must not block.
 */
interface NativeCallbacks {
    /** One H.264 access unit (Annex B), presentation time in nanoseconds. */
    fun onVideoData(data: ByteArray, ptsNs: Long)

    /** Audio stream set up. ct: 2 ALAC, 4 AAC-LC, 8 AAC-ELD; usingScreen: part of mirroring. */
    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean)

    /** Sender volume in dB, -30..0, -144 = mute. */
    fun onVolume(db: Float)

    /** Volume to report to a new sender, in dB. */
    fun onClientVolume(): Float

    fun onAudioTeardown()
    fun onConnectionInit()
    fun onConnectionDestroy()
    fun onMirrorRunning(running: Boolean)

    /** First SETUP of a sender, before any stream exists: true admits it, false makes the
     *  native side answer "403 Forbidden". Called on a UxPlay thread, must not block. */
    fun onClientRequest(): Boolean
}
