package com.techwin.statushd

sealed interface BatchPhotoPreparationResult {
    data class Success(
        val outputIdentifier: String,
        val outputWidth: Int,
        val outputHeight: Int,
        val outputSize: Long
    ) : BatchPhotoPreparationResult

    data class Failure(
        val category: PhotoProcessingFailure
    ) : BatchPhotoPreparationResult
}

interface PhotoPreparer {
    suspend fun prepare(
        sourceUri: String,
        mode: PhotoQualityMode,
        destination: PhotoDestination
    ): BatchPhotoPreparationResult
}
