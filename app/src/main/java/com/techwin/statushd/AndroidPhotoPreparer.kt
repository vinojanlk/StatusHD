package com.techwin.statushd

import android.net.Uri

class AndroidPhotoPreparer(private val processor: PhotoProcessor) : PhotoPreparer {
    override suspend fun prepare(
        sourceUri: String,
        mode: PhotoQualityMode,
        destination: PhotoDestination
    ): BatchPhotoPreparationResult {
        if (sourceUri.isBlank()) {
            return BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SourceUnavailable)
        }

        val parsedUri = try {
            Uri.parse(sourceUri)
        } catch (_: Exception) {
            return BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SourceUnavailable)
        }

        val scheme = parsedUri.scheme
        if (scheme == null || !scheme.equals("content", ignoreCase = true)) {
            return BatchPhotoPreparationResult.Failure(PhotoProcessingFailure.SourceUnavailable)
        }

        return when (val result = processor.preparePhoto(parsedUri, mode, PhotoDestination.Batch)) {
            is PhotoProcessingResult.Success -> BatchPhotoPreparationResult.Success(
                outputIdentifier = result.outputUri.toString(),
                outputWidth = result.outputWidth,
                outputHeight = result.outputHeight,
                outputSize = result.outputSize
            )
            is PhotoProcessingResult.Failure -> BatchPhotoPreparationResult.Failure(result.failure)
        }
    }
}
