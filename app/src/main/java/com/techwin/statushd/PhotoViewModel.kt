package com.techwin.statushd

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.techwin.statushd.storage.AndroidSourceSizeProvider
import com.techwin.statushd.storage.AvailableStorageProvider
import com.techwin.statushd.storage.PhotoEstimateInput
import com.techwin.statushd.storage.SourceSizeProvider
import com.techwin.statushd.storage.StorageEstimatePolicy
import com.techwin.statushd.storage.StorageLowWarning
import com.techwin.statushd.storage.StoragePreflightChecker
import com.techwin.statushd.storage.StoragePreflightGate
import com.techwin.statushd.storage.StoragePreflightPrompt
import com.techwin.statushd.storage.StoragePreflightTestHooks
import kotlinx.coroutines.CoroutineDispatcher
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

interface PhotoProcessorGateway {
    suspend fun inspectSource(uri: Uri): PhotoInspectionResult
    suspend fun preparePhoto(
        sourceUri: Uri,
        mode: PhotoQualityMode,
        destination: PhotoDestination,
        onProgress: (Int) -> Unit
    ): PhotoProcessingResult
}

private class DefaultPhotoProcessorGateway(
    private val processor: PhotoProcessor
) : PhotoProcessorGateway {
    override suspend fun inspectSource(uri: Uri): PhotoInspectionResult =
        processor.inspectSource(uri)

    override suspend fun preparePhoto(
        sourceUri: Uri,
        mode: PhotoQualityMode,
        destination: PhotoDestination,
        onProgress: (Int) -> Unit
    ): PhotoProcessingResult =
        processor.preparePhoto(
            sourceUri = sourceUri,
            mode = mode,
            destination = destination,
            onProgress = onProgress
        )
}

class PhotoViewModel @JvmOverloads constructor(
    application: Application,
    availableStorageProvider: AvailableStorageProvider = StoragePreflightTestHooks.resolveStorageProvider(application),
    private val sourceSizeProvider: SourceSizeProvider = AndroidSourceSizeProvider(application),
    private val processorGateway: PhotoProcessorGateway = DefaultPhotoProcessorGateway(PhotoProcessor(application)),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<PhotoUiState>(PhotoUiState.Idle)
    val uiState: StateFlow<PhotoUiState> = _uiState.asStateFlow()

    private val preflightGate = StoragePreflightGate(
        scope = viewModelScope,
        storagePreflightChecker = StoragePreflightChecker(availableStorageProvider)
    )

    val preflightPrompt: StateFlow<StoragePreflightPrompt?> = preflightGate.prompt
    val isCheckingStorage: StateFlow<Boolean> = preflightGate.isChecking
    val lowStorageWarning: StateFlow<StorageLowWarning?> = preflightGate.lowStorageWarning

    private var currentJob: Job? = null
    private var lastSelectedState: PhotoUiState.Selected? = null

    fun selectPhoto(uri: Uri) {
        if (currentJob?.isActive == true || preflightGate.isChecking.value) return
        preflightGate.clearAll()

        viewModelScope.launch(ioDispatcher) {
            when (val result = processorGateway.inspectSource(uri)) {
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
        if (currentJob?.isActive == true || preflightGate.isChecking.value) return
        val currentState = _uiState.value
        if (currentState is PhotoUiState.Selected) {
            preflightGate.dismissPrompt()
            val updated = currentState.copy(mode = mode)
            lastSelectedState = updated
            _uiState.value = updated
        }
    }

    fun startProcessing() {
        val currentState = _uiState.value
        if (currentState !is PhotoUiState.Selected) return
        if (!PhotoStateReducer.canStartProcessing(currentState, currentJob?.isActive == true)) return

        preflightGate.requestStart(
            canStart = {
                val latest = _uiState.value
                latest is PhotoUiState.Selected &&
                    PhotoStateReducer.canStartProcessing(latest, currentJob?.isActive == true)
            },
            estimateRequired = {
                val latest = _uiState.value as? PhotoUiState.Selected ?: currentState
                val queriedSize = sourceSizeProvider.querySizeBytes(latest.uri.toString())
                val effectiveSourceSize = queriedSize ?: latest.inputSize.takeIf { it > 0L }
                StorageEstimatePolicy.estimateSinglePhoto(
                    input = PhotoEstimateInput(
                        sourceSizeBytes = effectiveSourceSize,
                        width = latest.inputWidth,
                        height = latest.inputHeight
                    ),
                    mode = latest.mode
                )
            },
            onProceed = {
                val latest = _uiState.value as? PhotoUiState.Selected ?: return@requestStart
                launchPhotoProcessing(latest)
            }
        )
    }

    fun continueAfterUnavailableStorage() {
        preflightGate.continueAfterUnavailable()
    }

    fun dismissStoragePrompt() {
        preflightGate.dismissPrompt()
    }

    private fun launchPhotoProcessing(selectedState: PhotoUiState.Selected) {
        if (!PhotoStateReducer.canStartProcessing(selectedState, currentJob?.isActive == true)) return
        _uiState.value = PhotoUiState.Processing(0)

        val uri = selectedState.uri
        val mode = selectedState.mode

        val job = viewModelScope.launch(ioDispatcher) {
            try {
                val result = processorGateway.preparePhoto(
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
                        preflightGate.clearWarning()
                        _uiState.value = PhotoUiState.Saved(
                            outputUri = result.outputUri,
                            outputWidth = result.outputWidth,
                            outputHeight = result.outputHeight,
                            outputSize = result.outputSize
                        )
                    }
                    is PhotoProcessingResult.Failure -> {
                        preflightGate.clearWarning()
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
                preflightGate.clearWarning()
                _uiState.value = PhotoStateReducer.onCancelCompleted(previousSelected)
            }
        }
    }

    fun reset() {
        if (currentJob?.isActive == true) return
        preflightGate.clearAll()
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
