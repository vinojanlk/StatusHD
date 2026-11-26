package com.techwin.statushd

enum class BatchState {
    Draft,
    Running,
    Stopping,
    Finished
}

enum class ItemState {
    Waiting,
    Processing,
    Done,
    Failed,
    Skipped
}

enum class ItemSkipReason {
    None,
    UserStopped,
    FatalFailure,
    UnsupportedFormat,
    SourceTooLarge
}

sealed interface BatchItemStatus {
    object Waiting : BatchItemStatus
    object Processing : BatchItemStatus
    data class Done(val outputData: BatchPhotoPreparationResult.Success) : BatchItemStatus
    data class Failed(val failureCategory: PhotoProcessingFailure) : BatchItemStatus
    data class Skipped(val skipReason: ItemSkipReason) : BatchItemStatus
}

data class BatchPhotoItem(
    val id: String,
    val sourceIdentifier: String,
    val originalOrderIndex: Int,
    val status: BatchItemStatus = BatchItemStatus.Waiting
) {
    val state: ItemState
        get() = when (status) {
            BatchItemStatus.Waiting -> ItemState.Waiting
            BatchItemStatus.Processing -> ItemState.Processing
            is BatchItemStatus.Done -> ItemState.Done
            is BatchItemStatus.Failed -> ItemState.Failed
            is BatchItemStatus.Skipped -> ItemState.Skipped
        }

    val failureCategory: PhotoProcessingFailure?
        get() = (status as? BatchItemStatus.Failed)?.failureCategory

    val skipReason: ItemSkipReason
        get() = (status as? BatchItemStatus.Skipped)?.skipReason ?: ItemSkipReason.None

    val outputData: BatchPhotoPreparationResult.Success?
        get() = (status as? BatchItemStatus.Done)?.outputData
}
