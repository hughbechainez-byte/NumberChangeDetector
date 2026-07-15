package com.example.compilationmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeVisionImageTest {
    private val screenshotRoi = ScanWindow(0.0296f, 0.7618f, 0.10f, 0.1815f)

    @Test fun normalFrameRoiProducesValidCrop() {
        val rect = SafeVisionImage.computeSafeCropRect(1920, 1080, screenshotRoi)
        assertNotNull(rect)
        assertTrue(rect!!.width >= MIN_INPUT_IMAGE_DIMENSION)
        assertTrue(rect.height >= MIN_INPUT_IMAGE_DIMENSION)
        assertFalse(rect.usedFullFrameFallback)
    }

    @Test fun smallDownscaledFrameRoiExpandsToAtLeastThirtyTwo() {
        val rect = SafeVisionImage.computeSafeCropRect(160, 90, screenshotRoi)
        assertNotNull(rect)
        assertTrue(rect!!.width >= MIN_INPUT_IMAGE_DIMENSION)
        assertTrue(rect.height >= MIN_INPUT_IMAGE_DIMENSION)
        assertTrue(rect.left >= 0)
        assertTrue(rect.top >= 0)
        assertTrue(rect.right <= 160)
        assertTrue(rect.bottom <= 90)
    }

    @Test fun roiNearEveryEdgeStaysInBounds() {
        val windows = listOf(
            ScanWindow(0f, 0f, 0.02f, 0.02f),
            ScanWindow(0.98f, 0f, 0.02f, 0.02f),
            ScanWindow(0f, 0.98f, 0.02f, 0.02f),
            ScanWindow(0.98f, 0.98f, 0.02f, 0.02f)
        )
        windows.forEach { window ->
            val rect = SafeVisionImage.computeSafeCropRect(160, 90, window)
            assertNotNull(rect)
            assertTrue(rect!!.isValidForInputImage)
            assertTrue(rect.left >= 0 && rect.top >= 0)
            assertTrue(rect.right <= 160 && rect.bottom <= 90)
        }
    }

    @Test fun cropThatRoundsToThirtyOneBecomesThirtyTwo() {
        val rect = SafeVisionImage.computeSafeCropRect(100, 100, ScanWindow(0.1f, 0.1f, 0.31f, 0.31f))
        assertNotNull(rect)
        assertEquals(32, rect!!.width)
        assertEquals(32, rect.height)
    }

    @Test fun invalidFrameDimensionsReturnNull() {
        assertNull(SafeVisionImage.computeSafeCropRect(0, 90, screenshotRoi))
        assertNull(SafeVisionImage.computeSafeCropRect(160, -1, screenshotRoi))
        assertNull(SafeVisionImage.computeSafeCropRect(31, 90, screenshotRoi))
        assertNull(SafeVisionImage.computeSafeCropRect(160, 31, screenshotRoi))
    }

    @Test fun invalidCandidateCanBeSkippedAndAllInvalidShowsFriendlyMessage() {
        val candidates = listOf(0 to 0, 31 to 120, 160 to 90)
        val valid = candidates.mapNotNull { (w, h) -> SafeVisionImage.computeSafeCropRect(w, h, screenshotRoi) }
        assertEquals(1, valid.size)
        assertTrue(valid.single().isValidForInputImage)

        val allInvalid = listOf(0 to 0, 31 to 120).mapNotNull { (w, h) -> SafeVisionImage.computeSafeCropRect(w, h, screenshotRoi) }
        assertTrue(allInvalid.isEmpty())
        assertEquals(
            "No valid frames found in the selected ROI. Try increasing the ROI size or using higher quality.",
            SafeVisionImage.userFacingNoValidFramesMessage()
        )
    }
}
