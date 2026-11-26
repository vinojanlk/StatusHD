package com.techwin.statushd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhotoProcessorSeamTest {

    @Test
    fun testPhotoDestinationPaths() {
        assertEquals("Pictures/StatusHD", PhotoDestination.Single.relativePath)
        assertEquals("Pictures/StatusHD/Batch", PhotoDestination.Batch.relativePath)
    }

    @Test
    fun testPhotoQualityModes() {
        assertEquals(90, PhotoQualityMode.STATUS_HD.quality)
        assertEquals(80, PhotoQualityMode.SMALLER_FILE.quality)
    }

    @Test
    fun testTypedFailureMappingThroughProductionMapper() {
        assertEquals(
            "This version currently supports JPEG photos only.",
            PhotoProcessingUiMapper.mapFailureToUserMessage(PhotoProcessingFailure.UnsupportedFormat)
        )
        assertEquals(
            "Image dimensions exceed supported limit.",
            PhotoProcessingUiMapper.mapFailureToUserMessage(PhotoProcessingFailure.SourceTooLarge)
        )
        assertEquals(
            "Source file unavailable or unreadable.",
            PhotoProcessingUiMapper.mapFailureToUserMessage(PhotoProcessingFailure.SourceUnavailable)
        )
        assertEquals(
            "Not enough memory to process image.",
            PhotoProcessingUiMapper.mapFailureToUserMessage(PhotoProcessingFailure.OutOfMemory)
        )
        assertEquals(
            "Failed to save photo to MediaStore.",
            PhotoProcessingUiMapper.mapFailureToUserMessage(PhotoProcessingFailure.SaveFailed)
        )
    }

    @Test
    fun testProcessingFailedNeverExposesRawExceptionText() {
        val rawExceptionText = "java.lang.NullPointerException: Internal decoder buffer failed at line 142"
        val mappedMessage = PhotoProcessingUiMapper.mapFailureToUserMessage(
            PhotoProcessingFailure.ProcessingFailed(rawExceptionText)
        )

        assertEquals("Photo processing failed.", mappedMessage)
        assertFalse(mappedMessage.contains(rawExceptionText))
        assertFalse(mappedMessage.contains("NullPointerException"))
    }

    @Test
    fun testCanceledNotShownAsRawTechnicalError() {
        val mappedMessage = PhotoProcessingUiMapper.mapFailureToUserMessage(PhotoProcessingFailure.Canceled)

        assertEquals("Photo processing failed.", mappedMessage)
        assertFalse(mappedMessage.contains("CancellationException"))
    }
}
