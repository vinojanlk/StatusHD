package com.techwin.statushd

sealed interface PhotoProcessingFailure {
    object UnsupportedFormat : PhotoProcessingFailure
    object SourceUnavailable : PhotoProcessingFailure
    object SourceTooLarge : PhotoProcessingFailure
    object OutOfMemory : PhotoProcessingFailure
    object SaveFailed : PhotoProcessingFailure
    data class ProcessingFailed(val reason: String? = null) : PhotoProcessingFailure
    object Canceled : PhotoProcessingFailure
}
