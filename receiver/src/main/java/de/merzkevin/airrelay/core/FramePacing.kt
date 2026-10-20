/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

/**
 * What happens to a decoded picture. Up to 0.1.2 every picture was shown the moment the
 * decoder handed it out, so after a Wi-Fi stall the backlog ran back to back (time-lapse)
 * while the sound, which has its own jitter buffer, stayed live. Now late pictures are
 * dropped and the newest one is shown (a jump); the sound is not touched and stays the lead.
 *
 * All times in microseconds on one clock (System.nanoTime / 1000 on the device; the
 * presentation times of the mirroring stream are on the same local basis, see UxPlay
 * ntp_time_local, but with an unknown fixed offset, so lateness is always measured relative
 * to the best value seen recently, never absolutely).
 */
object FramePacing {
    /**
     * A picture counts as late once it trails the sound by more than this. 100 ms stays
     * below the 125 ms at which viewers detect a picture lagging its sound (ITU-R BT.1359),
     * and is six frames at 60 fps, far above Wi-Fi jitter.
     */
    const val LATE_US = 100_000L

    /** Pacing runs this much faster than the stream per frame, so a lag left after a jump
     *  melts away (about 60 ms per second at 60 fps) instead of staying forever. */
    const val SLEW_US = 1_000L

    /** A picture due within this is shown right away instead of being scheduled. */
    const val PACE_TOL_US = 1_000L

    /** Scheduling never reaches further ahead than this (protects against a jump of the
     *  presentation times on the sender side). */
    const val PACE_MAX_US = 150_000L

    /** While dropping, at least one picture per this period is shown, so a decoder that
     *  never catches up still moves instead of freezing. */
    const val HOLD_MAX_US = 1_000_000L

    /** The "best recent" delay and buffer are taken over this span. Long enough that a
     *  catch-up after a 1.5 s stall still sees the reference from before the stall; short
     *  enough that a lasting change of the network delay becomes the new normal within 3 s
     *  instead of being dropped against forever. */
    const val BASELINE_SPAN_US = 3_000_000L
    const val BASELINE_BUCKET_US = 250_000L

    /** Two shown pictures closer than this share of their stream distance count as
     *  time-lapse (diagnostics and tests). */
    const val FAST_RATIO = 0.75
}

/** Where a decoded picture would go. */
enum class FrameTarget {
    /** No display: the decoder writes to the parking surface. */
    PARKED,
    /** Decoder restart: frames since the keyframe are decoded again only to rebuild references. */
    REPLAY,
    /** On the display. */
    LIVE,
}

enum class FrameAction {
    /** Not shown, nothing to decide (parked or replay). */
    HIDE,
    /** Shown now. */
    SHOW,
    /** Shown at its stream distance from the previous picture (no time-lapse). */
    PACE,
    /** Late with newer pictures already received: skipped. */
    DROP,
    /** First picture after skipped ones: shown now, the jump to the newest picture. */
    JUMP,
}

fun frameTarget(onDisplay: Boolean, ptsUs: Long, renderFromPtsUs: Long): FrameTarget = when {
    ptsUs < renderFromPtsUs -> FrameTarget.REPLAY
    !onDisplay -> FrameTarget.PARKED
    else -> FrameTarget.LIVE
}

/**
 * Whether a picture opens a new catch-up phase (Aufholphase) for the counter that feeds the
 * Wi-Fi hint. Pure: no Android, no clock. Full cross product in catchup-table.txt.
 *
 * A phase is a run of pictures that are late and behind. It is counted once, and only if the
 * network delivered late: a decoder restart (Home and back, the app brought to the front, an
 * overloaded decoder) also leaves the picture late and behind for a moment, but every frame
 * arrived on time, so that is not the Wi-Fi (T-31c, Prüfer 0.1.3 Befund 1). A phase that
 * started without network lateness is still counted once the network turns out to be late.
 *
 * @param lateAndBehind the picture trails the sound and newer picture is already buffered
 * @param phaseCounted the current phase was already counted
 * @param networkLate frames arrived late from the network and the picture has not yet caught
 *   up past them (see [FramePacer.onReceived])
 */
fun countsAsCatchUp(lateAndBehind: Boolean, phaseCounted: Boolean, networkLate: Boolean): Boolean =
    lateAndBehind && !phaseCounted && networkLate

/**
 * The one place that decides what happens to a decoded picture. Pure: no Android, no clock.
 * Full cross product in frame-table.txt.
 *
 * @param late picture time trails the sound time by more than [FramePacing.LATE_US]
 * @param behind the received buffer already holds more than [FramePacing.LATE_US] of newer picture
 * @param tooSoon showing it now would run faster than the stream
 * @param heldTooLong nothing was shown for [FramePacing.HOLD_MAX_US]
 * @param droppedBefore the previous picture was dropped
 */
fun frameAction(
    target: FrameTarget,
    late: Boolean,
    behind: Boolean,
    tooSoon: Boolean,
    heldTooLong: Boolean,
    droppedBefore: Boolean,
): FrameAction = when {
    target != FrameTarget.LIVE -> FrameAction.HIDE
    late && behind && !heldTooLong -> FrameAction.DROP
    droppedBefore -> FrameAction.JUMP
    tooSoon -> FrameAction.PACE
    else -> FrameAction.SHOW
}

/** Sound time estimate: the sound plays live, so it is "now" on the stream basis that the
 *  best recent picture defined. [baselineUs] is the smallest recent now - picture time. */
fun audioClockUs(nowUs: Long, baselineUs: Long): Long = nowUs - baselineUs

/** Bildzeit against Tonzeit. */
fun isLate(ptsUs: Long, audioClockUs: Long): Boolean = audioClockUs - ptsUs > FramePacing.LATE_US

/**
 * Bildzeit against Puffer: newer picture already received beyond the usual decoder depth.
 * [bufferBaseUs] is the smallest recent latest - picture time (the decoder's own depth).
 */
fun isBehind(ptsUs: Long, latestReceivedPtsUs: Long, bufferBaseUs: Long): Boolean =
    latestReceivedPtsUs - ptsUs - bufferBaseUs > FramePacing.LATE_US

/** When the picture is due: its stream distance after the previous one, minus the slew. */
fun paceTargetUs(lastShownAtUs: Long, lastShownPtsUs: Long, ptsUs: Long): Long =
    lastShownAtUs + (ptsUs - lastShownPtsUs) - FramePacing.SLEW_US

fun isTooSoon(nowUs: Long, targetUs: Long?): Boolean =
    targetUs != null && nowUs < targetUs - FramePacing.PACE_TOL_US

fun isHeldTooLong(nowUs: Long, holdSinceUs: Long?): Boolean =
    holdSinceUs != null && nowUs - holdSinceUs > FramePacing.HOLD_MAX_US

/**
 * Smallest value seen in the last [spanUs], kept in buckets of [bucketUs] (constant memory,
 * no allocation per frame). Time based, not count based, so a burst of hundreds of late
 * pictures cannot push the good reference out early. Not thread-safe; one per video thread.
 */
class TimedMin(private val spanUs: Long, private val bucketUs: Long) {
    private val slots = (spanUs / bucketUs).toInt()
    private val ids = LongArray(slots)
    private val mins = LongArray(slots)
    private var used = 0
    private var head = 0 // index of the oldest bucket

    init { require(bucketUs > 0 && slots > 0) }

    fun add(nowUs: Long, v: Long) {
        val id = Math.floorDiv(nowUs, bucketUs)
        while (used > 0 && ids[head] <= id - slots) {
            head = (head + 1) % slots
            used--
        }
        val last = (head + used - 1 + slots) % slots
        if (used > 0 && ids[last] >= id) {
            if (v < mins[last]) mins[last] = v
            return
        }
        if (used == slots) { // cannot happen after the expiry above, kept for safety
            head = (head + 1) % slots
            used--
        }
        val at = (head + used) % slots
        ids[at] = id
        mins[at] = v
        used++
    }

    fun min(): Long? {
        if (used == 0) return null
        var m = Long.MAX_VALUE
        for (k in 0 until used) {
            val v = mins[(head + k) % slots]
            if (v < m) m = v
        }
        return m
    }

    fun clear() {
        used = 0
        head = 0
    }
}

data class PacedFrame(val action: FrameAction, val renderAtUs: Long?)

/**
 * State around [frameAction] for one mirroring session. Video thread only.
 * Counters are for the session log and the Wi-Fi hint.
 */
class FramePacer {
    private val delayBase = TimedMin(FramePacing.BASELINE_SPAN_US, FramePacing.BASELINE_BUCKET_US)
    private val bufferBase = TimedMin(FramePacing.BASELINE_SPAN_US, FramePacing.BASELINE_BUCKET_US)
    /** Smallest recent arrival time - picture time: the network delay when all goes well. */
    private val arrivalBase = TimedMin(FramePacing.BASELINE_SPAN_US, FramePacing.BASELINE_BUCKET_US)
    private var latestReceivedPtsUs: Long? = null
    /** A frame arrived late from the network and the picture has not caught up since. Set on
     *  arrival, cleared by the first picture that is no longer late and behind. */
    private var networkLate = false
    private var lastShownAtUs: Long? = null
    private var lastShownPtsUs = 0L
    private var holdSinceUs: Long? = null
    private var droppedBefore = false
    private var phaseCounted = false

    /** Pictures skipped because they were late. */
    var drops = 0L; private set
    /** Jumps to a newer picture after skipping. */
    var jumps = 0L; private set
    /** Phases in which the picture fell behind because the network delivered late and had to
     *  catch up (Aufholphasen). A decoder restart alone never counts. */
    var catchUps = 0L; private set
    /** Shown pictures that followed the previous one faster than [FramePacing.FAST_RATIO]
     *  of their stream distance, outside a jump. Must stay 0. */
    var fastShown = 0L; private set

    /**
     * A frame arrived from the sender (before decoding). [arrivedAtUs] is taken on the thread
     * that received it, before it waits for the video thread, so a busy or restarting decoder
     * never looks like a late network.
     */
    fun onReceived(ptsUs: Long, arrivedAtUs: Long) {
        latestReceivedPtsUs = ptsUs
        val delay = arrivedAtUs - ptsUs
        arrivalBase.add(arrivedAtUs, delay)
        if (delay - (arrivalBase.min() ?: delay) > FramePacing.LATE_US) networkLate = true
    }

    /** The decoder handed out a picture. */
    fun onOutput(ptsUs: Long, nowUs: Long, target: FrameTarget): PacedFrame {
        if (target == FrameTarget.REPLAY) return PacedFrame(FrameAction.HIDE, null)
        delayBase.add(nowUs, nowUs - ptsUs)
        val latest = latestReceivedPtsUs
        if (latest != null) bufferBase.add(nowUs, latest - ptsUs)
        val late = delayBase.min()?.let { isLate(ptsUs, audioClockUs(nowUs, it)) } ?: false
        val behind = if (latest == null) false else isBehind(ptsUs, latest, bufferBase.min() ?: 0L)
        // caught up (on the display or parked): the phase and the late network are over
        if (!(late && behind)) {
            networkLate = false
            phaseCounted = false
        }
        if (target != FrameTarget.LIVE) return PacedFrame(FrameAction.HIDE, null)

        if (holdSinceUs == null) holdSinceUs = nowUs
        if (countsAsCatchUp(late && behind, phaseCounted, networkLate)) {
            catchUps++
            phaseCounted = true
        }

        val dueAt = lastShownAtUs?.let { paceTargetUs(it, lastShownPtsUs, ptsUs) }
        val action = frameAction(
            target, late, behind,
            tooSoon = isTooSoon(nowUs, dueAt),
            heldTooLong = isHeldTooLong(nowUs, holdSinceUs),
            droppedBefore = droppedBefore,
        )
        when (action) {
            FrameAction.HIDE -> return PacedFrame(action, null)
            FrameAction.DROP -> {
                drops++
                droppedBefore = true
                return PacedFrame(action, null)
            }
            else -> Unit
        }
        val renderAt = if (action == FrameAction.PACE) minOf(dueAt!!, nowUs + FramePacing.PACE_MAX_US) else nowUs
        val last = lastShownAtUs
        if (action == FrameAction.JUMP) {
            jumps++
        } else if (last != null) {
            val dPts = ptsUs - lastShownPtsUs
            if (dPts > 0 && renderAt - last < FramePacing.FAST_RATIO * dPts) fastShown++
        }
        lastShownAtUs = renderAt
        lastShownPtsUs = ptsUs
        holdSinceUs = nowUs
        droppedBefore = false
        return PacedFrame(action, renderAt)
    }

    /**
     * Decoder (re)started: the picture history no longer applies; baselines and counters stay.
     * So does the catch-up phase: a restart in the middle of one (a stall that overflowed the
     * queue) continues it instead of opening a second one, and the network state is about the
     * network, which a restart does not change.
     */
    fun resetOutput() {
        lastShownAtUs = null
        lastShownPtsUs = 0L
        holdSinceUs = null
        droppedBefore = false
    }

    /** New mirroring session: everything from scratch. */
    fun resetSession() {
        resetOutput()
        delayBase.clear()
        bufferBase.clear()
        arrivalBase.clear()
        latestReceivedPtsUs = null
        networkLate = false
        phaseCounted = false
        drops = 0
        jumps = 0
        catchUps = 0
        fastShown = 0
    }
}
