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
}
