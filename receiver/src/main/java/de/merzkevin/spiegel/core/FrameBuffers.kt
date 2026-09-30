package de.merzkevin.spiegel.core

/** One H.264 access unit as delivered by the sender (Annex B, start codes included). */
class Frame(val data: ByteArray, val ptsUs: Long, val keyframe: Boolean)

/** NAL unit types that let a decoder start: IDR slice (5) and SPS (7). */
fun isH264Keyframe(data: ByteArray): Boolean {
    var i = 0
    val n = data.size
    while (i + 3 < n) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
            val headerAt = when {
                data[i + 2] == 1.toByte() -> i + 3
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> i + 4
                else -> -1
            }
            if (headerAt in 0 until n) {
                val type = data[headerAt].toInt() and 0x1F
                if (type == 5 || type == 7) return true
                i = headerAt
                continue
            }
        }
        i++
    }
    return false
}

/**
 * Keeps every frame since the last keyframe, so the decoder can be (re)started at any
 * moment: when the picture surface appears after the first frames already arrived (app
 * brought to the front on connect), or after the surface was gone (Home and back).
 *
 * Bounded by [maxBytes]. When a group grows past it the buffer empties and waits for the
 * next keyframe; a running decoder is not affected by that, only a restart would be.
 */
class GopBuffer(private val maxBytes: Int) {
    private val frames = ArrayList<Frame>()
    var bytes = 0
        private set
    var overflowed = false
        private set

    val hasKeyframe: Boolean get() = frames.isNotEmpty()
    val size: Int get() = frames.size

    /** Returns true if [frame] is now part of a decodable group. */
    fun add(frame: Frame): Boolean {
        if (frame.keyframe) {
            frames.clear()
            bytes = 0
            overflowed = false
            if (frame.data.size > maxBytes) {
                overflowed = true
                return false
            }
        } else {
            if (frames.isEmpty()) return false
            if (bytes + frame.data.size > maxBytes) {
                frames.clear()
                bytes = 0
                overflowed = true
                return false
            }
        }
        frames.add(frame)
        bytes += frame.data.size
        return true
    }

    fun snapshot(): List<Frame> = ArrayList(frames)

    fun clear() {
        frames.clear()
        bytes = 0
        overflowed = false
    }
}

/**
 * Frames waiting for a free decoder input buffer. If the decoder falls behind by more than
 * [maxFrames] live frames the queue reports an overload; the caller then restarts from the
 * last keyframe instead of showing an ever growing delay.
 *
 * A replayed group ([replaceWith]) may be longer than [maxFrames]; it is always taken in
 * full (without its keyframe it is useless) and does not count as backlog while it drains.
 */
class InputQueue(private val maxFrames: Int) {
    private val frames = ArrayDeque<Frame>()
    private var replayAllowance = 0

    val size: Int get() = frames.size
    fun isEmpty(): Boolean = frames.isEmpty()

    /** false = overloaded, the queue was cleared */
    fun offer(frame: Frame): Boolean {
        if (frames.size >= maxFrames + replayAllowance) {
            clear()
            return false
        }
        frames.addLast(frame)
        return true
    }

    fun poll(): Frame? {
        val f = frames.removeFirstOrNull() ?: return null
        if (replayAllowance > 0) replayAllowance--
        return f
    }

    fun replaceWith(group: List<Frame>) {
        frames.clear()
        group.forEach { frames.addLast(it) }
        replayAllowance = group.size
    }

    fun clear() {
        frames.clear()
        replayAllowance = 0
    }
}
