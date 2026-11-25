package com.techwin.statushd

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PhotoUiState {
    object Idle : PhotoUiState
    data class Selected(
        val uri: Uri,
        val fileName: String,
        val inputWidth: Int,
        val inputHeight: Int,
        val inputSize: Long,
        val mode: PhotoQualityMode = PhotoQualityMode.STATUS_HD
    ) : PhotoUiState
    data class Processing(val progress: Int) : PhotoUiState
    object Saving : PhotoUiState
    object Canceling : PhotoUiState
    data class Saved(
        val outputUri: Uri,
        val outputWidth: Int,
        val outputHeight: Int,
        val outputSize: Long
    ) : PhotoUiState
    data class Error(val message: String) : PhotoUiState
}

enum class PhotoQualityMode(val title: String, val desc: String, val quality: Int) {
    STATUS_HD("Status HD", "Fit within 1080x1920 (Portrait/Square) or 1920x1080 (Landscape), Quality 90", 90),
    SMALLER_FILE("Smaller File", "Same dimensions, Quality 80", 80)
}

class PhotoViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<PhotoUiState>(PhotoUiState.Idle)
    val uiState: StateFlow<PhotoUiState> = _uiState.asStateFlow()

    private var currentJob: Job? = null
    private var lastSelectedState: PhotoUiState.Selected? = null

    private val processor = PhotoProcessor(application)

    fun selectPhoto(uri: Uri) {
        if (currentJob?.isActive == true) return

        viewModelScope.launch(Dispatchers.IO) {
            when (val result = processor.inspectSource(uri)) {
                is PhotoInspectionResult.Success -> {
                    val selectedState = PhotoUiState.Selected(
                        uri = result.uri,
                        fileName = result.fileName,
                        inputWidth = result.width,
                        inputHeight = result.height,
                        inputSize = result.size
                    )
                    lastSelectedState = selectedState
                    _uiState.value = selectedState
                }
                is PhotoInspectionResult.Failure -> {
                    _uiState.value = PhotoUiState.Error(PhotoProcessingUiMapper.mapFailureToUserMessage(result.failure))
                }
            }
        }
    }

    fun setMode(mode: PhotoQualityMode) {
        if (currentJob?.isActive == true) return
        val currentState = _uiState.value
        if (currentState is PhotoUiState.Selected) {
            val updated = currentState.copy(mode = mode)
            lastSelectedState = updated
            _uiState.value = updated
        }
    }

    fun startProcessing() {
        val currentState = _uiState.value
        if (currentState !is PhotoUiState.Selected) return
        if (!PhotoStateReducer.canStartProcessing(currentState, currentJob?.isActive == true)) return

        _uiState.value = PhotoUiState.Processing(0)

        val uri = currentState.uri
        val mode = currentState.mode

        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = processor.preparePhoto(
                    sourceUri = uri,
                    mode = mode,
                    destination = PhotoDestination.Single,
                    onProgress = { progress ->
                        if (progress >= 70) {
                            _uiState.value = PhotoUiState.Saving
                        } else {
                            _uiState.value = PhotoUiState.Processing(progress)
                        }
                    }
                )

                when (result) {
                    is PhotoProcessingResult.Success -> {
                        _uiState.value = PhotoUiState.Saved(
                            outputUri = result.outputUri,
                            outputWidth = result.outputWidth,
                            outputHeight = result.outputHeight,
                            outputSize = result.outputSize
                        )
                    }
                    is PhotoProcessingResult.Failure -> {
                        if (result.failure != PhotoProcessingFailure.Canceled) {
                            _uiState.value = PhotoUiState.Error(PhotoProcessingUiMapper.mapFailureToUserMessage(result.failure))
                        }
                    }
                }
            } finally {
                if (currentJob == coroutineContext[Job]) {
                    currentJob = null
                }
            }
        }
        currentJob = job
    }

    fun cancelProcessing() {
        val jobToCancel = currentJob ?: return
        if (!jobToCancel.isActive) return

        val currentState = _uiState.value
        _uiState.value = PhotoStateReducer.onCancelRequested(currentState)

        val previousSelected = lastSelectedState

        viewModelScope.launch {
            try {
                jobToCancel.cancelAndJoin()
            } finally {
                if (currentJob == jobToCancel) {
                    currentJob = null
                }
                _uiState.value = PhotoStateReducer.onCancelCompleted(previousSelected)
            }
        }
    }

    fun reset() {
        if (currentJob?.isActive == true) return
        _uiState.value = PhotoUiState.Idle
        lastSelectedState = null
    }

    fun clearError() {
        val currentState = _uiState.value
        if (currentState is PhotoUiState.Error) {
            _uiState.value = PhotoUiState.Idle
        }
    }
}
