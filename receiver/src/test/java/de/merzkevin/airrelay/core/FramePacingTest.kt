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
        p.onReceived(0)
        // a replayed picture 5 s "late" must not become the reference
        assertEquals(FrameAction.HIDE, p.onOutput(ptsUs = -5_000_000, nowUs = 0, target = FrameTarget.REPLAY).action)
        p.onReceived(16_667)
        val f = p.onOutput(ptsUs = 16_667, nowUs = 16_667, target = FrameTarget.LIVE)
        assertEquals(FrameAction.SHOW, f.action)
        assertEquals(16_667L, f.renderAtUs)
        assertEquals(0L, p.drops)
    }

    @Test
    fun parkedPicturesAreHiddenButDefineTheBaseline() {
        val p = FramePacer()
        p.onReceived(0)
        assertEquals(FrameAction.HIDE, p.onOutput(0, 10_000, FrameTarget.PARKED).action)
        // display appears; the burst arrives 400 ms late with 400 ms of newer picture
        for (i in 1..30) p.onReceived(i * 16_667L)
        val f = p.onOutput(16_667, 430_000, FrameTarget.LIVE)
        assertEquals(FrameAction.DROP, f.action)
        assertNull(f.renderAtUs)
        assertEquals(1L, p.catchUps)
    }

    @Test
    fun aBurstIsDroppedThenJumpedOverAndCountedAsOnePhase() {
        val p = FramePacer()
        val d = 16_667L
        p.onReceived(0)
        assertEquals(FrameAction.SHOW, p.onOutput(0, 0, FrameTarget.LIVE).action)
        for (i in 1..40) p.onReceived(i * d) // 40 frames arrive at once after a stall
        val now = 600_000L
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
        p.onReceived(0)
        p.onOutput(0, 0, FrameTarget.LIVE)
        for (i in 1..4) p.onReceived(i * d) // 67 ms burst: below the drop threshold
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
        p.onReceived(0)
        p.onOutput(0, 0, FrameTarget.LIVE)
        p.onReceived(5_000_000) // sender clock jumped 5 s ahead
        val f = p.onOutput(5_000_000, 20_000, FrameTarget.LIVE)
        assertEquals(FrameAction.PACE, f.action)
        assertEquals(20_000L + FramePacing.PACE_MAX_US, f.renderAtUs)
    }

    @Test
    fun aBackwardClockJumpOfTheSenderIsShownNotDropped() {
        val p = FramePacer()
        val d = 16_667L
        for (i in 0..5) { p.onReceived(10_000_000 + i * d); p.onOutput(10_000_000 + i * d, i * d, FrameTarget.LIVE) }
        // new stream starts at 0: looks 10 s late against the old baseline, but nothing newer is buffered
        p.onReceived(0)
        assertEquals(FrameAction.SHOW, p.onOutput(0, 6 * d, FrameTarget.LIVE).action)
        assertEquals(0L, p.drops)
    }

    @Test
    fun aLastingBacklogShowsOnePicturePerSecondThenBecomesTheNewNormal() {
        val p = FramePacer()
        val d = 16_667L
        p.onReceived(0)
        p.onOutput(0, 0, FrameTarget.LIVE)
        // from here on every picture comes out 500 ms late with 500 ms of newer picture buffered
        val shownAt = mutableListOf(0L)
        var dropsAfterSpan = 0
        var now = 500_000L
        var pts = d
        while (now < 6_000_000) {
            p.onReceived(pts + 500_000)
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
        p.onReceived(0)
        p.onOutput(0, 0, FrameTarget.LIVE)
        for (i in 1..40) p.onReceived(i * d)
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
        p.onReceived(7)
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
            while (next < n && arrival[next] <= output[i]) pacer.onReceived(pts[next++])
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
}
