package com.techwin.statushd

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoProcessorInstrumentedTest {

    private lateinit var context: Context
    private lateinit var processor: PhotoProcessor
    private lateinit var fixtures: MediaStoreTestFixtures

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        processor = PhotoProcessor(context)
        fixtures = MediaStoreTestFixtures(context)
    }

    @After
    fun tearDown() {
        fixtures.cleanup()
        assertEquals("Cleanup must leave no lingering test-prefix rows in MediaStore", 0, fixtures.countLingeringTestPrefixRows())
    }

    @Test
    fun test1_statusHdProcessing_publishesExpectedJpegWithoutDistortion() = runBlocking {
        // Seed landscape fixture (4000x3000)
        val sourceUri = fixtures.seedAsset("landscape_4000x3000.jpg")
        val initialMeta = fixtures.queryRowMetadata(sourceUri)
        assertNotNull("Source row must exist in MediaStore", initialMeta)
        val initialSize = initialMeta!!.size ?: 0L
        assertTrue("Source size must be positive", initialSize > 0)

        // Inspect source
        val inspection = processor.inspectSource(sourceUri)
        assertTrue("Expected Success inspection but was $inspection", inspection is PhotoInspectionResult.Success)
        val successInspection = inspection as PhotoInspectionResult.Success
        assertEquals(4000, successInspection.width)
        assertEquals(3000, successInspection.height)

        // Prepare using quality 90 (Status HD) and Single destination
        val result = processor.preparePhoto(
            sourceUri = sourceUri,
            mode = PhotoQualityMode.STATUS_HD,
            destination = PhotoDestination.Single
        )

        assertTrue("Expected Success but was $result", result is PhotoProcessingResult.Success)
        val success = result as PhotoProcessingResult.Success
        fixtures.trackUri(success.outputUri)

        // Assert content Uri
        assertEquals("content", success.outputUri.scheme?.lowercase())

        // Query row metadata from MediaStore
        val outputMeta = fixtures.queryRowMetadata(success.outputUri)
        assertNotNull("Output row must exist in MediaStore", outputMeta)
        assertEquals("image/jpeg", outputMeta!!.mimeType)
        assertTrue("RELATIVE_PATH must be in Pictures/StatusHD/", outputMeta.relativePath?.startsWith("Pictures/StatusHD") == true)
        assertEquals(0, outputMeta.isPending)

        // Reopen output successfully
        assertTrue("Output URI must be readable as a valid bitmap", fixtures.reopenOutput(success.outputUri))

        // Assert no crop, stretch or upscale:
        // Expected target dimensions for 4000x3000 in 1920x1080 box:
        // scale = min(1920/4000, 1080/3000) = min(0.48, 0.36) = 0.36 -> 1440 x 1080
        val expectedTarget = TargetSizeCalculator.calculate(4000, 3000)
        assertEquals(1440, expectedTarget.width)
        assertEquals(1080, expectedTarget.height)
        assertEquals(expectedTarget.width, success.outputWidth)
        assertEquals(expectedTarget.height, success.outputHeight)

        val decodedBounds = fixtures.decodeOutputBounds(success.outputUri)
        assertNotNull("Decoded bounds must not be null", decodedBounds)
        assertEquals(1440, decodedBounds!!.first)
        assertEquals(1080, decodedBounds.second)

        // Aspect ratio preserved within 0.001 tolerance (4000:3000 = 1440:1080 = 4:3)
        val sourceAspect = 4000.0 / 3000.0
        val outputAspect = decodedBounds.first.toDouble() / decodedBounds.second.toDouble()
        assertEquals(sourceAspect, outputAspect, 0.001)

        // Assert source row still exists and source size is unchanged
        val sourceMetaAfter = fixtures.queryRowMetadata(sourceUri)
        assertNotNull("Source row must still exist after processing", sourceMetaAfter)
        assertEquals("Source size must remain unchanged", initialSize, sourceMetaAfter!!.size)
    }

    @Test
    fun test2_smallerFileProcessing_producesValidJpegAndDistinguishableSize() = runBlocking {
        // Use the same deterministic landscape fixture
        val sourceUri = fixtures.seedAsset("landscape_4000x3000.jpg")

        // Prepare quality 90 (Status HD)
        val result90 = processor.preparePhoto(
            sourceUri = sourceUri,
            mode = PhotoQualityMode.STATUS_HD,
            destination = PhotoDestination.Single
        )
        assertTrue("Status HD preparation failed", result90 is PhotoProcessingResult.Success)
        val success90 = result90 as PhotoProcessingResult.Success
        fixtures.trackUri(success90.outputUri)

        // Prepare quality 80 (Smaller File)
        val result80 = processor.preparePhoto(
            sourceUri = sourceUri,
            mode = PhotoQualityMode.SMALLER_FILE,
            destination = PhotoDestination.Single
        )
        assertTrue("Smaller File preparation failed", result80 is PhotoProcessingResult.Success)
        val success80 = result80 as PhotoProcessingResult.Success
        fixtures.trackUri(success80.outputUri)

        // Assert publication properties for Smaller File output
        val outputMeta80 = fixtures.queryRowMetadata(success80.outputUri)
        assertNotNull("Smaller File output row must exist in MediaStore", outputMeta80)
        assertEquals("image/jpeg", outputMeta80!!.mimeType)
        assertTrue("RELATIVE_PATH must be in Pictures/StatusHD/", outputMeta80.relativePath?.startsWith("Pictures/StatusHD") == true)
        assertEquals(0, outputMeta80.isPending)

        // Assert output can reopen
        assertTrue("Smaller File output URI must be readable", fixtures.reopenOutput(success80.outputUri))

        // Compare size against quality 90 only for this exact fixture:
        // DOCUMENTATION:
        // This size comparison (quality 80 producing a strictly smaller file than quality 90)
        // is verified specifically for this deterministic synthetic test fixture (landscape_4000x3000.jpg)
        // containing high-frequency color patterns. It is fixture-specific and not asserted as a universal
        // mathematical invariant across all conceivable image inputs.
        assertTrue(
            "Smaller File size (${success80.outputSize}) must be smaller than Status HD size (${success90.outputSize}) for this fixture",
            success80.outputSize < success90.outputSize
        )
    }

    @Test
    fun test3_smallSource_isNotUpscaled() = runBlocking {
        // Seed a JPEG below target dimensions (500x500 < 1920x1080)
        val sourceUri = fixtures.seedAsset("small_500x500.jpg")

        val result = processor.preparePhoto(
            sourceUri = sourceUri,
            mode = PhotoQualityMode.STATUS_HD,
            destination = PhotoDestination.Single
        )

        assertTrue("Expected Success for small JPEG but got $result", result is PhotoProcessingResult.Success)
        val success = result as PhotoProcessingResult.Success
        fixtures.trackUri(success.outputUri)

        // Assert output dimensions equal source dimensions
        assertEquals(500, success.outputWidth)
        assertEquals(500, success.outputHeight)

        val decodedBounds = fixtures.decodeOutputBounds(success.outputUri)
        assertNotNull(decodedBounds)
        assertEquals(500, decodedBounds!!.first)
        assertEquals(500, decodedBounds.second)
    }

    @Test
    fun test4_unsupportedFormat_rejectsMisleadingPngWithoutPublishing() = runBlocking {
        val initialOutputs = fixtures.queryPublishedOutputsIn("Pictures/StatusHD").map { it.uri }.toSet()

        // Seed PNG content with a misleading .jpg display name and MediaStore MIME
        val sourceUri = fixtures.seedAsset("misleading_png.jpg", mimeType = "image/jpeg")

        // Inspect should detect true PNG header and fail with UnsupportedFormat
        val inspectResult = processor.inspectSource(sourceUri)
        assertTrue("Expected UnsupportedFormat inspection failure but got $inspectResult", inspectResult is PhotoInspectionResult.Failure)
        assertEquals(PhotoProcessingFailure.UnsupportedFormat, (inspectResult as PhotoInspectionResult.Failure).failure)

        // Prepare should also reject it with UnsupportedFormat
        val prepareResult = processor.preparePhoto(sourceUri, PhotoQualityMode.STATUS_HD)
        assertTrue("Expected UnsupportedFormat processing failure but got $prepareResult", prepareResult is PhotoProcessingResult.Failure)
        assertEquals(PhotoProcessingFailure.UnsupportedFormat, (prepareResult as PhotoProcessingResult.Failure).failure)

        // Assert no new output row was created
        val finalOutputs = fixtures.queryPublishedOutputsIn("Pictures/StatusHD").map { it.uri }.toSet()
        val newOutputs = finalOutputs - initialOutputs
        assertEquals("No new output row must be published for unsupported format", 0, newOutputs.size)
    }

    @Test
    fun test5_corruptJpeg_failsGracefullyWithNoPublishedOrPendingRows() = runBlocking {
        val initialOutputs = fixtures.queryPublishedOutputsIn("Pictures/StatusHD").map { it.uri }.toSet()
        val initialPendingCount = fixtures.countPendingRows()

        // Seed corrupt JPEG bytes
        val sourceUri = fixtures.seedAsset("corrupt.jpg", mimeType = "image/jpeg")

        // Inspect source should fail
        val inspectResult = processor.inspectSource(sourceUri)
        assertTrue("Expected Failure on corrupt inspection but got $inspectResult", inspectResult is PhotoInspectionResult.Failure)
        val inspectFailure = (inspectResult as PhotoInspectionResult.Failure).failure
        assertTrue(
            "Expected SourceUnavailable or ProcessingFailed on corrupt inspection but got $inspectFailure",
            inspectFailure is PhotoProcessingFailure.SourceUnavailable || inspectFailure is PhotoProcessingFailure.ProcessingFailed
        )

        // Prepare should fail
        val prepareResult = processor.preparePhoto(sourceUri, PhotoQualityMode.STATUS_HD)
        assertTrue("Expected Failure on corrupt preparation but got $prepareResult", prepareResult is PhotoProcessingResult.Failure)

        // Assert no published output row
        val finalOutputs = fixtures.queryPublishedOutputsIn("Pictures/StatusHD").map { it.uri }.toSet()
        val newOutputs = finalOutputs - initialOutputs
        assertEquals("No published output row must exist for corrupt source", 0, newOutputs.size)

        // Assert no test-owned pending row remains
        val finalPendingCount = fixtures.countPendingRows()
        assertEquals("Pending row count must not increase after failure cleanup", initialPendingCount, finalPendingCount)
    }
}
