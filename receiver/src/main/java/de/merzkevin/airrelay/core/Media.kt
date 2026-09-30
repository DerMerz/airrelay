package de.merzkevin.airrelay.core

import kotlin.math.pow
import kotlin.math.roundToInt

const val GAIN_UNITY_Q15 = 32768

/** AirPlay reports this for "muted". */
const val AIRPLAY_MUTE_DB = -144f

/** Quietest audible AirPlay volume. */
const val AIRPLAY_MIN_DB = -30f

/**
 * Sender volume (dB, -30..0, -144 = mute) as a Q15 software gain for the audio output.
 * The Fire TV's own volume is left alone: the TV remote keeps full control, and a session
 * cannot leave the device muted after it ends.
 */
fun airplayVolumeToGainQ15(db: Float): Int {
    if (db.isNaN() || db <= AIRPLAY_MUTE_DB) return 0
    if (db >= 0f) return GAIN_UNITY_Q15
    val clamped = db.coerceAtLeast(AIRPLAY_MIN_DB)
    return (10.0.pow(clamped / 20.0) * GAIN_UNITY_Q15).roundToInt().coerceIn(1, GAIN_UNITY_Q15)
}

/** A video decoder as seen in MediaCodecList, reduced to what the choice needs. */
data class DecoderInfo(val name: String, val softwareOnly: Boolean)

private val SOFTWARE_PREFIXES = listOf("omx.google.", "c2.android.", "omx.ffmpeg.", "c2.ffmpeg.")

/** Name based check for API < 29, where MediaCodecInfo.isSoftwareOnly does not exist. */
fun looksLikeSoftwareDecoder(name: String): Boolean {
    val n = name.lowercase()
    return SOFTWARE_PREFIXES.any { n.startsWith(it) } || n.contains(".sw.") || n.endsWith(".sw")
}

/**
 * First hardware H.264 decoder in system order, else the first software one.
 * On the Stick Lite that is OMX.MTK.VIDEO.DECODER.AVC.
 */
fun chooseDecoder(candidates: List<DecoderInfo>): DecoderInfo? =
    candidates.firstOrNull { !it.softwareOnly && !looksLikeSoftwareDecoder(it.name) }
        ?: candidates.firstOrNull()

/**
 * MediaFormat options for successive configure attempts, most aggressive first. Strict
 * decoders reject unknown or unsupported keys at configure(); the next attempt drops them.
 *  - "low-latency": standard key, API 30+
 *  - "vdec-lowlatency": MediaTek vendor key (Fire TV sticks)
 *  - "priority" 0: realtime codec priority
 */
fun lowLatencyLadder(sdkInt: Int): List<Map<String, Int>> {
    val full = LinkedHashMap<String, Int>()
    if (sdkInt >= 30) full["low-latency"] = 1
    full["vdec-lowlatency"] = 1
    full["priority"] = 0
    val steps = listOf(
        full,
        full - "low-latency",
        full - "low-latency" - "vdec-lowlatency",
        emptyMap(),
    )
    return steps.distinct()
}

/**
 * Picks the address shown on the waiting screen and used to decide whether there is a
 * network at all: first IPv4 on wlan*, then eth*, never loopback or link-local (169.254).
 */
fun pickIpv4(interfaces: List<Pair<String, List<String>>>): String? {
    fun usable(addr: String): Boolean {
        val parts = addr.split('.')
        if (parts.size != 4 || parts.any { p -> p.toIntOrNull()?.let { it in 0..255 } != true }) return false
        if (parts[0] == "127" || parts[0] == "0") return false
        if (parts[0] == "169" && parts[1] == "254") return false
        return true
    }
    for (prefix in listOf("wlan", "eth")) {
        for ((name, addrs) in interfaces) {
            if (!name.startsWith(prefix)) continue
            addrs.firstOrNull(::usable)?.let { return it }
        }
    }
    return null
}
