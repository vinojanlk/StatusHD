package com.techwin.statushd

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class QualityMode(val title: String, val bitRateKbps: Int, val splitTime: Int, val maxW: Int, val maxH: Int, val desc: String) {
    FULL_HD("Full HD Detail", 3600, 30, 1080, 1920, "1080p • 30s split • High Quality"),
    HD_LONG("HD Longer", 1800, 60, 720, 1280, "720p • 60s split • Standard")
}

data class UiState(
    val selectedVideoUri: Uri? = null,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val bitrateKbps: Int = 0,
    val isPortrait: Boolean = true,
    val selectedMode: QualityMode = QualityMode.FULL_HD,
    val isProcessing: Boolean = false,
    val progress: Int = 0,
    val completedUris: List<Uri> = emptyList(),
    val error: String? = null
)

class VideoViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val workManager = WorkManager.getInstance(application)

    init {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow("status_video_transcode").collect { workInfos ->
                val workInfo = workInfos.firstOrNull() ?: return@collect
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getInt("progress", 0)
                        _uiState.update { it.copy(isProcessing = true, progress = progress, error = null) }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val urisStrArray = workInfo.outputData.getStringArray("savedUris")
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                progress = 100,
                                completedUris = urisStrArray?.map { str -> Uri.parse(str) } ?: emptyList()
                            )
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val error = workInfo.outputData.getString("error") ?: "Processing failed"
                        _uiState.update { it.copy(isProcessing = false, error = error) }
                    }
                    WorkInfo.State.CANCELLED -> {
                        _uiState.update { it.copy(isProcessing = false, error = "Cancelled by user") }
                    }
                    else -> {}
                }
            }
        }
    }

    fun selectVideo(uri: Uri, duration: Long, width: Int, height: Int, bitrateKbps: Int) {
        val isPortrait = height >= width
        _uiState.update {
            it.copy(
                selectedVideoUri = uri,
                durationMs = duration,
                width = width,
                height = height,
                bitrateKbps = bitrateKbps,
                isPortrait = isPortrait,
                completedUris = emptyList(),
                error = null,
                progress = 0
            )
        }
    }

    fun setMode(mode: QualityMode) {
        _uiState.update { it.copy(selectedMode = mode) }
    }

    fun startProcessing() {
        val currentState = _uiState.value
        val uri = currentState.selectedVideoUri ?: return

        _uiState.update { it.copy(isProcessing = true, progress = 0, error = null, completedUris = emptyList()) }

        val preserveOriginal = currentState.durationMs <= currentState.selectedMode.splitTime * 1000L &&
                               currentState.bitrateKbps > 0 && currentState.bitrateKbps <= currentState.selectedMode.bitRateKbps &&
                               currentState.width <= (if (currentState.isPortrait) currentState.selectedMode.maxW else currentState.selectedMode.maxH) &&
                               currentState.height <= (if (currentState.isPortrait) currentState.selectedMode.maxH else currentState.selectedMode.maxW)

        val data = Data.Builder()
            .putString("inputUri", uri.toString())
            .putLong("durationMs", currentState.durationMs)
            .putBoolean("isPortrait", currentState.isPortrait)
            .putString("modeName", currentState.selectedMode.name)
            .putInt("bitrateKbps", currentState.selectedMode.bitRateKbps)
            .putInt("splitTimeSec", currentState.selectedMode.splitTime)
            .putBoolean("preserveOriginal", preserveOriginal)
            .build()

        val request = OneTimeWorkRequestBuilder<VideoWorker>()
            .setInputData(data)
            .build()

        workManager.enqueueUniqueWork("status_video_transcode", ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelProcessing() {
        workManager.cancelUniqueWork("status_video_transcode")
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
