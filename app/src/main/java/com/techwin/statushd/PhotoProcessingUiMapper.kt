package com.techwin.statushd

object PhotoProcessingUiMapper {
    fun mapFailureToUserMessage(failure: PhotoProcessingFailure): String {
        return when (failure) {
            PhotoProcessingFailure.UnsupportedFormat -> "This version currently supports JPEG photos only."
            PhotoProcessingFailure.SourceTooLarge -> "Image dimensions exceed supported limit."
            PhotoProcessingFailure.SourceUnavailable -> "Source file unavailable or unreadable."
            PhotoProcessingFailure.OutOfMemory -> "Not enough memory to process image."
            PhotoProcessingFailure.SaveFailed -> "Failed to save photo to MediaStore."
            is PhotoProcessingFailure.ProcessingFailed, PhotoProcessingFailure.Canceled -> "Photo processing failed."
        }
    }
}
