package com.techwin.statushd

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BatchPhotoQueueTest {

    class FakePhotoPreparer : PhotoPreparer {
        var activeConcurrency = AtomicInteger(0)
        var peakConcurrency = AtomicInteger(0)
        val preparedCalls = mutableListOf<String>()
        var customBehavior: (suspend (String, PhotoQualityMode) -> BatchPhotoPreparationResult)? = null

        override suspend fun prepare(
            sourceUri: String,
            mode: PhotoQualityMode,
            destination: PhotoDestination
        ): BatchPhotoPreparationResult {
            val current = activeConcurrency.incrementAndGet()
            var peak = peakConcurrency.get()
            while (current > peak) {
                if (peakConcurrency.compareAndSet(peak, current)) break
                peak = peakConcurrency.get()
            }

            synchronized(preparedCalls) {
                preparedCalls.add(sourceUri)
            }

            val result = customBehavior?.invoke(sourceUri, mode) ?: BatchPhotoPreparationResult.Success(
                outputIdentifier = "out_$sourceUri.jpg",
                outputWidth = 1080,
                outputHeight = 1920,
                outputSize = 50000L
            )

            activeConcurrency.decrementAndGet()
            return result
        }
    }

    // 1. Selection Policy Tests (6 tests)
    @Test
    fun test0SelectedRemainsDraftCannotStart() {
        val result = BatchPhotoSelectionPolicy.validateAndDeduplicate(emptyList())
        assertFalse(result.isValidForStart)
        assertEquals(SelectionValidationReason.EMPTY, result.reasonCode)
        assertTrue(result.acceptedIdentifiers.isEmpty())
    }

    @Test
    fun test1DistinctCannotStart() {
        val result = BatchPhotoSelectionPolicy.validateAndDeduplicate(listOf("img1.jpg"))
        assertFalse(result.isValidForStart)
        assertEquals(SelectionValidationReason.TOO_FEW, result.reasonCode)
        assertEquals(1, result.acceptedIdentifiers.size)
    }

    @Test
    fun test2DistinctAccepted() {
        val result = BatchPhotoSelectionPolicy.validateAndDeduplicate(listOf("img1.jpg", "img2.jpg"))
        assertTrue(result.isValidForStart)
        assertEquals(SelectionValidationReason.VALID, result.reasonCode)
        assertEquals(2, result.acceptedIdentifiers.size)
    }

    @Test
    fun test10DistinctAccepted() {
        val list = (1..10).map { "img$it.jpg" }
        val result = BatchPhotoSelectionPolicy.validateAndDeduplicate(list)
        assertTrue(result.isValidForStart)
        assertEquals(SelectionValidationReason.VALID, result.reasonCode)
        assertEquals(10, result.acceptedIdentifiers.size)
    }

    @Test
    fun test11DistinctRejectsEntireSelection() {
        val list = (1..11).map { "img$it.jpg" }
        val result = BatchPhotoSelectionPolicy.validateAndDeduplicate(list)
        assertFalse(result.isValidForStart)
        assertEquals(SelectionValidationReason.TOO_MANY, result.reasonCode)
        assertTrue(result.acceptedIdentifiers.isEmpty())
    }

    @Test
    fun testDuplicateIdentifiersRemovedPreservingFirstOccurrence() {
        val input = listOf("b.jpg", "a.jpg", "b.jpg", "c.jpg", "a.jpg")
        val result = BatchPhotoSelectionPolicy.validateAndDeduplicate(input)
        assertEquals(listOf("b.jpg", "a.jpg", "c.jpg"), result.acceptedIdentifiers)
        assertEquals(2, result.duplicatesRemoved)
        assertTrue(result.isValidForStart)
    }

    // 2. Item State Invariant Tests (4 tests)
    @Test
    fun testWaitingItemHasNoOutputOrFailure() {
        val item = BatchPhotoItem("1", "src.jpg", 0, BatchItemStatus.Waiting)
        assertEquals(ItemState.Waiting, item.state)
        assertNull(item.outputData)
        assertNull(item.failureCategory)
        assertEquals(ItemSkipReason.None, item.skipReason)
    }

    @Test
    fun testDoneItemHasOutputAndNoFailure() {
        val successData = BatchPhotoPreparationResult.Success("out.jpg", 100, 100, 1000)
        val item = BatchPhotoItem("1", "src.jpg", 0, BatchItemStatus.Done(successData))
        assertEquals(ItemState.Done, item.state)
        assertEquals(successData, item.outputData)
        assertNull(item.failureCategory)
        assertEquals(ItemSkipReason.None, item.skipReason)
    }

    @Test
    fun testFailedItemHasFailureAndNoOutput() {
        val failure = PhotoProcessingFailure.SaveFailed
        val item = BatchPhotoItem("1", "src.jpg", 0, BatchItemStatus.Failed(failure))
        assertEquals(ItemState.Failed, item.state)
        assertEquals(failure, item.failureCategory)
        assertNull(item.outputData)
        assertEquals(ItemSkipReason.None, item.skipReason)
    }

    @Test
    fun testSkippedItemHasSkipReasonAndNoOutputOrFailure() {
        val item = BatchPhotoItem("1", "src.jpg", 0, BatchItemStatus.Skipped(ItemSkipReason.UserStopped))
        assertEquals(ItemState.Skipped, item.state)
        assertEquals(ItemSkipReason.UserStopped, item.skipReason)
        assertNull(item.outputData)
        assertNull(item.failureCategory)
    }

    // 3. State Transitions & Ordinary Start (6 tests)
    @Test
    fun testDraftToRunningToFinished() {
        runBlocking {
            val fake = FakePhotoPreparer()
            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))
            assertEquals(BatchState.Draft, runner.getSnapshot().state)

            runner.start(PhotoQualityMode.STATUS_HD)
            assertEquals(BatchState.Finished, runner.getSnapshot().state)
        }
    }

    @Test
    fun testRunningToFinished() {
        runBlocking {
            val fake = FakePhotoPreparer()
            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            val snapshot = runner.getSnapshot()
            assertEquals(BatchState.Finished, snapshot.state)
            assertEquals(2, BatchPhotoSummary.fromItems(snapshot.items).done)
        }
    }

    @Test
    fun testRunningToStoppingToFinished() {
        runBlocking {
            val startedGate = CompletableDeferred<Unit>()
            val itemGate = CompletableDeferred<Unit>()
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                if (uri == "1.jpg") {
                    startedGate.complete(Unit)
                    itemGate.await()
                }
                BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }
            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg", "3.jpg"))

            val dStart = async(Dispatchers.Default) { runner.start(PhotoQualityMode.STATUS_HD) }
            startedGate.await()

            val stopRes = runner.stopAfterCurrent()
            assertEquals(StopResult.ACCEPTED, stopRes)

            itemGate.complete(Unit)
            dStart.await()

            val snapshot = runner.getSnapshot()
            assertEquals(BatchState.Finished, snapshot.state)
            assertEquals(ItemState.Done, snapshot.items[0].state)
            assertEquals(ItemState.Skipped, snapshot.items[1].state)
            assertEquals(ItemSkipReason.UserStopped, snapshot.items[1].skipReason)
            assertEquals(ItemState.Skipped, snapshot.items[2].state)
        }
    }

    @Test
    fun testIllegalTransitionsRejected() {
        runBlocking {
            val fake = FakePhotoPreparer()
            val runner = BatchPhotoRunner(fake)
            val started = runner.start(PhotoQualityMode.STATUS_HD)
            assertFalse(started)
            assertEquals(BatchState.Draft, runner.getSnapshot().state)
        }
    }

    @Test
    fun testModeCannotChangeAfterStart() {
        runBlocking {
            val fake = FakePhotoPreparer()
            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            assertEquals(PhotoQualityMode.STATUS_HD, runner.getSnapshot().lockedMode)
        }
    }

    @Test
    fun testStartAfterFinishedReturnsFalseAndDoesNotChangeQueue() {
        runBlocking {
            val fake = FakePhotoPreparer()
            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            val firstCallsCount = fake.preparedCalls.size

            val secondStart = runner.start(PhotoQualityMode.SMALLER_FILE)

            assertFalse(secondStart)
            val snapshot = runner.getSnapshot()
            assertEquals(BatchState.Finished, snapshot.state)
            assertEquals(PhotoQualityMode.STATUS_HD, snapshot.lockedMode)
            assertEquals(firstCallsCount, fake.preparedCalls.size)
            assertEquals(ItemState.Done, snapshot.items[0].state)
            assertEquals(ItemState.Done, snapshot.items[1].state)
        }
    }

    // 4. Initialization Serialization (6 tests)
    @Test
    fun testInitializeRacingStart() {
        runBlocking {
            val startedGate = CompletableDeferred<Unit>()
            val itemGate = CompletableDeferred<Unit>()
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                startedGate.complete(Unit)
                itemGate.await()
                BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }
            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))

            val dStart = async(Dispatchers.Default) { runner.start(PhotoQualityMode.STATUS_HD) }
            startedGate.await()

            val initResult = runner.initializeQueue(listOf("a.jpg", "b.jpg"))
            assertFalse(initResult)

            itemGate.complete(Unit)
            dStart.await()
        }
    }

    @Test
    fun testInitializeRejectedDuringRunning() {
        runBlocking {
            val startedGate = CompletableDeferred<Unit>()
            val itemGate = CompletableDeferred<Unit>()
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                startedGate.complete(Unit)
                itemGate.await()
                BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }
            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))

            val dStart = async(Dispatchers.Default) { runner.start(PhotoQualityMode.STATUS_HD) }
            startedGate.await()

            val reinit = runner.initializeQueue(listOf("x.jpg", "y.jpg"))
            assertFalse(reinit)

            itemGate.complete(Unit)
            dStart.await()
        }
    }

    @Test
    fun testInitializeRejectedDuringStopping() {
        runBlocking {
            val startedGate = CompletableDeferred<Unit>()
            val itemGate = CompletableDeferred<Unit>()
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                startedGate.complete(Unit)
                itemGate.await()
                BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }
            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))

            val dStart = async(Dispatchers.Default) { runner.start(PhotoQualityMode.STATUS_HD) }
            startedGate.await()

            runner.stopAfterCurrent()

            val reinit = runner.initializeQueue(listOf("a.jpg", "b.jpg"))
            assertFalse(reinit)

            itemGate.complete(Unit)
            dStart.await()
        }
    }

    @Test
    fun testExplicitNewQueueAfterFinished() {
        runBlocking {
            val fake = FakePhotoPreparer()
            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            val newInit = runner.initializeQueue(listOf("a.jpg", "b.jpg", "c.jpg"))
            assertTrue(newInit)

            val snapshot = runner.getSnapshot()
            assertEquals(BatchState.Draft, snapshot.state)
            assertEquals(3, snapshot.items.size)
            assertEquals("a.jpg", snapshot.items[0].sourceIdentifier)
        }
    }

    @Test
    fun testNewQueueClearsOldMode() {
        runBlocking {
            val fake = FakePhotoPreparer()
            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))
            runner.start(PhotoQualityMode.SMALLER_FILE)

            runner.initializeQueue(listOf("a.jpg", "b.jpg"))
            val snapshot = runner.getSnapshot()
            assertNull(snapshot.lockedMode)
        }
    }

    @Test
    fun testNewQueueContainsOnlyNewIdentifiers() {
        runBlocking {
            val fake = FakePhotoPreparer()
            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            runner.initializeQueue(listOf("x.jpg", "y.jpg"))
            val identifiers = runner.getSnapshot().items.map { it.sourceIdentifier }
            assertEquals(listOf("x.jpg", "y.jpg"), identifiers)
        }
    }

    // 5. Atomic Claim & Deterministic Concurrency Races (3 tests)
    @Test
    fun testRaceA_ActiveItemCompletesStopRequestRacesNextClaim() {
        runBlocking {
            val startedGate = CompletableDeferred<Unit>()
            val itemGate = CompletableDeferred<Unit>()
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                if (uri == "1.jpg") {
                    startedGate.complete(Unit)
                    itemGate.await()
                }
                BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))

            val dStart = async(Dispatchers.Default) { runner.start(PhotoQualityMode.STATUS_HD) }
            startedGate.await()

            val stopRes = runner.stopAfterCurrent()
            assertEquals(StopResult.ACCEPTED, stopRes)

            itemGate.complete(Unit)
            dStart.await()

            val snapshot = runner.getSnapshot()
            assertEquals(BatchState.Finished, snapshot.state)
            assertEquals(1, fake.preparedCalls.size)
            assertEquals("1.jpg", fake.preparedCalls.first())
            assertEquals(ItemState.Skipped, snapshot.items[1].state)
        }
    }

    @Test
    fun testRaceB_ConcurrentStartCallsProcessedOncePerItem() {
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                gate.await()
                BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }
            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))

            val d1 = async(Dispatchers.Default) { runner.start(PhotoQualityMode.STATUS_HD) }
            val d2 = async(Dispatchers.Default) { runner.start(PhotoQualityMode.STATUS_HD) }

            gate.complete(Unit)
            val results = awaitAll(d1, d2)

            assertTrue(results.contains(true))
            assertTrue(results.contains(false))
            assertEquals(2, fake.preparedCalls.size)
            assertEquals(listOf("1.jpg", "2.jpg"), fake.preparedCalls)
        }
    }

    @Test
    fun testRaceC_ConcurrentRetryAndStopNoItemDuplicated() {
        runBlocking {
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                if (uri == "2.jpg") BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SaveFailed)
                else BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            val startedGate = CompletableDeferred<Unit>()
            val retryGate = CompletableDeferred<Unit>()
            fake.customBehavior = { uri, _ ->
                startedGate.complete(Unit)
                retryGate.await()
                BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }

            val dRetry = async(Dispatchers.Default) { runner.retryFailed() }
            startedGate.await()

            val stopRes = runner.stopAfterCurrent()
            retryGate.complete(Unit)

            dRetry.await()

            val snapshot = runner.getSnapshot()
            assertEquals(BatchState.Finished, snapshot.state)
            assertEquals(1, fake.peakConcurrency.get())
            assertTrue(stopRes == StopResult.ACCEPTED || stopRes == StopResult.ALREADY_STOPPING)
        }
    }

    // 6. Stop-After-Current & Active Stop (6 tests)
    @Test
    fun testActiveStop_FirstStopAcceptedSecondAlreadyStoppingStateFinished() {
        runBlocking {
            val startedGate = CompletableDeferred<Unit>()
            val itemGate = CompletableDeferred<Unit>()
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                if (uri == "1.jpg") {
                    startedGate.complete(Unit)
                    itemGate.await()
                }
                BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))

            val dStart = async(Dispatchers.Default) { runner.start(PhotoQualityMode.STATUS_HD) }
            startedGate.await()

            val stop1 = runner.stopAfterCurrent()
            val stop2 = runner.stopAfterCurrent()

            assertEquals(StopResult.ACCEPTED, stop1)
            assertEquals(StopResult.ALREADY_STOPPING, stop2)
            assertEquals(BatchState.Stopping, runner.getSnapshot().state)

            itemGate.complete(Unit)
            dStart.await()

            val snapshot = runner.getSnapshot()
            assertEquals(BatchState.Finished, snapshot.state)
            assertEquals(1, fake.preparedCalls.size)
            assertEquals(ItemState.Done, snapshot.items[0].state)
            assertEquals(ItemState.Skipped, snapshot.items[1].state)
        }
    }

    @Test
    fun testStopDuringItem4Of10AllowsItem4ToSettleAndItems5To10BecomeSkipped() {
        runBlocking {
            val fake = FakePhotoPreparer()
            var callCount = 0
            var runner: BatchPhotoRunner? = null
            fake.customBehavior = { uri, _ ->
                callCount++
                if (callCount == 4) {
                    runBlocking { runner?.stopAfterCurrent() }
                }
                BatchPhotoPreparationResult.Success("out_$uri", 1080, 1920, 50000L)
            }

            runner = BatchPhotoRunner(fake)
            val list = (1..10).map { "$it.jpg" }
            runner.initializeQueue(list)

            runner.start(PhotoQualityMode.STATUS_HD)

            val snapshot = runner.getSnapshot()
            assertEquals(4, callCount)
            assertEquals(4, BatchPhotoSummary.fromItems(snapshot.items).done)
            assertEquals(6, BatchPhotoSummary.fromItems(snapshot.items).skipped)
            (4..9).forEach { idx ->
                assertEquals(ItemState.Skipped, snapshot.items[idx].state)
                assertEquals(ItemSkipReason.UserStopped, snapshot.items[idx].skipReason)
            }
        }
    }

    @Test
    fun testNoFifthPreparerCallOccursWhenStoppedAt4() {
        runBlocking {
            val fake = FakePhotoPreparer()
            var callCount = 0
            var runner: BatchPhotoRunner? = null
            fake.customBehavior = { uri, _ ->
                callCount++
                if (callCount == 4) {
                    runBlocking { runner?.stopAfterCurrent() }
                }
                BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }

            runner = BatchPhotoRunner(fake)
            runner.initializeQueue((1..10).map { "$it.jpg" })
            runner.start(PhotoQualityMode.STATUS_HD)

            assertEquals(4, fake.preparedCalls.size)
        }
    }

    @Test
    fun testItems5To10BecomeSkippedUserStopped() {
        runBlocking {
            val fake = FakePhotoPreparer()
            var callCount = 0
            var runner: BatchPhotoRunner? = null
            fake.customBehavior = { uri, _ ->
                callCount++
                if (callCount == 3) {
                    runBlocking { runner?.stopAfterCurrent() }
                }
                BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }

            runner = BatchPhotoRunner(fake)
            runner.initializeQueue((1..6).map { "$it.jpg" })
            runner.start(PhotoQualityMode.STATUS_HD)

            val snapshot = runner.getSnapshot()
            assertEquals(ItemSkipReason.UserStopped, snapshot.items[3].skipReason)
            assertEquals(ItemSkipReason.UserStopped, snapshot.items[4].skipReason)
            assertEquals(ItemSkipReason.UserStopped, snapshot.items[5].skipReason)
        }
    }

    @Test
    fun testStopBetweenItemsClaimsNoNextItem() {
        runBlocking {
            val fake = FakePhotoPreparer()
            var runner: BatchPhotoRunner? = null
            runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg", "3.jpg"))

            fake.customBehavior = { uri, _ ->
                if (uri == "1.jpg") {
                    runBlocking { runner?.stopAfterCurrent() }
                }
                BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }

            runner.start(PhotoQualityMode.STATUS_HD)
            assertEquals(1, fake.preparedCalls.size)
            assertEquals("1.jpg", fake.preparedCalls.first())
        }
    }

    @Test
    fun testRepeatedStopReturnsAlreadyStopping() {
        runBlocking {
            val startedGate = CompletableDeferred<Unit>()
            val itemGate = CompletableDeferred<Unit>()
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                startedGate.complete(Unit)
                itemGate.await()
                BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))
            val dStart = async(Dispatchers.Default) { runner.start(PhotoQualityMode.STATUS_HD) }
            startedGate.await()

            val res1 = runner.stopAfterCurrent()
            val res2 = runner.stopAfterCurrent()

            assertEquals(StopResult.ACCEPTED, res1)
            assertEquals(StopResult.ALREADY_STOPPING, res2)

            itemGate.complete(Unit)
            dStart.await()
        }
    }

    // 7. Failure Policy & Canceled Policy (7 tests)
    @Test
    fun testUnsupportedItemIsSkippedAndLaterItemsContinue() {
        runBlocking {
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                if (uri == "2.jpg") {
                    BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.UnsupportedFormat)
                } else {
                    BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
                }
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg", "3.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            val summary = runner.getSnapshot()
            assertEquals(2, BatchPhotoSummary.fromItems(summary.items).done)
            assertEquals(1, BatchPhotoSummary.fromItems(summary.items).skipped)
            assertEquals(ItemState.Skipped, summary.items[1].state)
            assertEquals(ItemSkipReason.UnsupportedFormat, summary.items[1].skipReason)
        }
    }

    @Test
    fun testTooLargeItemIsSkippedAndLaterItemsContinue() {
        runBlocking {
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                if (uri == "2.jpg") {
                    BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SourceTooLarge)
                } else {
                    BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
                }
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg", "3.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            val summary = runner.getSnapshot()
            assertEquals(2, BatchPhotoSummary.fromItems(summary.items).done)
            assertEquals(1, BatchPhotoSummary.fromItems(summary.items).skipped)
            assertEquals(ItemState.Skipped, summary.items[1].state)
            assertEquals(ItemSkipReason.SourceTooLarge, summary.items[1].skipReason)
        }
    }

    @Test
    fun testUnavailableItemIsFailedAndLaterItemsContinue() {
        runBlocking {
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                if (uri == "2.jpg") {
                    BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SourceUnavailable)
                } else {
                    BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
                }
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg", "3.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            val summary = runner.getSnapshot()
            assertEquals(2, BatchPhotoSummary.fromItems(summary.items).done)
            assertEquals(1, BatchPhotoSummary.fromItems(summary.items).failed)
            assertEquals(ItemState.Failed, summary.items[1].state)
        }
    }

    @Test
    fun testSaveFailedIsFailedAndLaterItemsContinue() {
        runBlocking {
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                if (uri == "2.jpg") {
                    BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SaveFailed)
                } else {
                    BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
                }
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg", "3.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            val summary = runner.getSnapshot()
            assertEquals(2, BatchPhotoSummary.fromItems(summary.items).done)
            assertEquals(1, BatchPhotoSummary.fromItems(summary.items).failed)
            assertEquals(ItemState.Failed, summary.items[1].state)
        }
    }

    @Test
    fun testOutOfMemoryStopsTheBatch() {
        runBlocking {
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                if (uri == "2.jpg") {
                    BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.OutOfMemory)
                } else {
                    BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
                }
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg", "3.jpg", "4.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            val summary = runner.getSnapshot()
            assertEquals(2, fake.preparedCalls.size)
            assertEquals(1, BatchPhotoSummary.fromItems(summary.items).done)
            assertEquals(1, BatchPhotoSummary.fromItems(summary.items).failed)
            assertEquals(2, BatchPhotoSummary.fromItems(summary.items).skipped)
        }
    }

    @Test
    fun testOutOfMemoryMarksRemainingWaitingItemsSkippedFatalFailure() {
        runBlocking {
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                if (uri == "2.jpg") {
                    BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.OutOfMemory)
                } else {
                    BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
                }
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg", "3.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            val summary = runner.getSnapshot()
            assertEquals(ItemState.Skipped, summary.items[2].state)
            assertEquals(ItemSkipReason.FatalFailure, summary.items[2].skipReason)
        }
    }

    @Test
    fun testPreparerCanceledMakesCurrentItemFailedAndLaterItemsContinue() {
        runBlocking {
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                if (uri == "2.jpg") {
                    BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.Canceled)
                } else {
                    BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
                }
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg", "3.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            val snapshot = runner.getSnapshot()
            assertEquals(2, BatchPhotoSummary.fromItems(snapshot.items).done)
            assertEquals(1, BatchPhotoSummary.fromItems(snapshot.items).failed)
            assertEquals(ItemState.Failed, snapshot.items[1].state)
            assertEquals(PhotoProcessingFailure.Canceled, snapshot.items[1].failureCategory)
        }
    }

    // 8. Retry Policy (7 tests)
    @Test
    fun testRetrySelectsOnlyRetryEligibleFailedItems() {
        runBlocking {
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                when (uri) {
                    "2.jpg" -> BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SaveFailed)
                    "3.jpg" -> BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SourceUnavailable)
                    else -> BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
                }
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg", "3.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            fake.customBehavior = { uri, _ ->
                BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }

            val retryResult = runner.retryFailed()
            assertEquals(RetryResultCode.STARTED, retryResult.code)
            assertEquals(1, retryResult.retriedCount)

            val snapshot = runner.getSnapshot()
            assertEquals(2, BatchPhotoSummary.fromItems(snapshot.items).done)
            assertEquals(1, BatchPhotoSummary.fromItems(snapshot.items).failed)
        }
    }

    @Test
    fun testRetryPreservesOriginalOrder() {
        runBlocking {
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                if (uri == "2.jpg") BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SaveFailed)
                else BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg", "3.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            fake.customBehavior = { uri, _ -> BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000) }
            runner.retryFailed()

            val itemIds = runner.getSnapshot().items.map { it.sourceIdentifier }
            assertEquals(listOf("1.jpg", "2.jpg", "3.jpg"), itemIds)
        }
    }

    @Test
    fun testRetryPreservesOriginalMode() {
        runBlocking {
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                if (uri == "2.jpg") BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SaveFailed)
                else BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))
            runner.start(PhotoQualityMode.SMALLER_FILE)

            fake.customBehavior = { uri, _ -> BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000) }
            runner.retryFailed()

            assertEquals(PhotoQualityMode.SMALLER_FILE, runner.getSnapshot().lockedMode)
        }
    }

    @Test
    fun testDoneItemsAreNotRerun() {
        runBlocking {
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                if (uri == "2.jpg") BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SaveFailed)
                else BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            fake.preparedCalls.clear()
            fake.customBehavior = { uri, _ -> BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000) }
            runner.retryFailed()

            assertEquals(listOf("2.jpg"), fake.preparedCalls)
        }
    }

    @Test
    fun testSkippedItemsAreNotRerun() {
        runBlocking {
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                when (uri) {
                    "1.jpg" -> BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.UnsupportedFormat)
                    "2.jpg" -> BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SaveFailed)
                    else -> BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
                }
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            fake.preparedCalls.clear()
            fake.customBehavior = { uri, _ -> BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000) }
            runner.retryFailed()

            assertEquals(listOf("2.jpg"), fake.preparedCalls)
            assertEquals(ItemState.Skipped, runner.getSnapshot().items[0].state)
        }
    }

    @Test
    fun testUnavailableItemIsNotRetried() {
        runBlocking {
            val fake = FakePhotoPreparer()
            fake.customBehavior = { uri, _ ->
                BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SourceUnavailable)
            }

            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            val retryResult = runner.retryFailed()
            assertEquals(RetryResultCode.NO_ELIGIBLE_FAILURES, retryResult.code)
            assertEquals(0, retryResult.retriedCount)
        }
    }

    @Test
    fun testNoEligibleFailuresReturnsNoOp() {
        runBlocking {
            val fake = FakePhotoPreparer()
            val runner = BatchPhotoRunner(fake)
            runner.initializeQueue(listOf("1.jpg", "2.jpg"))
            runner.start(PhotoQualityMode.STATUS_HD)

            val retryResult = runner.retryFailed()
            assertEquals(RetryResultCode.NO_ELIGIBLE_FAILURES, retryResult.code)
            assertEquals(0, retryResult.retriedCount)
        }
    }

    // 9. Summary Derivation (3 tests)
    @Test
    fun testSummaryCountsDeriveCorrectlyFromMixedStates() {
        val items = listOf(
            BatchPhotoItem("1", "a", 0, BatchItemStatus.Done(BatchPhotoPreparationResult.Success("out.jpg", 10, 10, 10))),
            BatchPhotoItem("2", "b", 1, BatchItemStatus.Failed(PhotoProcessingFailure.SaveFailed)),
            BatchPhotoItem("3", "c", 2, BatchItemStatus.Skipped(ItemSkipReason.UnsupportedFormat)),
            BatchPhotoItem("4", "d", 3, BatchItemStatus.Waiting),
            BatchPhotoItem("5", "e", 4, BatchItemStatus.Processing)
        )
        val summary = BatchPhotoSummary.fromItems(items)

        assertEquals(5, summary.total)
        assertEquals(1, summary.waiting)
        assertEquals(1, summary.processing)
        assertEquals(1, summary.done)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.skipped)
        assertEquals(3, summary.handled)
        assertFalse(summary.isTerminal)
    }

    @Test
    fun testHandledEqualsDonePlusFailedPlusSkipped() {
        val items = listOf(
            BatchPhotoItem("1", "a", 0, BatchItemStatus.Done(BatchPhotoPreparationResult.Success("out.jpg", 10, 10, 10))),
            BatchPhotoItem("2", "b", 1, BatchItemStatus.Failed(PhotoProcessingFailure.SaveFailed)),
            BatchPhotoItem("3", "c", 2, BatchItemStatus.Skipped(ItemSkipReason.UserStopped))
        )
        val summary = BatchPhotoSummary.fromItems(items)

        assertEquals(summary.done + summary.failed + summary.skipped, summary.handled)
        assertEquals(3, summary.handled)
    }

    @Test
    fun testIsTerminalIsTrueOnlyWhenNoWaitingOrProcessingItemRemains() {
        val terminalItems = listOf(
            BatchPhotoItem("1", "a", 0, BatchItemStatus.Done(BatchPhotoPreparationResult.Success("out.jpg", 10, 10, 10))),
            BatchPhotoItem("2", "b", 1, BatchItemStatus.Failed(PhotoProcessingFailure.SaveFailed)),
            BatchPhotoItem("3", "c", 2, BatchItemStatus.Skipped(ItemSkipReason.UserStopped))
        )
        val terminalSummary = BatchPhotoSummary.fromItems(terminalItems)
        assertTrue(terminalSummary.isTerminal)

        val activeItems = listOf(
            BatchPhotoItem("1", "a", 0, BatchItemStatus.Done(BatchPhotoPreparationResult.Success("out.jpg", 10, 10, 10))),
            BatchPhotoItem("2", "b", 1, BatchItemStatus.Waiting)
        )
        val activeSummary = BatchPhotoSummary.fromItems(activeItems)
        assertFalse(activeSummary.isTerminal)
    }
}
