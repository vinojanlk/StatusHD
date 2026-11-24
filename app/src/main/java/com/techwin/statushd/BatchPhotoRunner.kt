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

class BatchPhotoRunner(
    private val preparer: PhotoPreparer
) {
    private val mutex = Mutex()

    var batchState: BatchState = BatchState.Draft
        private set

    var lockedMode: PhotoQualityMode? = null
        private set

    private val _items = mutableListOf<BatchPhotoItem>()
    val items: List<BatchPhotoItem>
        get() = synchronized(_items) { _items.toList() }

    val summary: BatchPhotoSummary
        get() = synchronized(_items) { BatchPhotoSummary.fromItems(_items) }

    fun initializeQueue(identifiers: List<String>): Boolean {
        if (batchState != BatchState.Draft && batchState != BatchState.Finished) return false
        val policy = BatchPhotoSelectionPolicy.validateAndDeduplicate(identifiers)
        if (!policy.isValidForStart) return false

        synchronized(_items) {
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
        }
        batchState = BatchState.Draft
        lockedMode = null
        return true
    }

    suspend fun start(mode: PhotoQualityMode): Boolean {
        val canStart = mutex.withLock {
            if (batchState != BatchState.Draft && batchState != BatchState.Finished) false
            else {
                val count = synchronized(_items) { _items.size }
                if (count < 2) false
                else {
                    batchState = BatchState.Running
                    lockedMode = mode
                    true
                }
            }
        }

        if (!canStart) return false

        runLoop()
        return true
    }

    suspend fun stopAfterCurrent(): Boolean = mutex.withLock {
        if (batchState != BatchState.Running) return false

        batchState = BatchState.Stopping

        synchronized(_items) {
            _items.forEachIndexed { index, item ->
                if (item.status is BatchItemStatus.Waiting) {
                    _items[index] = item.copy(
                        status = BatchItemStatus.Skipped(ItemSkipReason.UserStopped)
                    )
                }
            }
            if (_items.none { it.status is BatchItemStatus.Processing }) {
                batchState = BatchState.Finished
            }
        }
        return true
    }

    suspend fun retryFailed(): RetryResult {
        var eligibleCount = 0

        val canStartRetry = mutex.withLock {
            if (batchState != BatchState.Finished) return RetryResult(RetryResultCode.INVALID_STATE, 0)
            val currentMode = lockedMode ?: return RetryResult(RetryResultCode.INVALID_STATE, 0)

            synchronized(_items) {
                _items.forEachIndexed { index, item ->
                    if (item.status is BatchItemStatus.Failed && isRetryEligible(item.failureCategory)) {
                        _items[index] = item.copy(status = BatchItemStatus.Waiting)
                        eligibleCount++
                    }
                }
            }

            if (eligibleCount == 0) {
                return RetryResult(RetryResultCode.NO_ELIGIBLE_FAILURES, 0)
            }

            batchState = BatchState.Running
            true
        }

        if (!canStartRetry) return RetryResult(RetryResultCode.INVALID_STATE, 0)

        runLoop()
        return RetryResult(RetryResultCode.STARTED, eligibleCount)
    }

    private suspend fun claimNextItem(): Pair<Int, BatchPhotoItem>? = mutex.withLock {
        if (batchState != BatchState.Running) return null
        val index = synchronized(_items) { _items.indexOfFirst { it.status is BatchItemStatus.Waiting } }
        if (index == -1) {
            batchState = BatchState.Finished
            return null
        }
        val item = synchronized(_items) {
            val claimed = _items[index].copy(status = BatchItemStatus.Processing)
            _items[index] = claimed
            claimed
        }
        return Pair(index, item)
    }

    private suspend fun recordResultAndCheckContinue(
        index: Int,
        result: BatchPhotoPreparationResult
    ): Boolean = mutex.withLock {
        synchronized(_items) {
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
                            batchState = BatchState.Finished
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

            if (batchState == BatchState.Stopping) {
                _items.forEachIndexed { idx, itm ->
                    if (itm.status is BatchItemStatus.Waiting) {
                        _items[idx] = itm.copy(
                            status = BatchItemStatus.Skipped(ItemSkipReason.UserStopped)
                        )
                    }
                }
                batchState = BatchState.Finished
                return false
            }
        }
        return true
    }

    private suspend fun runLoop() {
        while (true) {
            val claim = claimNextItem() ?: break
            val index = claim.first
            val item = claim.second
            val mode = lockedMode ?: break

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
