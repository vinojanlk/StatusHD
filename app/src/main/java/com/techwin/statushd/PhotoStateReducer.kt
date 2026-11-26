package com.techwin.statushd

object PhotoStateReducer {

    fun onCancelRequested(currentState: PhotoUiState): PhotoUiState {
        return when (currentState) {
            is PhotoUiState.Processing, PhotoUiState.Saving -> PhotoUiState.Canceling
            else -> currentState
        }
    }

    fun onCancelCompleted(selectedState: PhotoUiState.Selected?): PhotoUiState {
        return selectedState ?: PhotoUiState.Idle
    }

    fun canStartPhotoProcessing(isSelected: Boolean, isProcessing: Boolean, isJobActive: Boolean): Boolean {
        return isSelected && !isProcessing && !isJobActive
    }

    fun canStartProcessing(currentState: PhotoUiState, isJobActive: Boolean): Boolean {
        val isSelected = currentState is PhotoUiState.Selected
        val isProcessing = currentState is PhotoUiState.Processing || currentState is PhotoUiState.Saving || currentState is PhotoUiState.Canceling
        return canStartPhotoProcessing(isSelected, isProcessing, isJobActive)
    }
}
