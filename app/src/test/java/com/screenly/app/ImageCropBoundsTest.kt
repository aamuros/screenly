package com.screenly.app

import org.junit.Assert.*
import org.junit.Test

class ImageCropBoundsTest {
    @Test fun excludesActualEdgeBoundsWithoutIncludingAnyOccludedPixels() {
        val crop = ImageCropBounds(0, 108, 1080, 2460)
        assertEquals(crop.copy(right = 1041), crop.excluding(ImageCropBounds(1041, 561, 1080, 945)))
        assertEquals(crop, crop.excluding(ImageCropBounds(0, 0, 1080, 108)))
        assertEquals(crop.copy(top = 300), crop.excluding(ImageCropBounds(0, 100, 1080, 300)))
        assertEquals(crop.copy(bottom = 2200), crop.excluding(ImageCropBounds(0, 2200, 1080, 2460)))
    }

    @Test fun rejectsInteriorAndFullScreenOcclusionsAndNeverExpandsTheCrop() {
        val crop = ImageCropBounds(0, 100, 1080, 2400)
        assertNull(crop.excluding(ImageCropBounds(200, 300, 600, 700)))
        assertNull(crop.excluding(ImageCropBounds(0, 0, 1080, 2460)))
        val result = crop.excluding(ImageCropBounds(0, 0, 100, 200))!!
        assertTrue(result.left >= crop.left && result.top >= crop.top && result.right <= crop.right && result.bottom <= crop.bottom)
        assertTrue(result.left >= 100 || result.top >= 200)
    }
}
