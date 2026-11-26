package com.techwin.statushd

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchPhotoMixedRetryInstrumentedTest {

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
        assertEquals(
            "Cleanup must leave no lingering test-prefix rows in MediaStore",
            0,
            fixtures.countLingeringTestPrefixRows()
        )
    }

    @Test
    fun mixedBatch_withValidJpegMisleadingPngCorruptJpegAndTrailingJpeg_followsTypedPolicyAndRetryRules() = runBlocking {
        val initialBatchCount = fixtures.queryRowsByRelativePath("Pictures/StatusHD/Batch/").size
        val initialPendingCount = fixtures.countPendingRows()

        // 1. Seed valid JPEG, misleading PNG, corrupt JPEG, and a trailing valid JPEG
        val validUri1 = fixtures.seedAsset("landscape_4000x3000.jpg")
        val pngUri = fixtures.seedAsset("misleading_png.jpg", mimeType = "image/jpeg")
        val corruptUri = fixtures.seedAsset("corrupt.jpg", mimeType = "image/jpeg")
        val validUri2 = fixtures.seedAsset("small_500x500.jpg")

        val inputUris = listOf(
            validUri1.toString(),
            pngUri.toString(),
            corruptUri.toString(),
            validUri2.toString()
        )

        val runner = BatchPhotoRunner(AndroidPhotoPreparer(processor))
        assertTrue(runner.initializeQueue(inputUris))

        // 2. First run
        assertTrue(runner.start(PhotoQualityMode.STATUS_HD))

        val firstSnapshot = runner.getSnapshot()
        assertEquals(BatchState.Finished, firstSnapshot.state)

        // Valid JPEG -> Done
        assertEquals(ItemState.Done, firstSnapshot.items[0].state)
        // Misleading PNG -> Skipped(UnsupportedFormat)
        assertEquals(ItemState.Skipped, firstSnapshot.items[1].state)
        assertEquals(ItemSkipReason.UnsupportedFormat, firstSnapshot.items[1].skipReason)
        // Corrupt JPEG -> follows actual typed production policy (Failed)
        assertEquals(ItemState.Failed, firstSnapshot.items[2].state)
        val corruptFailure = firstSnapshot.items[2].failureCategory
        assertNotNull("Corrupt item must record typed failure category", corruptFailure)
        // Later items continue -> trailing valid JPEG becomes Done
        assertEquals(ItemState.Done, firstSnapshot.items[3].state)

        val firstDoneUris = listOf(0, 3).map { idx ->
            val outUri = Uri.parse(firstSnapshot.items[idx].outputData!!.outputIdentifier)
            fixtures.trackUri(outUri)
            outUri
        }

        // Verify output count equals Done count (2) and all outputs are valid JPEGs with IS_PENDING=0
        val afterFirstBatchCount = fixtures.queryRowsByRelativePath("Pictures/StatusHD/Batch/").size
        assertEquals(initialBatchCount + 2, afterFirstBatchCount)
        firstDoneUris.forEach { outUri ->
            val meta = fixtures.queryRowMetadata(outUri)
            assertNotNull(meta)
            assertEquals("image/jpeg", meta!!.mimeType)
            assertEquals(0, meta.isPending)
            assertTrue(fixtures.reopenOutput(outUri))
        }

        // 3. Retry follows typed production eligibility
        val retryResult = runner.retryFailed()
        when (corruptFailure) {
            PhotoProcessingFailure.SaveFailed,
            is PhotoProcessingFailure.ProcessingFailed -> {
                assertEquals(RetryResultCode.STARTED, retryResult.code)
                assertEquals(1, retryResult.retriedCount)
            }
            PhotoProcessingFailure.SourceUnavailable -> {
                assertEquals(RetryResultCode.NO_ELIGIBLE_FAILURES, retryResult.code)
                assertEquals(0, retryResult.retriedCount)
            }
            else -> {
                assertEquals(RetryResultCode.NO_ELIGIBLE_FAILURES, retryResult.code)
                assertEquals(0, retryResult.retriedCount)
            }
        }

        val afterRetrySnapshot = runner.getSnapshot()
        assertEquals(BatchState.Finished, afterRetrySnapshot.state)

        // UnsupportedFormat remains Skipped
        assertEquals(ItemState.Skipped, afterRetrySnapshot.items[1].state)
        assertEquals(ItemSkipReason.UnsupportedFormat, afterRetrySnapshot.items[1].skipReason)

        // Done outputs are not duplicated and retain exact same output identifiers
        assertEquals(ItemState.Done, afterRetrySnapshot.items[0].state)
        assertEquals(
            firstDoneUris[0].toString(),
            afterRetrySnapshot.items[0].outputData!!.outputIdentifier
        )
        assertEquals(ItemState.Done, afterRetrySnapshot.items[3].state)
        assertEquals(
            firstDoneUris[1].toString(),
            afterRetrySnapshot.items[3].outputData!!.outputIdentifier
        )

        val afterRetryBatchCount = fixtures.queryRowsByRelativePath("Pictures/StatusHD/Batch/").size
        assertEquals(
            "Done outputs must not be duplicated on retry",
            initialBatchCount + 2,
            afterRetryBatchCount
        )

        assertEquals(
            "No pending MediaStore rows may remain",
            initialPendingCount,
            fixtures.countPendingRows()
        )
    }

    @Test
    fun mixedBatch_withUnavailableSource_producesSourceUnavailableAndIsNotRetryEligible() = runBlocking {
        val initialBatchCount = fixtures.queryRowsByRelativePath("Pictures/StatusHD/Batch/").size
        val initialPendingCount = fixtures.countPendingRows()

        val validUri1 = fixtures.seedAsset("landscape_4000x3000.jpg")
        val pngUri = fixtures.seedAsset("misleading_png.jpg", mimeType = "image/jpeg")
        val unavailableUri = fixtures.seedAsset("small_500x500.jpg")
        // Delete the seeded row so ContentResolver raises FileNotFoundException -> SourceUnavailable
        assertEquals(1, context.contentResolver.delete(unavailableUri, null, null))
        val validUri2 = fixtures.seedAsset("small_500x500.jpg")

        val runner = BatchPhotoRunner(AndroidPhotoPreparer(processor))
        assertTrue(
            runner.initializeQueue(
                listOf(
                    validUri1.toString(),
                    pngUri.toString(),
                    unavailableUri.toString(),
                    validUri2.toString()
                )
            )
        )

        assertTrue(runner.start(PhotoQualityMode.STATUS_HD))

        val firstSnapshot = runner.getSnapshot()
        assertEquals(BatchState.Finished, firstSnapshot.state)
        assertEquals(ItemState.Done, firstSnapshot.items[0].state)
        assertEquals(ItemState.Skipped, firstSnapshot.items[1].state)
        assertEquals(ItemSkipReason.UnsupportedFormat, firstSnapshot.items[1].skipReason)
        assertEquals(ItemState.Failed, firstSnapshot.items[2].state)
        assertEquals(PhotoProcessingFailure.SourceUnavailable, firstSnapshot.items[2].failureCategory)
        assertEquals(ItemState.Done, firstSnapshot.items[3].state)

        listOf(0, 3).forEach { idx ->
            fixtures.trackUri(Uri.parse(firstSnapshot.items[idx].outputData!!.outputIdentifier))
        }

        // SourceUnavailable is not retry-eligible
        val retryResult = runner.retryFailed()
        assertEquals(RetryResultCode.NO_ELIGIBLE_FAILURES, retryResult.code)
        assertEquals(0, retryResult.retriedCount)

        val afterRetrySnapshot = runner.getSnapshot()
        assertEquals(ItemState.Failed, afterRetrySnapshot.items[2].state)
        assertEquals(PhotoProcessingFailure.SourceUnavailable, afterRetrySnapshot.items[2].failureCategory)
        assertEquals(ItemState.Skipped, afterRetrySnapshot.items[1].state)
        assertEquals(ItemSkipReason.UnsupportedFormat, afterRetrySnapshot.items[1].skipReason)

        assertEquals(
            initialBatchCount + 2,
            fixtures.queryRowsByRelativePath("Pictures/StatusHD/Batch/").size
        )
        assertEquals(initialPendingCount, fixtures.countPendingRows())
    }
}
