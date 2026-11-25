package com.techwin.statushd

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchPhotoInstrumentedTest {

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
    fun test6_threeItemBatch_succeedsWithCorrectMetadataAndPreservedOrder() = runBlocking {
        // Count pre-existing batch rows in Pictures/StatusHD/Batch/
        val initialBatchRows = fixtures.queryRowsByRelativePath("Pictures/StatusHD/Batch/")
        val initialBatchCount = initialBatchRows.size

        // 1. Seed three valid JPEGs
        val uri1 = fixtures.seedAsset("landscape_4000x3000.jpg")
        val uri2 = fixtures.seedAsset("portrait_2400x3200.jpg")
        val uri3 = fixtures.seedAsset("small_500x500.jpg")

        val inputUris = listOf(uri1.toString(), uri2.toString(), uri3.toString())

        // 2. Execute BatchPhotoRunner with AndroidPhotoPreparer
        val preparer = AndroidPhotoPreparer(processor)
        val runner = BatchPhotoRunner(preparer)

        val initialized = runner.initializeQueue(inputUris)
        assertTrue("Queue initialization should succeed for 3 valid items", initialized)

        // 3. Start batch processing with Status HD mode
        val started = runner.start(PhotoQualityMode.STATUS_HD)
        assertTrue("BatchRunner should start successfully", started)

        // 4. Require final snapshot Finished
        val snapshot = runner.getSnapshot()
        assertEquals("Final batch state must be Finished", BatchState.Finished, snapshot.state)

        // 5. Require 3 Done, 0 Failed, 0 Skipped
        val doneItems = snapshot.items.filter { it.status is BatchItemStatus.Done }
        val failedItems = snapshot.items.filter { it.status is BatchItemStatus.Failed }
        val skippedItems = snapshot.items.filter { it.status is BatchItemStatus.Skipped }

        assertEquals(3, doneItems.size)
        assertEquals(0, failedItems.size)
        assertEquals(0, skippedItems.size)

        // 6. Require exactly three new rows in Pictures/StatusHD/Batch/
        val finalBatchRows = fixtures.queryRowsByRelativePath("Pictures/StatusHD/Batch/")
        assertEquals(
            "Expected exactly 3 new rows in Pictures/StatusHD/Batch/",
            initialBatchCount + 3,
            finalBatchRows.size
        )

        // 7. Verify each output row properties, readability, and order preservation
        for (i in snapshot.items.indices) {
            val item = snapshot.items[i]
            assertEquals("Source identifier order must match input list order", inputUris[i], item.sourceIdentifier)
            assertEquals("Item index must match original order index", i, item.originalOrderIndex)

            val outputData = item.outputData
            assertNotNull("Output data must not be null for Done item $i", outputData)
            val outputUri = Uri.parse(outputData!!.outputIdentifier)
            fixtures.trackUri(outputUri)

            val meta = fixtures.queryRowMetadata(outputUri)
            assertNotNull("Output metadata row must exist in MediaStore for item $i", meta)
            assertEquals("MIME type must be image/jpeg", "image/jpeg", meta!!.mimeType)
            assertEquals("IS_PENDING must be 0 after successful batch publication", 0, meta.isPending)
            assertTrue(
                "RELATIVE_PATH must be in Pictures/StatusHD/Batch/",
                meta.relativePath?.startsWith("Pictures/StatusHD/Batch") == true
            )

            // Reopen output
            assertTrue("Output file for item $i must be openable and valid JPEG", fixtures.reopenOutput(outputUri))
        }
    }
}
