package de.merzkevin.spiegel.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGeometryTest {
    private val tv = Size(1920, 1080)

    @Test fun landscapeStreamFillsTheTv() {
        assertEquals(Rect(0, 0, 1920, 1080), fitCentered(Size(1920, 1080), tv))
        assertEquals(Rect(0, 0, 1920, 1080), fitCentered(Size(1280, 720), tv))
    }

    @Test fun portraitPhoneGetsBlackBarsLeftAndRight() {
        // iPhone portrait stream, e.g. 886x1920 scaled into 1080 height
        val r = fitCentered(Size(886, 1920), tv)
        assertEquals(1080, r.height)
        assertEquals(498, r.width)
        assertEquals((1920 - 498) / 2, r.left)
        assertEquals(0, r.top)
    }

    @Test fun widerThanTvGetsBarsTopAndBottom() {
        val r = fitCentered(Size(2400, 1080), tv) // 20:9 phone in landscape
        assertEquals(Rect(0, 108, 1920, 864), r)
    }

    @Test fun fourToThreeIpadIsPillarboxed() {
        assertEquals(Rect(240, 0, 1440, 1080), fitCentered(Size(2048, 1536), tv))
    }

    @Test fun unknownContentFillsAndEmptyContainerGivesNothing() {
        assertEquals(Rect(0, 0, 1920, 1080), fitCentered(Size(0, 0), tv))
        assertEquals(Rect(0, 0, 0, 0), fitCentered(Size(1920, 1080), Size(0, 1080)))
    }

    @Test fun resultNeverExceedsTheContainer() {
        for (w in listOf(1, 3, 480, 886, 1179, 1920, 2556)) for (h in listOf(1, 7, 720, 1080, 1920, 2556)) {
            val r = fitCentered(Size(w, h), tv)
            assert(r.left >= 0 && r.top >= 0 && r.left + r.width <= 1920 && r.top + r.height <= 1080) { "$w x $h -> $r" }
            assert(r.width == 1920 || r.height == 1080) { "$w x $h does not touch two edges: $r" }
        }
    }

    @Test fun advertisedSizeIsCappedAt1080p() {
        assertEquals(Size(1920, 1080), advertisedSize(Size(1920, 1080), Size(1920, 1088)))
        assertEquals(Size(1920, 1080), advertisedSize(Size(3840, 2160), null))
        assertEquals(Size(1280, 720), advertisedSize(Size(1280, 720), Size(4096, 2160)))
    }

    @Test fun advertisedSizeRespectsASmallerDecoder() {
        assertEquals(Size(1280, 720), advertisedSize(Size(1920, 1080), Size(1280, 720)))
    }

    @Test fun advertisedSizeIsAlwaysLandscapeAndHasADefault() {
        assertEquals(Size(1920, 1080), advertisedSize(Size(1080, 1920), null))
        assertEquals(Size(1920, 1080), advertisedSize(Size(0, 0), Size(0, 0)))
    }

    @Test fun visibleSizeUsesTheCropRectangle() {
        assertEquals(Size(1920, 1080), visibleSize(1920, 1088, 0, 0, 1919, 1079))
        assertEquals(Size(886, 1920), visibleSize(896, 1920, 0, 0, 885, 1919))
    }

    @Test fun visibleSizeWithoutOrWithBrokenCropUsesWidthAndHeight() {
        assertEquals(Size(1280, 720), visibleSize(1280, 720, -1, -1, -1, -1))
        assertEquals(Size(1280, 720), visibleSize(1280, 720, 10, 0, 5, 719))
    }
}
