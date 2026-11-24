package com.techwin.statushd

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RetryResultCode {
    STARTED,
    NO_ELIGIBLE_FAILURES,
    INVALID_STATE
}

data class RetryResult(
    val code: RetryResultCode,
    val retriedCount: Int
)

enum class StopResult {
    ACCEPTED,
    ALREADY_STOPPING,
    NOT_RUNNING
}

data class BatchPhotoQueueSnapshot(
    val state: BatchState,
    val lockedMode: PhotoQualityMode?,
    val items: List<BatchPhotoItem>
)

class BatchPhotoRunner(
    private val preparer: PhotoPreparer
) {
    private val mutex = Mutex()

    private var _batchState: BatchState = BatchState.Draft
    private var _lockedMode: PhotoQualityMode? = null
    private val _items = mutableListOf<BatchPhotoItem>()

    suspend fun getSnapshot(): BatchPhotoQueueSnapshot = mutex.withLock {
        BatchPhotoQueueSnapshot(
            state = _batchState,
            lockedMode = _lockedMode,
            items = _items.toList()
        )
    }

    suspend fun initializeQueue(identifiers: List<String>): Boolean = mutex.withLock {
        if (_batchState != BatchState.Draft && _batchState != BatchState.Finished) return false

        val policy = BatchPhotoSelectionPolicy.validateAndDeduplicate(identifiers)
        if (!policy.isValidForStart) return false

        _items.clear()
        policy.acceptedIdentifiers.forEachIndexed { index, id ->
            _items.add(
                BatchPhotoItem(
                    id = "item_${index + 1}_$id",
                    sourceIdentifier = id,
                    originalOrderIndex = index
                )
            )
        }
        _batchState = BatchState.Draft
        _lockedMode = null
        return true
    }

    suspend fun start(mode: PhotoQualityMode): Boolean {
        val canStart = mutex.withLock {
            if (_batchState != BatchState.Draft) false
            else if (_items.size < 2) false
            else {
                _batchState = BatchState.Running
                _lockedMode = mode
                true
            }
        }

        if (!canStart) return false

        runLoop()
        return true
    }

    suspend fun stopAfterCurrent(): StopResult = mutex.withLock {
        when (_batchState) {
            BatchState.Running -> {
                _batchState = BatchState.Stopping
                _items.forEachIndexed { index, item ->
                    if (item.status is BatchItemStatus.Waiting) {
                        _items[index] = item.copy(
                            status = BatchItemStatus.Skipped(ItemSkipReason.UserStopped)
                        )
                    }
                }
                if (_items.none { it.status is BatchItemStatus.Processing }) {
                    _batchState = BatchState.Finished
                }
                StopResult.ACCEPTED
            }
            BatchState.Stopping -> StopResult.ALREADY_STOPPING
            else -> StopResult.NOT_RUNNING
        }
    }

    suspend fun retryFailed(): RetryResult {
        var eligibleCount = 0

        val canStartRetry = mutex.withLock {
            if (_batchState != BatchState.Finished) return RetryResult(RetryResultCode.INVALID_STATE, 0)
            val currentMode = _lockedMode ?: return RetryResult(RetryResultCode.INVALID_STATE, 0)

            _items.forEachIndexed { index, item ->
                if (item.status is BatchItemStatus.Failed && isRetryEligible(item.failureCategory)) {
                    _items[index] = item.copy(status = BatchItemStatus.Waiting)
                    eligibleCount++
                }
            }

            if (eligibleCount == 0) {
                return RetryResult(RetryResultCode.NO_ELIGIBLE_FAILURES, 0)
            }

            _batchState = BatchState.Running
            true
        }

        if (!canStartRetry) return RetryResult(RetryResultCode.INVALID_STATE, 0)

        runLoop()
        return RetryResult(RetryResultCode.STARTED, eligibleCount)
    }

    private suspend fun claimNextItem(): Pair<Int, BatchPhotoItem>? = mutex.withLock {
        if (_batchState != BatchState.Running) return null
        val index = _items.indexOfFirst { it.status is BatchItemStatus.Waiting }
        if (index == -1) {
            _batchState = BatchState.Finished
            return null
        }
        val claimed = _items[index].copy(status = BatchItemStatus.Processing)
        _items[index] = claimed
        return Pair(index, claimed)
    }

    private suspend fun recordResultAndCheckContinue(
        index: Int,
        result: BatchPhotoPreparationResult
    ): Boolean = mutex.withLock {
        when (result) {
            is BatchPhotoPreparationResult.Success -> {
                _items[index] = _items[index].copy(
                    status = BatchItemStatus.Done(result)
                )
            }
            is BatchPhotoPreparationResult.Failure -> {
                when (result.category) {
                    PhotoProcessingFailure.UnsupportedFormat -> {
                        _items[index] = _items[index].copy(
                            status = BatchItemStatus.Skipped(ItemSkipReason.UnsupportedFormat)
                        )
                    }
                    PhotoProcessingFailure.SourceTooLarge -> {
                        _items[index] = _items[index].copy(
                            status = BatchItemStatus.Skipped(ItemSkipReason.SourceTooLarge)
                        )
                    }
                    PhotoProcessingFailure.OutOfMemory -> {
                        _items[index] = _items[index].copy(
                            status = BatchItemStatus.Failed(result.category)
                        )
                        _items.forEachIndexed { idx, itm ->
                            if (itm.status is BatchItemStatus.Waiting) {
                                _items[idx] = itm.copy(
                                    status = BatchItemStatus.Skipped(ItemSkipReason.FatalFailure)
                                )
                            }
                        }
                        _batchState = BatchState.Finished
                        return false
                    }
                    else -> {
                        _items[index] = _items[index].copy(
                            status = BatchItemStatus.Failed(result.category)
                        )
                    }
                }
            }
        }

        if (_batchState == BatchState.Stopping) {
            _items.forEachIndexed { idx, itm ->
                if (itm.status is BatchItemStatus.Waiting) {
                    _items[idx] = itm.copy(
                        status = BatchItemStatus.Skipped(ItemSkipReason.UserStopped)
                    )
                }
            }
            _batchState = BatchState.Finished
            return false
        }

        return true
    }

    private suspend fun runLoop() {
        while (true) {
            val claim = claimNextItem() ?: break
            val index = claim.first
            val item = claim.second

            val mode = mutex.withLock { _lockedMode } ?: break

            val prepResult = preparer.prepare(
                sourceUri = item.sourceIdentifier,
                mode = mode,
                destination = PhotoDestination.Batch
            )

            val shouldContinue = recordResultAndCheckContinue(index, prepResult)
            if (!shouldContinue) break
        }
    }

    private fun isRetryEligible(failure: PhotoProcessingFailure?): Boolean {
        return when (failure) {
            PhotoProcessingFailure.SaveFailed,
            is PhotoProcessingFailure.ProcessingFailed -> true
            else -> false
        }
    }
}
