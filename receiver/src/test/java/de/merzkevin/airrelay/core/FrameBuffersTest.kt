package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBuffersTest {

    private fun nal(type: Int, startCode4: Boolean = true, payload: Int = 8): ByteArray {
        val sc = if (startCode4) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1)
        return sc + byteArrayOf((0x60 or type).toByte()) + ByteArray(payload) { 0x55 }
    }

    private fun key(size: Int = 100, pts: Long = 0) = Frame(ByteArray(size), pts, true)
    private fun delta(size: Int = 100, pts: Long = 0) = Frame(ByteArray(size), pts, false)

    @Test fun spsOrIdrMakesAKeyframe() {
        assertTrue(isH264Keyframe(nal(7) + nal(8) + nal(5)))
        assertTrue(isH264Keyframe(nal(5)))
        assertTrue(isH264Keyframe(nal(7, startCode4 = false)))
        assertTrue(isH264Keyframe(nal(9) + nal(5, startCode4 = false)))
    }

    @Test fun otherNalTypesAreNotKeyframes() {
        assertFalse(isH264Keyframe(nal(1)))
        assertFalse(isH264Keyframe(nal(6) + nal(1)))
        assertFalse(isH264Keyframe(nal(8)))
        assertFalse(isH264Keyframe(ByteArray(0)))
        assertFalse(isH264Keyframe(byteArrayOf(0, 0, 0, 1)))
        // 0x65 inside a payload without a start code in front must not count
        assertFalse(isH264Keyframe(nal(1) + byteArrayOf(0x65, 0x67)))
    }

    @Test fun startCodeAtTheVeryEndIsHandled() {
        assertFalse(isH264Keyframe(nal(1) + byteArrayOf(0, 0, 1)))
        assertTrue(isH264Keyframe(nal(1) + byteArrayOf(0, 0, 1, 0x65)))
    }

    @Test fun gopStartsOnlyAtAKeyframe() {
        val g = GopBuffer(1000)
        assertFalse(g.add(delta()))
        assertFalse(g.hasKeyframe)
        assertTrue(g.add(key()))
        assertTrue(g.add(delta()))
        assertEquals(2, g.size)
        assertEquals(200, g.bytes)
    }

    @Test fun newKeyframeReplacesTheGroup() {
        val g = GopBuffer(1000)
        g.add(key(pts = 1)); g.add(delta(pts = 2)); g.add(delta(pts = 3))
        g.add(key(pts = 4))
        assertEquals(listOf(4L), g.snapshot().map { it.ptsUs })
        assertEquals(100, g.bytes)
    }

    @Test fun overflowEmptiesAndWaitsForTheNextKeyframe() {
        val g = GopBuffer(250)
        g.add(key()); g.add(delta())
        assertFalse(g.add(delta()))
        assertTrue(g.overflowed)
        assertFalse(g.hasKeyframe)
        assertFalse(g.add(delta()))
        assertTrue(g.add(key()))
        assertFalse(g.overflowed)
    }

    @Test fun keyframeLargerThanTheLimitIsNotKept() {
        val g = GopBuffer(50)
        assertFalse(g.add(key(size = 51)))
        assertFalse(g.hasKeyframe)
        assertTrue(g.overflowed)
        assertFalse(g.add(delta(size = 1)))
    }

    @Test fun groupExactlyAtTheLimitIsKept() {
        val g = GopBuffer(200)
        assertTrue(g.add(key())); assertTrue(g.add(delta()))
        assertEquals(200, g.bytes)
    }

    @Test fun snapshotIsACopy() {
        val g = GopBuffer(1000)
        g.add(key())
        val s = g.snapshot()
        g.add(delta())
        assertEquals(1, s.size)
        g.clear()
        assertEquals(0, g.bytes)
        assertFalse(g.hasKeyframe)
    }

    @Test fun queueReportsOverloadAndClears() {
        val q = InputQueue(3)
        repeat(3) { assertTrue(q.offer(delta())) }
        assertFalse(q.offer(delta()))
        assertEquals(0, q.size)
        assertTrue(q.offer(delta()))
    }

    @Test fun replayedGroupLongerThanTheLimitIsKeptWhole() {
        val q = InputQueue(3)
        val group = listOf(key(pts = 0)) + (1..9L).map { delta(pts = it) }
        q.replaceWith(group)
        assertEquals(10, q.size)
        assertEquals(0L, q.poll()!!.ptsUs)
        // live frames on top of the draining replay are fine up to the normal limit
        assertTrue(q.offer(delta(pts = 10)))
    }

    @Test fun replayAllowanceShrinksWhileTheReplayDrains() {
        val q = InputQueue(2)
        q.replaceWith(List(4) { delta(pts = it.toLong()) })
        repeat(4) { q.poll() }
        assertTrue(q.offer(delta())); assertTrue(q.offer(delta()))
        assertFalse("allowance must be used up once the replay drained", q.offer(delta()))
    }

    @Test fun pollOnEmptyQueueIsNull() {
        val q = InputQueue(2)
        assertNull(q.poll())
        assertTrue(q.isEmpty())
    }
}
