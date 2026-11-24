package com.techwin.statushd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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

    // Selection Policy Tests (1-6)
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

    // Invariant Tests
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

    // State Transitions & Concurrency Races (A, B, C)
    @Test
    fun testDraftToRunningToFinished() = runBlocking {
        val fake = FakePhotoPreparer()
        val runner = BatchPhotoRunner(fake)
        runner.initializeQueue(listOf("1.jpg", "2.jpg"))
        assertEquals(BatchState.Draft, runner.batchState)

        runner.start(PhotoQualityMode.STATUS_HD)
        assertEquals(BatchState.Finished, runner.batchState)
        assertEquals(2, runner.summary.done)
    }

    @Test
    fun testRaceA_ActiveItemCompletesStopRequestRacesNextClaim() = runBlocking {
        val fake = FakePhotoPreparer()
        var runner: BatchPhotoRunner? = null

        fake.customBehavior = { uri, _ ->
            if (uri == "1.jpg") {
                runner?.stopAfterCurrent()
            }
            BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
        }

        runner = BatchPhotoRunner(fake)
        runner.initializeQueue(listOf("1.jpg", "2.jpg", "3.jpg"))
        runner.start(PhotoQualityMode.STATUS_HD)

        assertEquals(BatchState.Finished, runner.batchState)
        assertEquals(1, fake.preparedCalls.size)
        assertEquals(listOf("1.jpg"), fake.preparedCalls)
        assertEquals(ItemState.Done, runner.items[0].state)
        assertEquals(ItemState.Skipped, runner.items[1].state)
        assertEquals(ItemState.Skipped, runner.items[2].state)
    }

    @Test
    fun testRaceB_ConcurrentStartCallsProcessedOncePerItem() = runBlocking {
        val fake = FakePhotoPreparer()
        fake.customBehavior = { uri, _ ->
            delay(20)
            BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
        }
        val runner = BatchPhotoRunner(fake)
        runner.initializeQueue(listOf("1.jpg", "2.jpg"))

        val d1 = async(Dispatchers.Default) { runner.start(PhotoQualityMode.STATUS_HD) }
        val d2 = async(Dispatchers.Default) { runner.start(PhotoQualityMode.STATUS_HD) }

        val results = awaitAll(d1, d2)

        assertTrue(results.contains(true))
        assertTrue(results.contains(false)) // Second concurrent start rejected
        assertEquals(2, fake.preparedCalls.size)
        assertEquals(listOf("1.jpg", "2.jpg"), fake.preparedCalls)
    }

    @Test
    fun testRaceC_ConcurrentRetryAndStopNoItemDuplicated() = runBlocking {
        val fake = FakePhotoPreparer()
        fake.customBehavior = { uri, _ ->
            if (uri == "2.jpg") BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SaveFailed)
            else BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
        }

        val runner = BatchPhotoRunner(fake)
        runner.initializeQueue(listOf("1.jpg", "2.jpg"))
        runner.start(PhotoQualityMode.STATUS_HD)

        fake.customBehavior = { uri, _ ->
            delay(20)
            BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
        }

        val dRetry = async(Dispatchers.Default) { runner.retryFailed() }
        delay(5)
        val dStop = async(Dispatchers.Default) { runner.stopAfterCurrent() }

        dRetry.await()
        dStop.await()

        assertEquals(BatchState.Finished, runner.batchState)
        assertEquals(1, fake.peakConcurrency.get())
    }

    @Test
    fun testIllegalTransitionsRejected() = runBlocking {
        val fake = FakePhotoPreparer()
        val runner = BatchPhotoRunner(fake)
        val started = runner.start(PhotoQualityMode.STATUS_HD)
        assertFalse(started)
        assertEquals(BatchState.Draft, runner.batchState)
    }

    @Test
    fun testModeCannotChangeAfterStart() = runBlocking {
        val fake = FakePhotoPreparer()
        val runner = BatchPhotoRunner(fake)
        runner.initializeQueue(listOf("1.jpg", "2.jpg"))
        runner.start(PhotoQualityMode.STATUS_HD)

        assertEquals(PhotoQualityMode.STATUS_HD, runner.lockedMode)
    }

    // Serialization (12-15)
    @Test
    fun testPeakFakePreparerConcurrencyIsExactlyOne() = runBlocking {
        val fake = FakePhotoPreparer()
        val runner = BatchPhotoRunner(fake)
        runner.initializeQueue((1..5).map { "$it.jpg" })
        runner.start(PhotoQualityMode.STATUS_HD)

        assertEquals(1, fake.peakConcurrency.get())
    }

    @Test
    fun testEachAcceptedItemCalledExactlyOnce() = runBlocking {
        val fake = FakePhotoPreparer()
        val runner = BatchPhotoRunner(fake)
        val list = listOf("a.jpg", "b.jpg", "c.jpg")
        runner.initializeQueue(list)
        runner.start(PhotoQualityMode.STATUS_HD)

        assertEquals(list, fake.preparedCalls)
    }

    @Test
    fun testOriginalQueueOrderPreserved() = runBlocking {
        val fake = FakePhotoPreparer()
        val runner = BatchPhotoRunner(fake)
        val list = listOf("z.jpg", "x.jpg", "y.jpg")
        runner.initializeQueue(list)
        runner.start(PhotoQualityMode.STATUS_HD)

        val sourceOrder = runner.items.map { it.sourceIdentifier }
        assertEquals(list, sourceOrder)
    }

    @Test
    fun testDoubleStartDoesNotDuplicateWork() = runBlocking {
        val fake = FakePhotoPreparer()
        val runner = BatchPhotoRunner(fake)
        runner.initializeQueue(listOf("1.jpg", "2.jpg"))
        runner.start(PhotoQualityMode.STATUS_HD)
        val firstCallsCount = fake.preparedCalls.size

        runner.start(PhotoQualityMode.STATUS_HD)
        assertEquals(firstCallsCount, fake.preparedCalls.size)
    }

    // Stop-after-current (16-20)
    @Test
    fun testStopDuringItem4Of10AllowsItem4ToSettleAndItems5To10BecomeSkipped() = runBlocking {
        val fake = FakePhotoPreparer()
        var callCount = 0
        var runner: BatchPhotoRunner? = null
        fake.customBehavior = { uri, _ ->
            callCount++
            if (callCount == 4) {
                runner?.stopAfterCurrent()
            }
            BatchPhotoPreparationResult.Success("out_$uri", 1080, 1920, 50000L)
        }

        runner = BatchPhotoRunner(fake)
        val list = (1..10).map { "$it.jpg" }
        runner.initializeQueue(list)

        runner.start(PhotoQualityMode.STATUS_HD)

        assertEquals(4, callCount)
        assertEquals(4, runner.summary.done)
        assertEquals(6, runner.summary.skipped)
        (4..9).forEach { idx ->
            assertEquals(ItemState.Skipped, runner.items[idx].state)
            assertEquals(ItemSkipReason.UserStopped, runner.items[idx].skipReason)
        }
    }

    @Test
    fun testNoFifthPreparerCallOccursWhenStoppedAt4() = runBlocking {
        val fake = FakePhotoPreparer()
        var callCount = 0
        var runner: BatchPhotoRunner? = null
        fake.customBehavior = { uri, _ ->
            callCount++
            if (callCount == 4) {
                runner?.stopAfterCurrent()
            }
            BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
        }

        runner = BatchPhotoRunner(fake)
        runner.initializeQueue((1..10).map { "$it.jpg" })
        runner.start(PhotoQualityMode.STATUS_HD)

        assertEquals(4, fake.preparedCalls.size)
    }

    @Test
    fun testItems5To10BecomeSkippedUserStopped() = runBlocking {
        val fake = FakePhotoPreparer()
        var callCount = 0
        var runner: BatchPhotoRunner? = null
        fake.customBehavior = { uri, _ ->
            callCount++
            if (callCount == 3) {
                runner?.stopAfterCurrent()
            }
            BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
        }

        runner = BatchPhotoRunner(fake)
        runner.initializeQueue((1..6).map { "$it.jpg" })
        runner.start(PhotoQualityMode.STATUS_HD)

        assertEquals(ItemSkipReason.UserStopped, runner.items[3].skipReason)
        assertEquals(ItemSkipReason.UserStopped, runner.items[4].skipReason)
        assertEquals(ItemSkipReason.UserStopped, runner.items[5].skipReason)
    }

    @Test
    fun testStopBetweenItemsClaimsNoNextItem() = runBlocking {
        val fake = FakePhotoPreparer()
        var runner: BatchPhotoRunner? = null
        runner = BatchPhotoRunner(fake)
        runner.initializeQueue(listOf("1.jpg", "2.jpg", "3.jpg"))

        fake.customBehavior = { uri, _ ->
            if (uri == "1.jpg") {
                runner.stopAfterCurrent()
            }
            BatchPhotoPreparationResult.Success("out_$uri", 100, 100, 1000)
        }

        runner.start(PhotoQualityMode.STATUS_HD)
        assertEquals(1, fake.preparedCalls.size)
        assertEquals("1.jpg", fake.preparedCalls.first())
    }

    @Test
    fun testRepeatedStopIsIdempotent() = runBlocking {
        val fake = FakePhotoPreparer()
        val runner = BatchPhotoRunner(fake)
        runner.initializeQueue(listOf("1.jpg", "2.jpg"))
        runner.start(PhotoQualityMode.STATUS_HD)

        val stop1 = runner.stopAfterCurrent()
        val stop2 = runner.stopAfterCurrent()

        assertFalse(stop1)
        assertFalse(stop2)
    }

    // Failures (21-26)
    @Test
    fun testUnsupportedItemIsSkippedAndLaterItemsContinue() = runBlocking {
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

        assertEquals(2, runner.summary.done)
        assertEquals(1, runner.summary.skipped)
        assertEquals(ItemState.Skipped, runner.items[1].state)
        assertEquals(ItemSkipReason.UnsupportedFormat, runner.items[1].skipReason)
    }

    @Test
    fun testTooLargeItemIsSkippedAndLaterItemsContinue() = runBlocking {
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

        assertEquals(2, runner.summary.done)
        assertEquals(1, runner.summary.skipped)
        assertEquals(ItemState.Skipped, runner.items[1].state)
        assertEquals(ItemSkipReason.SourceTooLarge, runner.items[1].skipReason)
    }

    @Test
    fun testUnavailableItemIsFailedAndLaterItemsContinue() = runBlocking {
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

        assertEquals(2, runner.summary.done)
        assertEquals(1, runner.summary.failed)
        assertEquals(ItemState.Failed, runner.items[1].state)
    }

    @Test
    fun testSaveFailedIsFailedAndLaterItemsContinue() = runBlocking {
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

        assertEquals(2, runner.summary.done)
        assertEquals(1, runner.summary.failed)
        assertEquals(ItemState.Failed, runner.items[1].state)
    }

    @Test
    fun testOutOfMemoryStopsTheBatch() = runBlocking {
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

        assertEquals(2, fake.preparedCalls.size)
        assertEquals(1, runner.summary.done)
        assertEquals(1, runner.summary.failed)
        assertEquals(2, runner.summary.skipped)
    }

    @Test
    fun testOutOfMemoryMarksRemainingWaitingItemsSkippedFatalFailure() = runBlocking {
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

        assertEquals(ItemState.Skipped, runner.items[2].state)
        assertEquals(ItemSkipReason.FatalFailure, runner.items[2].skipReason)
    }

    // Retry (27-33)
    @Test
    fun testRetrySelectsOnlyRetryEligibleFailedItems() = runBlocking {
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
        assertEquals(2, runner.summary.done)
        assertEquals(1, runner.summary.failed)
    }

    @Test
    fun testRetryPreservesOriginalOrder() = runBlocking {
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

        val itemIds = runner.items.map { it.sourceIdentifier }
        assertEquals(listOf("1.jpg", "2.jpg", "3.jpg"), itemIds)
    }

    @Test
    fun testRetryPreservesOriginalMode() = runBlocking {
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

        assertEquals(PhotoQualityMode.SMALLER_FILE, runner.lockedMode)
    }

    @Test
    fun testDoneItemsAreNotRerun() = runBlocking {
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

    @Test
    fun testSkippedItemsAreNotRerun() = runBlocking {
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
        assertEquals(ItemState.Skipped, runner.items[0].state)
    }

    @Test
    fun testUnavailableItemIsNotRetried() = runBlocking {
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

    @Test
    fun testNoEligibleFailuresReturnsNoOp() = runBlocking {
        val fake = FakePhotoPreparer()
        val runner = BatchPhotoRunner(fake)
        runner.initializeQueue(listOf("1.jpg", "2.jpg"))
        runner.start(PhotoQualityMode.STATUS_HD)

        val retryResult = runner.retryFailed()
        assertEquals(RetryResultCode.NO_ELIGIBLE_FAILURES, retryResult.code)
        assertEquals(0, retryResult.retriedCount)
    }

    // Summary (34-36)
    @Test
    fun testSummaryCountsDeriveCorrectlyFromMixedStates() = runBlocking {
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
    fun testHandledEqualsDonePlusFailedPlusSkipped() = runBlocking {
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
    fun testIsTerminalIsTrueOnlyWhenNoWaitingOrProcessingItemRemains() = runBlocking {
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
