package com.techwin.statushd

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

enum class PhotoQualityMode(val title: String, val quality: Int, val desc: String) {
    STATUS_HD("Status HD", 90, "High quality • Optimized size"),
    SMALLER_FILE("Smaller File", 80, "Standard quality • Reduced size")
}

data class PhotoUiState(
    val selectedPhotoUri: Uri? = null,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val selectedMode: PhotoQualityMode = PhotoQualityMode.STATUS_HD,
    val isProcessing: Boolean = false,
    val completedUri: Uri? = null,
    val error: String? = null
)

class PhotoViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PhotoUiState())
    val uiState: StateFlow<PhotoUiState> = _uiState.asStateFlow()

    private val processingMutex = Mutex()

    fun selectPhoto(uri: Uri, width: Int, height: Int, sizeBytes: Long) {
        _uiState.update {
            it.copy(
                selectedPhotoUri = uri,
                width = width,
                height = height,
                sizeBytes = sizeBytes,
                completedUri = null,
                error = null
            )
        }
    }

    fun setMode(mode: PhotoQualityMode) {
        _uiState.update { it.copy(selectedMode = mode) }
    }

    fun startProcessing() {
        viewModelScope.launch {
            if (!processingMutex.tryLock()) return@launch
            try {
                val currentState = _uiState.value
                val uri = currentState.selectedPhotoUri ?: return@launch

                _uiState.update { it.copy(isProcessing = true, error = null, completedUri = null) }

                val context = getApplication<Application>()
                val result = PhotoProcessor.processPhoto(context, uri, currentState.selectedMode.quality)

                when (result) {
                    is PhotoProcessor.Result.Success -> {
                        _uiState.update { it.copy(isProcessing = false, completedUri = result.uri) }
                    }
                    is PhotoProcessor.Result.Error -> {
                        _uiState.update { it.copy(isProcessing = false, error = result.message) }
                    }
                }
            } finally {
                processingMutex.unlock()
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun reset() {
        _uiState.update { PhotoUiState() }
    }
}
