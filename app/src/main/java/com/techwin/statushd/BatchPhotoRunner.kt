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

    var onSnapshotChanged: ((BatchPhotoQueueSnapshot) -> Unit)? = null

    private fun notifySnapshot(snapshot: BatchPhotoQueueSnapshot) {
        try {
            onSnapshotChanged?.invoke(snapshot)
        } catch (_: Throwable) {}
    }

    suspend fun getSnapshot(): BatchPhotoQueueSnapshot = mutex.withLock {
        BatchPhotoQueueSnapshot(
            state = _batchState,
            lockedMode = _lockedMode,
            items = _items.toList()
        )
    }

    suspend fun initializeQueue(identifiers: List<String>): Boolean {
        var snapshotToNotify: BatchPhotoQueueSnapshot? = null
        val success = mutex.withLock {
            if (_batchState != BatchState.Draft && _batchState != BatchState.Finished) return@withLock false

            val policy = BatchPhotoSelectionPolicy.validateAndDeduplicate(identifiers)
            if (!policy.isValidForStart && identifiers.isNotEmpty()) return@withLock false

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
            snapshotToNotify = BatchPhotoQueueSnapshot(
                state = _batchState,
                lockedMode = _lockedMode,
                items = _items.toList()
            )
            true
        }

        snapshotToNotify?.let { notifySnapshot(it) }
        return success
    }

    suspend fun start(mode: PhotoQualityMode): Boolean {
        var snapshotToNotify: BatchPhotoQueueSnapshot? = null
        val canStart = mutex.withLock {
            if (_batchState != BatchState.Draft) false
            else if (_items.size < 2) false
            else {
                _batchState = BatchState.Running
                _lockedMode = mode
                snapshotToNotify = BatchPhotoQueueSnapshot(
                    state = _batchState,
                    lockedMode = _lockedMode,
                    items = _items.toList()
                )
                true
            }
        }

        snapshotToNotify?.let { notifySnapshot(it) }
        if (!canStart) return false

        runLoop()
        return true
    }

    suspend fun stopAfterCurrent(): StopResult {
        var snapshotToNotify: BatchPhotoQueueSnapshot? = null
        val result = mutex.withLock {
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
                    snapshotToNotify = BatchPhotoQueueSnapshot(
                        state = _batchState,
                        lockedMode = _lockedMode,
                        items = _items.toList()
                    )
                    StopResult.ACCEPTED
                }
                BatchState.Stopping -> StopResult.ALREADY_STOPPING
                else -> StopResult.NOT_RUNNING
            }
        }

        snapshotToNotify?.let { notifySnapshot(it) }
        return result
    }

    suspend fun retryFailed(): RetryResult {
        var eligibleCount = 0
        var snapshotToNotify: BatchPhotoQueueSnapshot? = null

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
            snapshotToNotify = BatchPhotoQueueSnapshot(
                state = _batchState,
                lockedMode = _lockedMode,
                items = _items.toList()
            )
            true
        }

        snapshotToNotify?.let { notifySnapshot(it) }
        if (!canStartRetry) return RetryResult(RetryResultCode.INVALID_STATE, 0)

        runLoop()
        return RetryResult(RetryResultCode.STARTED, eligibleCount)
    }

    private suspend fun claimNextItem(): Pair<Int, BatchPhotoItem>? {
        var finishedSnapshotToNotify: BatchPhotoQueueSnapshot? = null
        var claimedPair: Pair<Int, BatchPhotoItem>? = null
        var claimedSnapshotToNotify: BatchPhotoQueueSnapshot? = null

        mutex.withLock {
            if (_batchState != BatchState.Running) return null
            val index = _items.indexOfFirst { it.status is BatchItemStatus.Waiting }
            if (index == -1) {
                _batchState = BatchState.Finished
                finishedSnapshotToNotify = BatchPhotoQueueSnapshot(
                    state = _batchState,
                    lockedMode = _lockedMode,
                    items = _items.toList()
                )
                return@withLock null
            }
            val claimed = _items[index].copy(status = BatchItemStatus.Processing)
            _items[index] = claimed
            claimedPair = Pair(index, claimed)
            claimedSnapshotToNotify = BatchPhotoQueueSnapshot(
                state = _batchState,
                lockedMode = _lockedMode,
                items = _items.toList()
            )
        }

        if (finishedSnapshotToNotify != null) {
            notifySnapshot(finishedSnapshotToNotify!!)
            return null
        }

        if (claimedSnapshotToNotify != null) {
            notifySnapshot(claimedSnapshotToNotify!!)
        }

        return claimedPair
    }

    private suspend fun recordResultAndCheckContinue(
        index: Int,
        result: BatchPhotoPreparationResult
    ): Boolean {
        var snapshotToNotify: BatchPhotoQueueSnapshot? = null
        var shouldContinue = true

        mutex.withLock {
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
                            shouldContinue = false
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
                shouldContinue = false
            }

            snapshotToNotify = BatchPhotoQueueSnapshot(
                state = _batchState,
                lockedMode = _lockedMode,
                items = _items.toList()
            )
        }

        snapshotToNotify?.let { notifySnapshot(it) }
        return shouldContinue
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
