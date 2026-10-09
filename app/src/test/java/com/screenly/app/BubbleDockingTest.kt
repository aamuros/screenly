package com.screenly.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleDockingTest {
    @Test fun snapsOnlyHorizontally() {
        assertEquals(false, BubbleDocking.nearestSide(90, 0, 400))
        assertEquals(true, BubbleDocking.nearestSide(380, 0, 400))
        assertEquals(true, BubbleDocking.nearestSide(200, 0, 400))
        assertEquals(
            Pair(32, 397),
            BubbleDocking.position(20, 50, 420, 850, 72, 72, 12, false, 397)
        )
        assertEquals(
            Pair(336, 397),
            BubbleDocking.position(20, 50, 420, 850, 72, 72, 12, true, 397)
        )
    }

    @Test fun clampsVerticalBoundsWithoutForcingCorners() {
        assertEquals(
            Pair(336, 62),
            BubbleDocking.position(20, 50, 420, 850, 72, 72, 12, true, -30)
        )
        assertEquals(
            Pair(32, 766),
            BubbleDocking.position(20, 50, 420, 850, 72, 72, 12, false, 900)
        )
        assertEquals(
            Pair(0, 0),
            BubbleDocking.position(0, 0, 40, 40, 40, 40, 12, true, 20)
        )
    }

    @Test fun menuTracksBubbleHeightWithoutLeavingScreen() {
        assertEquals(
            Pair(188, 334),
            BubbleDocking.panelPosition(20, 50, 420, 850, 220, 198, 12, true, 397, 72)
        )
        assertEquals(
            Pair(32, 62),
            BubbleDocking.panelPosition(20, 50, 420, 850, 220, 198, 12, false, 62, 72)
        )
        assertEquals(
            Pair(188, 640),
            BubbleDocking.panelPosition(20, 50, 420, 850, 220, 198, 12, true, 766, 72)
        )
    }
}
