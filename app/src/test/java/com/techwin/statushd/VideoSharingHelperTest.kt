package com.techwin.statushd

import org.junit.Assert.*
import org.junit.Test

class VideoSharingHelperTest {

    // 1. Video expected-part count tests
    @Test
    fun testExpectedPartCountZeroSeconds() {
        assertEquals(0, VideoSharingHelper.calculatePartCount(0.0, 30))
        assertEquals(0, VideoSharingHelper.calculatePartCount(0.0, 60))
        assertEquals(0, VideoSharingHelper.calculatePartCount(0L, 30))
        assertEquals(0, VideoSharingHelper.calculatePartCount(0L, 60))
    }

    @Test
    fun testExpectedPartCountOneSecond() {
        assertEquals(1, VideoSharingHelper.calculatePartCount(1.0, 30))
        assertEquals(1, VideoSharingHelper.calculatePartCount(1.0, 60))
        assertEquals(1, VideoSharingHelper.calculatePartCount(1000L, 30))
        assertEquals(1, VideoSharingHelper.calculatePartCount(1000L, 60))
    }

    @Test
    fun testExpectedPartCountThirtySeconds() {
        assertEquals(1, VideoSharingHelper.calculatePartCount(30.0, 30))
        assertEquals(1, VideoSharingHelper.calculatePartCount(30.0, 60))
        assertEquals(1, VideoSharingHelper.calculatePartCount(30000L, 30))
        assertEquals(1, VideoSharingHelper.calculatePartCount(30000L, 60))
    }

    @Test
    fun testExpectedPartCountThirtyOneSeconds() {
        assertEquals(2, VideoSharingHelper.calculatePartCount(31.0, 30))
        assertEquals(1, VideoSharingHelper.calculatePartCount(31.0, 60))
        assertEquals(2, VideoSharingHelper.calculatePartCount(31000L, 30))
        assertEquals(1, VideoSharingHelper.calculatePartCount(31000L, 60))
    }

    @Test
    fun testExpectedPartCountSixtySeconds() {
        assertEquals(2, VideoSharingHelper.calculatePartCount(60.0, 30))
        assertEquals(1, VideoSharingHelper.calculatePartCount(60.0, 60))
        assertEquals(2, VideoSharingHelper.calculatePartCount(60000L, 30))
        assertEquals(1, VideoSharingHelper.calculatePartCount(60000L, 60))
    }

    @Test
    fun testExpectedPartCountSixtyOneSeconds() {
        assertEquals(3, VideoSharingHelper.calculatePartCount(61.0, 30))
        assertEquals(2, VideoSharingHelper.calculatePartCount(61.0, 60))
        assertEquals(3, VideoSharingHelper.calculatePartCount(61000L, 30))
        assertEquals(2, VideoSharingHelper.calculatePartCount(61000L, 60))
    }

    @Test
    fun testExpectedPartCountEightySixSeconds() {
        // Full HD Detail: 86s / 30s split -> ceil(2.866) = 3 parts
        assertEquals(3, VideoSharingHelper.calculatePartCount(86.0, 30))
        assertEquals(3, VideoSharingHelper.calculatePartCount(86000L, 30))

        // HD Longer: 86s / 60s split -> ceil(1.433) = 2 parts
        assertEquals(2, VideoSharingHelper.calculatePartCount(86.0, 60))
        assertEquals(2, VideoSharingHelper.calculatePartCount(86000L, 60))
    }

    @Test
    fun testExpectedPartCountExactMultipleOfThirty() {
        // 90 seconds is exact multiple of 30 -> 3 parts
        assertEquals(3, VideoSharingHelper.calculatePartCount(90.0, 30))
        assertEquals(3, VideoSharingHelper.calculatePartCount(90000L, 30))

        // 120 seconds is exact multiple of 30 -> 4 parts
        assertEquals(4, VideoSharingHelper.calculatePartCount(120.0, 30))
        assertEquals(4, VideoSharingHelper.calculatePartCount(120000L, 30))
    }

    @Test
    fun testExpectedPartCountExactMultipleOfSixty() {
        // 120 seconds is exact multiple of 60 -> 2 parts
        assertEquals(2, VideoSharingHelper.calculatePartCount(120.0, 60))
        assertEquals(2, VideoSharingHelper.calculatePartCount(120000L, 60))

        // 180 seconds is exact multiple of 60 -> 3 parts
        assertEquals(3, VideoSharingHelper.calculatePartCount(180.0, 60))
        assertEquals(3, VideoSharingHelper.calculatePartCount(180000L, 60))
    }

    // 2. One output selects ACTION_SEND
    @Test
    fun testOneOutputSelectsActionSend() {
        val outputs = listOf("content://media/external/video/media/101")
        val spec = VideoSharingHelper.createShareSpec(outputs)
        assertNotNull(spec)
        assertEquals(ShareIntentAction.SEND, spec!!.action)
        assertEquals(VideoSharingHelper.MIME_TYPE_VIDEO, spec.mimeType)
        assertEquals(1, spec.streamUris.size)
        assertEquals(outputs.first(), spec.streamUris.first())
    }

    // 3. Two or more outputs select ACTION_SEND_MULTIPLE
    @Test
    fun testTwoOrMoreOutputsSelectActionSendMultiple() {
        val outputs2 = listOf(
            "content://media/external/video/media/101",
            "content://media/external/video/media/102"
        )
        val spec2 = VideoSharingHelper.createShareSpec(outputs2)
        assertNotNull(spec2)
        assertEquals(ShareIntentAction.SEND_MULTIPLE, spec2!!.action)

        val outputs3 = listOf(
            "content://media/external/video/media/101",
            "content://media/external/video/media/102",
            "content://media/external/video/media/103"
        )
        val spec3 = VideoSharingHelper.createShareSpec(outputs3)
        assertNotNull(spec3)
        assertEquals(ShareIntentAction.SEND_MULTIPLE, spec3!!.action)
    }

    // 4. Shared URI order preserved
    @Test
    fun testSharedUriOrderPreserved() {
        val outputs = listOf(
            "content://media/external/video/media/part_3",
            "content://media/external/video/media/part_1",
            "content://media/external/video/media/part_2"
        )
        val spec = VideoSharingHelper.createShareSpec(outputs)
        assertNotNull(spec)
        assertEquals(outputs, spec!!.streamUris)
    }

    // 5. Only completed output URIs included
    @Test
    fun testOnlyCompletedOutputUrisIncluded() {
        val outputs = listOf(
            "content://media/external/video/media/part_1",
            "",
            "   ",
            "content://media/external/video/media/part_2"
        )
        val spec = VideoSharingHelper.createShareSpec(outputs)
        assertNotNull(spec)
        assertEquals(2, spec!!.streamUris.size)
        assertEquals(
            listOf("content://media/external/video/media/part_1", "content://media/external/video/media/part_2"),
            spec.streamUris
        )
    }

    // 6. Source URI excluded
    @Test
    fun testSourceUriExcluded() {
        val source = "content://media/external/video/media/source_999"
        val outputs = listOf(
            source,
            "content://media/external/video/media/part_1",
            "content://media/external/video/media/part_2"
        )
        val spec = VideoSharingHelper.createShareSpec(outputs, sourceUri = source)
        assertNotNull(spec)
        assertEquals(2, spec!!.streamUris.size)
        assertFalse(spec.streamUris.contains(source))
        assertEquals("content://media/external/video/media/part_1", spec.streamUris[0])
        assertEquals("content://media/external/video/media/part_2", spec.streamUris[1])
    }

    // 7. Empty output list disables sharing
    @Test
    fun testEmptyOutputListDisablesSharing() {
        assertNull(VideoSharingHelper.createShareSpec(emptyList()))
        assertNull(VideoSharingHelper.createShareSpec(listOf("", "  ")))
        assertNull(
            VideoSharingHelper.createShareSpec(
                listOf("content://media/source"),
                sourceUri = "content://media/source"
            )
        )
    }

    // 8. Photo action cannot start twice
    @Test
    fun testPhotoActionCannotStartTwice() {
        // Can start when selected and job is not active
        assertTrue(PhotoStateReducer.canStartPhotoProcessing(isSelected = true, isProcessing = false, isJobActive = false))

        // Cannot start when job is already active
        assertFalse(PhotoStateReducer.canStartPhotoProcessing(isSelected = true, isProcessing = false, isJobActive = true))

        // Cannot start when already processing
        assertFalse(PhotoStateReducer.canStartPhotoProcessing(isSelected = true, isProcessing = true, isJobActive = false))
        assertFalse(PhotoStateReducer.canStartPhotoProcessing(isSelected = true, isProcessing = true, isJobActive = true))

        // Cannot start when not selected
        assertFalse(PhotoStateReducer.canStartPhotoProcessing(isSelected = false, isProcessing = false, isJobActive = false))
    }

    // 9. Video action cannot start twice
    @Test
    fun testVideoActionCannotStartTwice() {
        // Can start when video selected and not processing
        assertTrue(VideoSharingHelper.canStartVideoProcessing(hasSelectedVideo = true, isProcessing = false))

        // Cannot start when video processing is active
        assertFalse(VideoSharingHelper.canStartVideoProcessing(hasSelectedVideo = true, isProcessing = true))

        // Cannot start when no video selected
        assertFalse(VideoSharingHelper.canStartVideoProcessing(hasSelectedVideo = false, isProcessing = false))
        assertFalse(VideoSharingHelper.canStartVideoProcessing(hasSelectedVideo = false, isProcessing = true))
    }
}
