package com.techwin.statushd

import android.net.Uri

sealed interface PhotoProcessingResult {
    data class Success(
        val outputUri: Uri,
        val outputWidth: Int,
        val outputHeight: Int,
        val outputSize: Long
    ) : PhotoProcessingResult

    data class Failure(
        val failure: PhotoProcessingFailure
    ) : PhotoProcessingResult
}
