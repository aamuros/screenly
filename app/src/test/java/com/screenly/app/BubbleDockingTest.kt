package com.screenly.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleDockingTest {
    @Test fun allFourCorners() {
        val leftTop = BubbleDocking.nearestCorner(45, 80, 20, 50, 420, 850)
        val rightTop = BubbleDocking.nearestCorner(400, 80, 20, 50, 420, 850)
        val leftBottom = BubbleDocking.nearestCorner(45, 800, 20, 50, 420, 850)
        val rightBottom = BubbleDocking.nearestCorner(400, 800, 20, 50, 420, 850)
        assertEquals(BubbleCorner(false, false), leftTop)
        assertEquals(BubbleCorner(true, false), rightTop)
        assertEquals(BubbleCorner(false, true), leftBottom)
        assertEquals(BubbleCorner(true, true), rightBottom)
        assertEquals(Pair(32, 62), BubbleDocking.position(20, 50, 420, 850, 72, 12, leftTop))
        assertEquals(Pair(336, 766), BubbleDocking.position(20, 50, 420, 850, 72, 12, rightBottom))
    }

    @Test fun crampedScreenDoesNotCrash() {
        assertEquals(Pair(12, 12),
            BubbleDocking.position(0, 0, 40, 40, 72, 12, BubbleCorner(true, true)))
    }

    @Test fun exactCenterChoosesRightBottom() {
        assertEquals(BubbleCorner(true, true),
            BubbleDocking.nearestCorner(200, 400, 0, 0, 400, 800))
    }
}
