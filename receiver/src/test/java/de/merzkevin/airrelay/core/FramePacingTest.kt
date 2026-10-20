/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picture pacing (T-31b): after a Wi-Fi stall the picture jumps to the newest frame instead
 * of replaying the backlog in time-lapse. The decision table is in DecisionTablesTest; here
 * the classifiers at their edges and a deterministic simulation of sender, Wi-Fi and decoder.
 */
class FramePacingTest {

    // ---- classifiers at their edges ----

    @Test
    fun lateStartsAboveOneHundredMilliseconds() {
        assertFalse(isLate(ptsUs = 0, audioClockUs = 100_000))
        assertTrue(isLate(ptsUs = 0, audioClockUs = 100_001))
        assertFalse(isLate(ptsUs = 500_000, audioClockUs = 400_000)) // picture ahead of the sound
        assertEquals(1_000_000L - 250_000L, audioClockUs(nowUs = 1_000_000, baselineUs = 250_000))
    }

    @Test
    fun behindCountsOnlyBufferBeyondTheDecoderDepth() {
        assertFalse(isBehind(ptsUs = 0, latestReceivedPtsUs = 100_000, bufferBaseUs = 0))
        assertTrue(isBehind(ptsUs = 0, latestReceivedPtsUs = 100_001, bufferBaseUs = 0))
        // a decoder that always holds 133 ms (4 frames at 30 fps) is not behind at 233 ms
        assertFalse(isBehind(ptsUs = 0, latestReceivedPtsUs = 233_000, bufferBaseUs = 133_000))
        assertTrue(isBehind(ptsUs = 0, latestReceivedPtsUs = 233_001, bufferBaseUs = 133_000))
        // newest received is older (sender restarted its clock): never behind
        assertFalse(isBehind(ptsUs = 900_000, latestReceivedPtsUs = 0, bufferBaseUs = 0))
    }

    @Test
    fun paceTargetKeepsTheStreamDistanceMinusTheSlew() {
        assertEquals(1_000_000L + 16_667L - FramePacing.SLEW_US, paceTargetUs(1_000_000, 50_000, 66_667))
        assertFalse(isTooSoon(nowUs = 100, targetUs = null))
        assertFalse(isTooSoon(nowUs = 9_000, targetUs = 10_000)) // within tolerance
        assertTrue(isTooSoon(nowUs = 8_999, targetUs = 10_000))
    }

    @Test
    fun heldTooLongStartsAboveOneSecond() {
        assertFalse(isHeldTooLong(nowUs = 5_000_000, holdSinceUs = null))
        assertFalse(isHeldTooLong(nowUs = 1_000_000, holdSinceUs = 0))
        assertTrue(isHeldTooLong(nowUs = 1_000_001, holdSinceUs = 0))
    }

    @Test
    fun frameTargetHidesReplayAndParkedPictures() {
        assertEquals(FrameTarget.REPLAY, frameTarget(onDisplay = true, ptsUs = 9, renderFromPtsUs = 10))
        assertEquals(FrameTarget.REPLAY, frameTarget(onDisplay = false, ptsUs = 9, renderFromPtsUs = 10))
        assertEquals(FrameTarget.LIVE, frameTarget(onDisplay = true, ptsUs = 10, renderFromPtsUs = 10))
        assertEquals(FrameTarget.PARKED, frameTarget(onDisplay = false, ptsUs = 10, renderFromPtsUs = 10))
        assertEquals(FrameTarget.LIVE, frameTarget(onDisplay = true, ptsUs = 0, renderFromPtsUs = Long.MIN_VALUE))
    }

    @Test
    fun timedMinForgetsValuesOlderThanTheSpan() {
        val m = TimedMin(spanUs = 1_000, bucketUs = 250) // 4 buckets
        assertNull(m.min())
        m.add(0, 5)
        m.add(100, 1)
        m.add(300, 7)
        assertEquals(1L, m.min())
        m.add(999, 9)
        assertEquals("bucket 0 is still inside the span", 1L, m.min())
        m.add(1_000, 8)
        assertEquals("bucket 0 (values 5 and 1) has expired", 7L, m.min())
        m.add(5_000, 20)
        assertEquals("a long pause expires everything older", 20L, m.min())
        m.add(4_000, 3) // clock never goes back on the device; if it did, the value still counts
        assertEquals(3L, m.min())
        m.clear()
        assertNull(m.min())
        m.add(-600, -3)
        assertEquals(-3L, m.min())
    }

    @Test
    fun timedMinHoldsManyValuesInConstantSpace() {
        val m = TimedMin(FramePacing.BASELINE_SPAN_US, FramePacing.BASELINE_BUCKET_US)
        for (t in 0 until 10_000_000 step 1_000) m.add(t.toLong(), 1_000_000L - t)
        // only the last 3 s count: smallest is the most recent value
        assertEquals(1_000_000L - 9_999_000L, m.min())
    }

    // ---- FramePacer, hand-fed ----

    @Test
    fun replayPicturesNeitherShowNorMoveTheBaseline() {
        val p = FramePacer()
        p.onReceived(0, arrivedAtUs = 0)
        // a replayed picture 5 s "late" must not become the reference
        assertEquals(FrameAction.HIDE, p.onOutput(ptsUs = -5_000_000, nowUs = 0, target = FrameTarget.REPLAY).action)
        p.onReceived(16_667, arrivedAtUs = 16_667)
        val f = p.onOutput(ptsUs = 16_667, nowUs = 16_667, target = FrameTarget.LIVE)
        assertEquals(FrameAction.SHOW, f.action)
        assertEquals(16_667L, f.renderAtUs)
        assertEquals(0L, p.drops)
    }

    @Test
    fun parkedPicturesAreHiddenButDefineTheBaseline() {
        val p = FramePacer()
        p.onReceived(0, arrivedAtUs = 0)
        assertEquals(FrameAction.HIDE, p.onOutput(0, 10_000, FrameTarget.PARKED).action)
        // display appears; after a stall the burst arrives at once with 400 ms of newer picture
        for (i in 1..25) p.onReceived(i * 16_667L, arrivedAtUs = 430_000)
        val f = p.onOutput(16_667, 430_000, FrameTarget.LIVE)
        assertEquals(FrameAction.DROP, f.action)
        assertNull(f.renderAtUs)
        assertEquals(1L, p.catchUps)
    }

    @Test
    fun aBurstIsDroppedThenJumpedOverAndCountedAsOnePhase() {
        val p = FramePacer()
        val d = 16_667L
        p.onReceived(0, arrivedAtUs = 0)
        assertEquals(FrameAction.SHOW, p.onOutput(0, 0, FrameTarget.LIVE).action)
        val now = 600_000L
        // 40 frames arrive at once after a stall (pacing test: the latest ones are fed early on
        // purpose, so that the picture stays behind until it is no longer late)
        for (i in 1..40) p.onReceived(i * d, arrivedAtUs = now)
        val actions = (1..40).map { i -> p.onOutput(i * d, now + i * 1_000L, FrameTarget.LIVE).action }
        val firstShown = actions.indexOfFirst { it != FrameAction.DROP }
        assertTrue("drops first", firstShown > 0)
        assertTrue(actions.take(firstShown).all { it == FrameAction.DROP })
        assertEquals(FrameAction.JUMP, actions[firstShown])
        assertTrue("after the jump only paced or shown", actions.drop(firstShown + 1).none { it == FrameAction.DROP || it == FrameAction.JUMP })
        assertEquals(firstShown.toLong(), p.drops)
        assertEquals(1L, p.jumps)
        assertEquals(1L, p.catchUps)
        assertEquals(0L, p.fastShown)
        // the jump lands on the first picture that trails the sound by 100 ms or less
        val jumpedPts = (firstShown + 1) * d
        val jumpedAt = now + (firstShown + 1) * 1_000L
        assertTrue(jumpedAt - jumpedPts <= FramePacing.LATE_US)
        val lastDroppedPts = firstShown * d
        assertTrue(jumpedAt - 1_000L - lastDroppedPts > FramePacing.LATE_US)
    }

    @Test
    fun pacingSpreadsAShortBurstAtStreamSpeed() {
        val p = FramePacer()
        val d = 16_667L
        p.onReceived(0, arrivedAtUs = 0)
        p.onOutput(0, 0, FrameTarget.LIVE)
        for (i in 1..4) p.onReceived(i * d, arrivedAtUs = 69_000) // 67 ms burst: below the drop threshold
        val frames = (1..4).map { i -> p.onOutput(i * d, 70_000 + i * 500L, FrameTarget.LIVE) }
        assertEquals(FrameAction.SHOW, frames[0].action) // already due
        assertTrue(frames.drop(1).all { it.action == FrameAction.PACE })
        for (k in 1 until frames.size) {
            assertEquals(d - FramePacing.SLEW_US, frames[k].renderAtUs!! - frames[k - 1].renderAtUs!!)
        }
        assertEquals(0L, p.drops)
        assertEquals(0L, p.fastShown)
    }

    @Test
    fun pacingNeverSchedulesFurtherThanTheCap() {
        val p = FramePacer()
        p.onReceived(0, arrivedAtUs = 0)
        p.onOutput(0, 0, FrameTarget.LIVE)
        p.onReceived(5_000_000, arrivedAtUs = 19_000) // sender clock jumped 5 s ahead
        val f = p.onOutput(5_000_000, 20_000, FrameTarget.LIVE)
        assertEquals(FrameAction.PACE, f.action)
        assertEquals(20_000L + FramePacing.PACE_MAX_US, f.renderAtUs)
    }

    @Test
    fun aBackwardClockJumpOfTheSenderIsShownNotDropped() {
        val p = FramePacer()
        val d = 16_667L
        for (i in 0..5) { p.onReceived(10_000_000 + i * d, i * d); p.onOutput(10_000_000 + i * d, i * d, FrameTarget.LIVE) }
        // new stream starts at 0: looks 10 s late against the old baseline, but nothing newer is buffered
        p.onReceived(0, arrivedAtUs = 6 * d)
        assertEquals(FrameAction.SHOW, p.onOutput(0, 6 * d, FrameTarget.LIVE).action)
        assertEquals(0L, p.drops)
    }

    @Test
    fun aLastingBacklogShowsOnePicturePerSecondThenBecomesTheNewNormal() {
        val p = FramePacer()
        val d = 16_667L
        p.onReceived(0, arrivedAtUs = 0)
        p.onOutput(0, 0, FrameTarget.LIVE)
        // a 500 ms stall: what the sender captured meanwhile arrives at once ...
        for (k in 1..30) p.onReceived(k * d, arrivedAtUs = 500_000)
        // ... and from here on every picture comes out 500 ms late with 500 ms of newer picture buffered
        val shownAt = mutableListOf(0L)
        var dropsAfterSpan = 0
        var now = 500_000L
        var pts = d
        while (now < 6_000_000) {
            p.onReceived(pts + 500_000, arrivedAtUs = now)
            val f = p.onOutput(pts, now, FrameTarget.LIVE)
            if (f.renderAtUs != null) shownAt += f.renderAtUs!!
            if (f.action == FrameAction.DROP && now > 500_000 + FramePacing.BASELINE_SPAN_US + FramePacing.BASELINE_BUCKET_US) dropsAfterSpan++
            now += d
            pts += d
        }
        val gaps = shownAt.zipWithNext { a, b -> b - a }
        // while dropping: never longer than the hold limit without a new picture
        assertTrue("longest gap ${gaps.max()}", gaps.max() <= FramePacing.HOLD_MAX_US + d)
        assertTrue("drops ${p.drops}", p.drops > 100)
        // after the span the 500 ms are the reference: smooth again, not dropped forever
        assertEquals(0, dropsAfterSpan)
        assertEquals(1L, p.catchUps) // one long phase, not one per held picture
    }

    @Test
    fun resetOutputForgetsThePictureHistoryButKeepsTheCounters() {
        val p = FramePacer()
        val d = 16_667L
        p.onReceived(0, arrivedAtUs = 0)
        p.onOutput(0, 0, FrameTarget.LIVE)
        for (i in 1..40) p.onReceived(i * d, arrivedAtUs = 600_000)
        p.onOutput(d, 600_000, FrameTarget.LIVE) // DROP
        assertEquals(1L, p.drops)
        p.resetOutput()
        // after a decoder restart the newest picture is shown at once, no jump owed, no pacing
        val f = p.onOutput(40 * d, 600_100, FrameTarget.LIVE)
        assertEquals(FrameAction.SHOW, f.action)
        assertEquals(1L, p.drops)
        p.resetSession()
        assertEquals(0L, p.drops)
        assertEquals(0L, p.catchUps)
        p.onReceived(7, arrivedAtUs = 50)
        assertEquals(FrameAction.SHOW, p.onOutput(7, 99, FrameTarget.LIVE).action)
    }

    // ---- deterministic simulation: sender, Wi-Fi, decoder ----

    private data class Scenario(
        val fps: Int,
        val seconds: Int = 8,
        val latencyUs: Long = 30_000,
        val jitterUs: Long = 4_000,
        val depth: Int = 1,
        val decodeMinUs: Long = 2_000,
        val decodeMaxUs: Long = 10_000,
        val stallAtUs: Long? = null,
        val stallUs: Long = 0,
        val shiftAtUs: Long? = null,
        val shiftUs: Long = 0,
        val seed: Long = 7,
    )

    private data class Shown(val ptsUs: Long, val atUs: Long, val action: FrameAction)

    private class Result(val pacer: FramePacer, val shown: List<Shown>, val naive: List<Shown>, val frameUs: Long)

    /** Linear congruential generator: same numbers on every machine. */
    private class Lcg(var s: Long) {
        fun next(bound: Long): Long {
            if (bound <= 0) return 0
            s = (s * 6364136223846793005L + 1442695040888963407L)
            return ((s ushr 33) % bound)
        }
    }

    private fun simulate(sc: Scenario): Result {
        val frameUs = 1_000_000L / sc.fps
        val n = sc.seconds * sc.fps
        val rnd = Lcg(sc.seed)
        val pts = LongArray(n) { it * frameUs }
        // arrival: latency, jitter, a stall that releases everything at once, a lasting shift; in order (TCP)
        val arrival = LongArray(n)
        for (i in 0 until n) {
            var a = pts[i] + sc.latencyUs + rnd.next(sc.jitterUs + 1)
            if (sc.shiftAtUs != null && pts[i] >= sc.shiftAtUs) a += sc.shiftUs
            if (sc.stallAtUs != null && a >= sc.stallAtUs && a < sc.stallAtUs + sc.stallUs) a = sc.stallAtUs + sc.stallUs
            arrival[i] = if (i == 0) a else maxOf(a, arrival[i - 1] + 100)
        }
        // decoder: FIFO, one picture at a time, hands a picture out once `depth` later frames are in
        val output = LongArray(n)
        var done = 0L
        for (i in 0 until n) {
            done = maxOf(arrival[i], done) + sc.decodeMinUs + rnd.next(sc.decodeMaxUs - sc.decodeMinUs + 1)
            val o = maxOf(done, arrival[minOf(i + sc.depth, n - 1)])
            output[i] = if (i == 0) o else maxOf(o, output[i - 1])
        }
        val pacer = FramePacer()
        val shown = mutableListOf<Shown>()
        var next = 0
        for (i in 0 until n) {
            while (next < n && arrival[next] <= output[i]) { pacer.onReceived(pts[next], arrival[next]); next++ }
            val f = pacer.onOutput(pts[i], output[i], FrameTarget.LIVE)
            if (f.renderAtUs != null) shown += Shown(pts[i], f.renderAtUs!!, f.action)
        }
        val naive = (0 until n).map { Shown(pts[it], output[it], FrameAction.SHOW) }
        return Result(pacer, shown, naive, frameUs)
    }

    /** Pairs of shown pictures that run faster than the stream (counted independently of the pacer). */
    private fun timeLapse(shown: List<Shown>): List<Pair<Shown, Shown>> =
        shown.zipWithNext().filter { (a, b) ->
            b.action != FrameAction.JUMP && b.ptsUs > a.ptsUs &&
                b.atUs - a.atUs < (b.ptsUs - a.ptsUs) - FramePacing.SLEW_US - FramePacing.PACE_TOL_US
        }

    /** Picture delay above the best seen in the run: how far the picture trails the sound. */
    private fun lagUs(shown: List<Shown>, s: Shown): Long = (s.atUs - s.ptsUs) - shown.minOf { it.atUs - it.ptsUs }

    @Test
    fun theStallReproducesTheTimeLapseOf012() {
        // without pacing (0.1.2 rendered every picture the moment it was decoded)
        for (fps in listOf(30, 60)) {
            val r = simulate(Scenario(fps = fps, stallAtUs = 2_000_000, stallUs = 500_000))
            assertTrue("fps $fps: the old behaviour must show time-lapse here, else the scenario proves nothing",
                timeLapse(r.naive).size >= 8)
        }
    }

    @Test
    fun afterAStallThePictureJumpsAndNeverRunsInTimeLapse() {
        for (fps in listOf(30, 60)) for (depth in 0..4) for (stall in listOf(300_000L, 500_000L, 1_500_000L)) {
            val sc = Scenario(fps = fps, depth = depth, stallAtUs = 2_000_000, stallUs = stall)
            val r = simulate(sc)
            val p = r.pacer
            val tag = "fps=$fps depth=$depth stall=${stall / 1000}ms"
            assertEquals("$tag: time-lapse pairs ${timeLapse(r.shown).take(3)}", 0, timeLapse(r.shown).size)
            assertEquals("$tag fastShown", 0L, p.fastShown)
            assertEquals("$tag jumps", 1L, p.jumps)
            assertEquals("$tag catch-up phases", 1L, p.catchUps)
            assertTrue("$tag drops ${p.drops}", p.drops > 0)
            // the jump goes close to live: at most 100 ms (+ a frame, + decoder depth) behind the best delay
            val jump = r.shown.first { it.action == FrameAction.JUMP }
            assertTrue("$tag lag after jump ${lagUs(r.shown, jump)}",
                lagUs(r.shown, jump) <= FramePacing.LATE_US + (depth + 2) * r.frameUs + sc.jitterUs + sc.decodeMaxUs)
            // and is fully back in sync before the end
            val last = r.shown.last()
            assertTrue("$tag lag at end ${lagUs(r.shown, last)}", lagUs(r.shown, last) < 20_000)
        }
    }

    @Test
    fun steadyStreamIsNeverDroppedAndRunsSmoothly() {
        for (fps in listOf(30, 60)) for (depth in 0..4) for (jitter in listOf(0L, 8_000L, 20_000L)) {
            val r = simulate(Scenario(fps = fps, depth = depth, jitterUs = jitter))
            val p = r.pacer
            val tag = "fps=$fps depth=$depth jitter=${jitter / 1000}ms"
            assertEquals("$tag drops", 0L, p.drops)
            assertEquals("$tag jumps", 0L, p.jumps)
            assertEquals("$tag catch-ups", 0L, p.catchUps)
            assertEquals("$tag shown", r.naive.size, r.shown.size)
            assertEquals("$tag time-lapse", 0, timeLapse(r.shown).size)
            val gap = r.shown.zipWithNext { a, b -> b.atUs - a.atUs }.max()
            assertTrue("$tag longest gap $gap", gap <= 2 * r.frameUs + jitter)
        }
    }

    @Test
    fun aLastingLatencyShiftIsNotMistakenForABacklog() {
        for (fps in listOf(30, 60)) for (depth in 0..4) {
            val r = simulate(Scenario(fps = fps, depth = depth, shiftAtUs = 2_000_000, shiftUs = 300_000))
            val tag = "fps=$fps depth=$depth"
            assertEquals("$tag drops", 0L, r.pacer.drops)
            assertEquals("$tag jumps", 0L, r.pacer.jumps)
            assertEquals("$tag time-lapse", 0, timeLapse(r.shown).size)
        }
    }

    @Test
    fun simulationIsDeterministic() {
        val a = simulate(Scenario(fps = 60, stallAtUs = 2_000_000, stallUs = 500_000))
        val b = simulate(Scenario(fps = 60, stallAtUs = 2_000_000, stallUs = 500_000))
        assertEquals(a.shown, b.shown)
        assertEquals(a.pacer.drops, b.pacer.drops)
    }

    // ---- hand-fed: what counts as a catch-up phase (T-31c, Prüfer 0.1.3 Befund 1) ----

    @Test
    fun aDecoderBacklogWithoutALateNetworkIsDroppedButNotCounted() {
        // the same lasting backlog as above, once after a network stall, once with every frame
        // on time from the network (the decoder alone fell behind): the picture does the same,
        // only the first is a catch-up phase
        val d = 16_667L
        fun run(stall: Boolean): FramePacer {
            val p = FramePacer()
            p.onReceived(0, arrivedAtUs = 0)
            p.onOutput(0, 0, FrameTarget.LIVE)
            if (stall) for (k in 1..30) p.onReceived(k * d, arrivedAtUs = 500_000)
            else for (k in 1..30) p.onReceived(k * d, arrivedAtUs = k * d)
            var now = 500_000L
            var pts = d
            while (now < 6_000_000) {
                p.onReceived(pts + 500_000, arrivedAtUs = now)
                p.onOutput(pts, now, FrameTarget.LIVE)
                now += d
                pts += d
            }
            return p
        }
        val network = run(stall = true)
        val decoder = run(stall = false)
        assertTrue("drops ${decoder.drops}", decoder.drops > 100)
        assertEquals("the picture skips the same either way", network.drops, decoder.drops)
        assertEquals(network.jumps, decoder.jumps)
        assertEquals(1L, network.catchUps)
        assertEquals(0L, decoder.catchUps)
    }

    @Test
    fun aRestartContinuesTheCatchUpPhaseItInterrupts() {
        val p = FramePacer()
        val d = 16_667L
        p.onReceived(0, arrivedAtUs = 0)
        p.onOutput(0, 0, FrameTarget.LIVE)
        for (i in 1..40) p.onReceived(i * d, arrivedAtUs = 800_000) // a stall
        assertEquals(FrameAction.DROP, p.onOutput(d, 804_000, FrameTarget.LIVE).action)
        assertEquals(1L, p.catchUps)
        p.resetOutput() // the stall overflowed the queue: the decoder restarts
        assertEquals(FrameAction.DROP, p.onOutput(2 * d, 900_000, FrameTarget.LIVE).action)
        assertEquals("one stall, one phase, restart or not", 1L, p.catchUps)
        // caught up: the next stall is a new phase
        assertEquals(FrameAction.JUMP, p.onOutput(40 * d, 901_000, FrameTarget.LIVE).action)
        for (i in 41..100) p.onReceived(i * d, arrivedAtUs = 3_000_000)
        p.onOutput(41 * d, 3_004_000, FrameTarget.LIVE)
        assertEquals(2L, p.catchUps)
    }

    @Test
    fun aParkedPictureThatCaughtUpEndsTheLateNetwork() {
        val p = FramePacer()
        val d = 16_667L
        p.onReceived(0, arrivedAtUs = 0)
        p.onOutput(0, 0, FrameTarget.LIVE)
        for (i in 1..30) p.onReceived(i * d, arrivedAtUs = 600_000) // stall while on Home
        assertEquals(FrameAction.HIDE, p.onOutput(30 * d, 601_000, FrameTarget.PARKED).action)
        // back on the display, the restarted decoder is late on its own: not the network
        p.resetOutput()
        for (i in 31..50) p.onReceived(i * d, arrivedAtUs = i * d + 90_000)
        assertEquals(FrameAction.DROP, p.onOutput(31 * d, 31 * d + 450_000, FrameTarget.LIVE).action)
        assertEquals(0L, p.catchUps)
    }

    // ---- deterministic simulation with decoder restarts (T-31c) ----

    /**
     * Like VideoRenderer: frames are stamped on arrival (native thread), queued for the decoder
     * (InputQueue with MAX_PENDING_FRAMES plus the replay allowance), decoded one at a time.
     * A restart (Home and back, app in front again, decoder overload) takes [codecStartUs] on
     * the video thread, then replays the group since the last keyframe hidden up to the newest
     * received frame, which is shown first.
     */
    private data class RestartScenario(
        val fps: Int,
        val codecStartUs: Long,
        val restartsAtUs: List<Long> = emptyList(),
        /** Home and back: before each restart the picture is parked this long. */
        val parkedUs: Long = 0,
        val stalls: List<Pair<Long, Long>> = emptyList(),
        val seconds: Int = 10,
        val latencyUs: Long = 30_000,
        val jitterUs: Long = 4_000,
        val decodeUs: Long = 4_000,
        /** Decoder overload: decoding takes [slowDecodeUs] between these times. */
        val slowFromUs: Long = 0,
        val slowToUs: Long = 0,
        val slowDecodeUs: Long = 0,
        /** Counter-check only: take the arrival time on the video thread, as a renderer would
         *  that stamps in handleFrame (behind a starting decoder). */
        val stampOnVideoThread: Boolean = false,
        val seed: Long = 11,
    )

    private class RestartResult(val pacer: FramePacer, val restarts: Int, val overflows: Int, val catchUpEvents: List<Long>)

    private fun simulateRestarts(sc: RestartScenario): RestartResult {
        val frameUs = 1_000_000L / sc.fps
        val n = sc.seconds * sc.fps
        val gop = 2 * sc.fps // a keyframe every 2 s
        val rnd = Lcg(sc.seed)
        val pts = LongArray(n) { it * frameUs }
        val arrival = LongArray(n)
        for (i in 0 until n) {
            var a = pts[i] + sc.latencyUs + rnd.next(sc.jitterUs + 1)
            for ((at, len) in sc.stalls) if (a >= at && a < at + len) a = at + len
            arrival[i] = if (i == 0) a else maxOf(a, arrival[i - 1] + 100)
        }
        val pacer = FramePacer()
        val queue = ArrayDeque<Pair<Int, Long>>() // frame, time it was queued
        var allowance = 0
        var decoderFreeUs = 0L
        var blockedUntilUs = 0L
        var renderFrom = Long.MIN_VALUE
        var onDisplay = true
        var restarts = 0
        var overflows = 0
        var seen = 0L
        val events = mutableListOf<Long>()
        val planned = sc.restartsAtUs.sorted().toMutableList()
        var received = 0
        fun restart(atUs: Long) {
            val latest = received - 1
            val key = latest / gop * gop
            queue.clear()
            blockedUntilUs = atUs + sc.codecStartUs
            for (k in key..latest) queue.addLast(k to blockedUntilUs)
            allowance = latest - key + 1
            decoderFreeUs = blockedUntilUs
            renderFrom = pts[latest]
            onDisplay = true
            pacer.resetOutput()
            restarts++
        }
        fun decodeUs(atUs: Long) = if (atUs >= sc.slowFromUs && atUs < sc.slowToUs) sc.slowDecodeUs else sc.decodeUs
        val never = Long.MAX_VALUE
        while (true) {
            val tArr = if (received < n) maxOf(arrival[received], blockedUntilUs) else never
            val head = queue.firstOrNull()
            val tOut = if (head == null) never else maxOf(decoderFreeUs, head.second).let { it + decodeUs(it) }
            val tRes = planned.firstOrNull() ?: never
            val tPark = if (sc.parkedUs > 0 && onDisplay && tRes != never) tRes - sc.parkedUs else never
            val t = minOf(minOf(tArr, tOut), minOf(tRes, tPark))
            if (t == never) break
            when (t) {
                tArr -> {
                    val i = received++
                    pacer.onReceived(pts[i], if (sc.stampOnVideoThread) t else arrival[i])
                    if (queue.size >= ReceiverConfig.MAX_PENDING_FRAMES + allowance) {
                        overflows++
                        restart(t)
                    } else {
                        queue.addLast(i to t)
                    }
                }
                tPark -> onDisplay = false
                tRes -> {
                    planned.removeAt(0)
                    restart(t)
                }
                else -> {
                    val (i, _) = queue.removeFirst()
                    if (allowance > 0) allowance--
                    decoderFreeUs = t
                    pacer.onOutput(pts[i], t, frameTarget(onDisplay, pts[i], renderFrom))
                    if (pacer.catchUps != seen) {
                        seen = pacer.catchUps
                        events += seen
                    }
                }
            }
        }
        return RestartResult(pacer, restarts, overflows, events)
    }

    /** Restarts in the middle of a group, so the restarted decoder replays a good part of it. */
    private val midGroupRestarts = listOf(listOf(3_300_000L), listOf(3_300_000L, 7_500_000L))

    @Test
    fun aRestartOnAPerfectNetworkIsNoCatchUpPhase() {
        var checked = 0
        for (fps in listOf(30, 60)) for (start in listOf(80_000L, 300_000L)) for (at in midGroupRestarts)
            for (parked in listOf(0L, 2_000_000L)) {
                val r = simulateRestarts(RestartScenario(fps = fps, codecStartUs = start, restartsAtUs = at, parkedUs = parked))
                val tag = "fps=$fps codecStart=${start / 1000}ms restarts=${at.size} parked=${parked / 1000}ms"
                assertEquals("$tag restarts", at.size, r.restarts)
                assertTrue("$tag: the restart must leave the picture behind, else this proves nothing (drops ${r.pacer.drops})", r.pacer.drops > 0)
                assertTrue("$tag: and the picture still jumps (jumps ${r.pacer.jumps})", r.pacer.jumps >= 1)
                assertEquals("$tag catch-up phases", 0L, r.pacer.catchUps)
                checked++
            }
        assertEquals(16, checked)
    }

    @Test
    fun aRestartStampedOnTheVideoThreadWouldLookLikeALateNetwork() {
        // why the arrival is taken on the native thread: behind a 300 ms codec start the frames
        // that waited for the video thread look 300 ms late
        val r = simulateRestarts(RestartScenario(fps = 60, codecStartUs = 300_000, restartsAtUs = listOf(3_300_000L), stampOnVideoThread = true))
        assertEquals(1L, r.pacer.catchUps)
    }

    @Test
    fun aRealStallAfterARestartStillCounts() {
        var checked = 0
        for (fps in listOf(30, 60)) for (start in listOf(80_000L, 300_000L)) for (stall in listOf(500_000L, 1_500_000L)) {
            val r = simulateRestarts(RestartScenario(fps = fps, codecStartUs = start, restartsAtUs = listOf(3_300_000L),
                stalls = listOf(5_500_000L to stall)))
            val tag = "fps=$fps codecStart=${start / 1000}ms stall=${stall / 1000}ms"
            assertEquals("$tag one stall, one phase", listOf(1L), r.catchUpEvents)
            checked++
        }
        assertEquals(8, checked)
    }

    @Test
    fun aStallThatOverflowsTheQueueCountsOnceDespiteItsRestart() {
        for (fps in listOf(30, 60)) for (start in listOf(80_000L, 300_000L)) {
            val r = simulateRestarts(RestartScenario(fps = fps, codecStartUs = start, stalls = listOf(5_000_000L to 2_500_000L)))
            val tag = "fps=$fps codecStart=${start / 1000}ms"
            assertTrue("$tag: the stall must overflow the queue, else this proves nothing", r.overflows >= 1)
            assertEquals("$tag", 1L, r.pacer.catchUps)
        }
    }

    @Test
    fun decoderOverloadWithItsRestartIsNoCatchUpPhase() {
        for (fps in listOf(30, 60)) for (start in listOf(80_000L, 300_000L)) {
            // decoding takes two and a half frame times: the queue grows by 60 % of the frame rate
            val r = simulateRestarts(RestartScenario(fps = fps, codecStartUs = start,
                slowFromUs = 2_500_000, slowToUs = 6_500_000, slowDecodeUs = 5 * 1_000_000L / (2 * fps)))
            val tag = "fps=$fps codecStart=${start / 1000}ms"
            assertTrue("$tag: the overload must overflow the queue, else this proves nothing", r.overflows >= 1)
            assertTrue("$tag drops ${r.pacer.drops}", r.pacer.drops > 0)
            assertEquals("$tag", 0L, r.pacer.catchUps)
        }
    }

    // ---- the whole chain on 2.4 GHz: pacer, counter per network, advice, hint (T-31c) ----

    /** What ReceiverService does: announce when mirroring starts, then one reading per phase. */
    private fun hintAfter(events: List<Long>): ReceiverState {
        val key = NetworkKey(networkHandle = 100L, band = bandClass(2437))
        val rssi = rssiClass(-55)
        val onNetwork = NetworkCatchUps()
        var state = ReceiverState().withWifiAdvice(wifiAdvice(rssi, key.band, catchUpClass(onNetwork.countOn(key))), announce = true)
        for (e in events) state = state.withWifiAdvice(wifiAdvice(rssi, key.band, catchUpClass(onNetwork.add(key))), announce = false)
        return state
    }

    @Test
    fun twoRestartsOnTwoPointFourGigahertzShowNoHint() {
        for (fps in listOf(30, 60)) for (start in listOf(80_000L, 300_000L)) {
            val r = simulateRestarts(RestartScenario(fps = fps, codecStartUs = start,
                restartsAtUs = listOf(3_300_000L, 7_500_000L), parkedUs = 2_000_000))
            val tag = "fps=$fps codecStart=${start / 1000}ms"
            assertEquals("$tag restarts", 2, r.restarts)
            val state = hintAfter(r.catchUpEvents)
            assertEquals("$tag advice", WifiAdvice.NONE, state.wifiAdvice)
            assertEquals("$tag hint never raised", 0, state.wifiHintSeq)
        }
    }

    @Test
    fun twoRealStallsOnTwoPointFourGigahertzStillShowTheHint() {
        // the contrast: the same chain does warn when the network really stalls twice
        val r = simulateRestarts(RestartScenario(fps = 60, codecStartUs = 300_000,
            stalls = listOf(2_500_000L to 500_000L, 6_500_000L to 500_000L)))
        assertEquals(listOf(1L, 2L), r.catchUpEvents)
        val state = hintAfter(r.catchUpEvents)
        assertEquals(WifiAdvice.BUSY_24GHZ, state.wifiAdvice)
        assertEquals(1, state.wifiHintSeq)
    }
}
