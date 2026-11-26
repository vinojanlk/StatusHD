package com.techwin.statushd

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchPhotoStopInstrumentedTest {

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
    fun stopAfterCurrent_latchesStoppingFinishesActiveItemAndSkipsRemainingWaitingItems() = runBlocking {
        val initialBatchCount = fixtures.queryRowsByRelativePath("Pictures/StatusHD/Batch/").size
        val initialPendingCount = fixtures.countPendingRows()

        // 1. Seed three deterministic large JPEG fixtures
        val uri1 = fixtures.seedAsset("landscape_4000x3000.jpg")
        val uri2 = fixtures.seedAsset("portrait_2400x3200.jpg")
        val uri3 = fixtures.seedAsset("landscape_4000x3000.jpg")
        val inputUris = listOf(uri1.toString(), uri2.toString(), uri3.toString())

        val realPreparer = AndroidPhotoPreparer(processor)
        val item1ProcessingStarted = CompletableDeferred<Unit>()
        val allowItem1ToFinish = CompletableDeferred<Unit>()
        val startedSources = mutableListOf<String>()

        val gatedPreparer = object : PhotoPreparer {
            override suspend fun prepare(
                sourceUri: String,
                mode: PhotoQualityMode,
                destination: PhotoDestination
            ): BatchPhotoPreparationResult {
                synchronized(startedSources) {
                    startedSources.add(sourceUri)
                }
                if (sourceUri == uri1.toString()) {
                    item1ProcessingStarted.complete(Unit)
                    withTimeout(10_000L) {
                        allowItem1ToFinish.await()
                    }
                }
                return realPreparer.prepare(sourceUri, mode, destination)
            }
        }

        val runner = BatchPhotoRunner(gatedPreparer)
        val observedStates = mutableListOf<BatchState>()
        runner.onSnapshotChanged = { snapshot ->
            synchronized(observedStates) {
                observedStates.add(snapshot.state)
            }
        }

        val initialized = runner.initializeQueue(inputUris)
        assertTrue("Queue initialization should succeed", initialized)

        // 2. Start batch processing on IO dispatcher
        val startJob = async(Dispatchers.IO) {
            runner.start(PhotoQualityMode.STATUS_HD)
        }

        // 3. Confirm item 1 reaches Processing
        withTimeout(10_000L) {
            item1ProcessingStarted.await()
        }
        val activeSnapshot = runner.getSnapshot()
        assertEquals(BatchState.Running, activeSnapshot.state)
        assertEquals(ItemState.Processing, activeSnapshot.items[0].state)
        assertEquals(ItemState.Waiting, activeSnapshot.items[1].state)
        assertEquals(ItemState.Waiting, activeSnapshot.items[2].state)

        // 4. Issue stopAfterCurrent while item 1 is active
        val stopResult = runner.stopAfterCurrent()
        assertEquals(StopResult.ACCEPTED, stopResult)

        // 5. State becomes Stopping and Waiting items immediately become Skipped(UserStopped)
        val stoppingSnapshot = runner.getSnapshot()
        assertEquals(BatchState.Stopping, stoppingSnapshot.state)
        assertEquals(ItemState.Processing, stoppingSnapshot.items[0].state)
        assertEquals(ItemState.Skipped, stoppingSnapshot.items[1].state)
        assertEquals(ItemSkipReason.UserStopped, stoppingSnapshot.items[1].skipReason)
        assertEquals(ItemState.Skipped, stoppingSnapshot.items[2].state)
        assertEquals(ItemSkipReason.UserStopped, stoppingSnapshot.items[2].skipReason)

        // 6. Release active item to settle with its real result
        allowItem1ToFinish.complete(Unit)
        val started = withTimeout(30_000L) {
            startJob.await()
        }
        assertTrue("BatchRunner should report started", started)

        // 7. Verify final state Finished, item 1 Done, remaining items Skipped(UserStopped)
        val finalSnapshot = runner.getSnapshot()
        assertEquals(BatchState.Finished, finalSnapshot.state)
        synchronized(observedStates) {
            assertTrue("Stopping state must have been observed", observedStates.contains(BatchState.Stopping))
        }
        synchronized(startedSources) {
            assertEquals(
                "No new item may begin after Stop is latched",
                listOf(uri1.toString()),
                startedSources
            )
        }

        assertEquals(ItemState.Done, finalSnapshot.items[0].state)
        assertEquals(ItemState.Skipped, finalSnapshot.items[1].state)
        assertEquals(ItemSkipReason.UserStopped, finalSnapshot.items[1].skipReason)
        assertEquals(ItemState.Skipped, finalSnapshot.items[2].state)
        assertEquals(ItemSkipReason.UserStopped, finalSnapshot.items[2].skipReason)

        val summary = BatchPhotoSummary.fromItems(finalSnapshot.items)
        assertEquals(1, summary.done)
        assertEquals(0, summary.failed)
        assertEquals(2, summary.skipped)

        // 8. Verify MediaStore output count equals Done item count, MIME image/jpeg, IS_PENDING=0
        val outputData = finalSnapshot.items[0].outputData
        assertNotNull("Done item must have outputData", outputData)
        val outputUri = Uri.parse(outputData!!.outputIdentifier)
        fixtures.trackUri(outputUri)

        val meta = fixtures.queryRowMetadata(outputUri)
        assertNotNull("Published output row must exist in MediaStore", meta)
        assertEquals("image/jpeg", meta!!.mimeType)
        assertEquals(0, meta.isPending)
        assertTrue(
            "RELATIVE_PATH must be in Pictures/StatusHD/Batch/",
            meta.relativePath?.startsWith("Pictures/StatusHD/Batch") == true
        )
        assertTrue("Published JPEG must reopen cleanly", fixtures.reopenOutput(outputUri))

        val finalBatchCount = fixtures.queryRowsByRelativePath("Pictures/StatusHD/Batch/").size
        assertEquals(
            "Output row count in Pictures/StatusHD/Batch/ must equal Done item count (1)",
            initialBatchCount + 1,
            finalBatchCount
        )

        // 9. Verify no test-created pending rows remain
        assertEquals(
            "No pending MediaStore rows may remain",
            initialPendingCount,
            fixtures.countPendingRows()
        )
    }
}
