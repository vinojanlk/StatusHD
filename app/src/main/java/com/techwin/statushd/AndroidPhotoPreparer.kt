package com.techwin.statushd

import android.net.Uri

class AndroidPhotoPreparer(private val processor: PhotoProcessor) : PhotoPreparer {
    override suspend fun prepare(
        sourceUri: String,
        mode: PhotoQualityMode,
        destination: PhotoDestination
    ): BatchPhotoPreparationResult {
        val uri = Uri.parse(sourceUri)
        return when (val result = processor.preparePhoto(uri, mode, destination)) {
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
