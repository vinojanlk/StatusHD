package com.techwin.statushd

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.techwin.statushd.storage.AndroidSourceSizeProvider
import com.techwin.statushd.storage.AvailableStorageProvider
import com.techwin.statushd.storage.SourceSizeProvider
import com.techwin.statushd.storage.StorageEstimatePolicy
import com.techwin.statushd.storage.StorageLowWarning
import com.techwin.statushd.storage.StoragePreflightChecker
import com.techwin.statushd.storage.StoragePreflightGate
import com.techwin.statushd.storage.StoragePreflightPrompt
import com.techwin.statushd.storage.StoragePreflightTestHooks
import com.techwin.statushd.storage.VideoEstimateInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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

interface VideoWorkScheduler {
    val workInfosFlow: Flow<List<WorkInfo>>
    fun enqueueUniqueWork(
        inputUri: Uri,
        durationMs: Long,
        isPortrait: Boolean,
        mode: QualityMode,
        preserveOriginal: Boolean
    )
    fun cancelUniqueWork()
}

private class WorkManagerVideoWorkScheduler(
    private val workManager: WorkManager
) : VideoWorkScheduler {
    override val workInfosFlow: Flow<List<WorkInfo>>
        get() = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)

    override fun enqueueUniqueWork(
        inputUri: Uri,
        durationMs: Long,
        isPortrait: Boolean,
        mode: QualityMode,
        preserveOriginal: Boolean
    ) {
        val data = Data.Builder()
            .putString("inputUri", inputUri.toString())
            .putLong("durationMs", durationMs)
            .putBoolean("isPortrait", isPortrait)
            .putString("modeName", mode.name)
            .putInt("bitrateKbps", mode.bitRateKbps)
            .putInt("splitTimeSec", mode.splitTime)
            .putBoolean("preserveOriginal", preserveOriginal)
            .build()

        val request = OneTimeWorkRequestBuilder<VideoWorker>()
            .setInputData(data)
            .build()

        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancelUniqueWork() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "status_video_transcode"
    }
}

class VideoPreflightCoordinator(
    scope: CoroutineScope,
    private val sourceSizeProvider: SourceSizeProvider,
    storagePreflightChecker: StoragePreflightChecker
) {
    private val gate = StoragePreflightGate(scope, storagePreflightChecker)

    val preflightPrompt: StateFlow<StoragePreflightPrompt?> = gate.prompt
    val isCheckingStorage: StateFlow<Boolean> = gate.isChecking
    val lowStorageWarning: StateFlow<StorageLowWarning?> = gate.lowStorageWarning

    fun requestStart(
        uriString: String?,
        durationMs: Long,
        width: Int,
        height: Int,
        sourceBitrateKbps: Int,
        isPortrait: Boolean,
        mode: QualityMode,
        isProcessingProvider: () -> Boolean,
        onProceed: () -> Unit
    ): Boolean {
        if (uriString.isNullOrBlank()) return false
        return gate.requestStart(
            canStart = { !isProcessingProvider() },
            estimateRequired = {
                val sourceSizeBytes = sourceSizeProvider.querySizeBytes(uriString)
                StorageEstimatePolicy.estimateVideo(
                    VideoEstimateInput(
                        sourceSizeBytes = sourceSizeBytes,
                        durationMs = durationMs,
                        width = width,
                        height = height,
                        sourceBitrateKbps = sourceBitrateKbps,
                        isPortrait = isPortrait,
                        mode = mode
                    )
                )
            },
            onProceed = { onProceed() }
        )
    }

    fun continueAfterUnavailableStorage() = gate.continueAfterUnavailable()
    fun dismissStoragePrompt() = gate.dismissPrompt()
    fun clearAll() = gate.clearAll()
    fun clearWarning() = gate.clearWarning()
}

class VideoViewModel @JvmOverloads constructor(
    application: Application,
    availableStorageProvider: AvailableStorageProvider = StoragePreflightTestHooks.resolveStorageProvider(application),
    sourceSizeProvider: SourceSizeProvider = AndroidSourceSizeProvider(application),
    private val workScheduler: VideoWorkScheduler = WorkManagerVideoWorkScheduler(WorkManager.getInstance(application))
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val preflightCoordinator = VideoPreflightCoordinator(
        scope = viewModelScope,
        sourceSizeProvider = sourceSizeProvider,
        storagePreflightChecker = StoragePreflightChecker(availableStorageProvider)
    )

    val preflightPrompt: StateFlow<StoragePreflightPrompt?> = preflightCoordinator.preflightPrompt
    val isCheckingStorage: StateFlow<Boolean> = preflightCoordinator.isCheckingStorage
    val lowStorageWarning: StateFlow<StorageLowWarning?> = preflightCoordinator.lowStorageWarning

    init {
        viewModelScope.launch {
            workScheduler.workInfosFlow.collect { workInfos ->
                val workInfo = workInfos.firstOrNull() ?: return@collect
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getInt("progress", 0)
                        _uiState.update { it.copy(isProcessing = true, progress = progress, error = null) }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        preflightCoordinator.clearWarning()
                        val urisStrArray = workInfo.outputData.getStringArray("savedUris")
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                progress = 100,
                                completedUris = urisStrArray?.map { str -> str.toUri() } ?: emptyList()
                            )
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        preflightCoordinator.clearWarning()
                        val error = workInfo.outputData.getString("error") ?: "Processing failed"
                        _uiState.update { it.copy(isProcessing = false, error = error) }
                    }
                    WorkInfo.State.CANCELLED -> {
                        preflightCoordinator.clearWarning()
                        _uiState.update { it.copy(isProcessing = false, error = "Cancelled by user") }
                    }
                    else -> {}
                }
            }
        }
    }

    fun selectVideo(uri: Uri, duration: Long, width: Int, height: Int, bitrateKbps: Int) {
        preflightCoordinator.clearAll()
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
        if (preflightCoordinator.isCheckingStorage.value) return
        preflightCoordinator.dismissStoragePrompt()
        _uiState.update { it.copy(selectedMode = mode) }
    }

    fun startProcessing() {
        val currentState = _uiState.value
        if (!VideoSharingHelper.canStartVideoProcessing(currentState)) return
        val uri = currentState.selectedVideoUri ?: return

        preflightCoordinator.requestStart(
            uriString = uri.toString(),
            durationMs = currentState.durationMs,
            width = currentState.width,
            height = currentState.height,
            sourceBitrateKbps = currentState.bitrateKbps,
            isPortrait = currentState.isPortrait,
            mode = currentState.selectedMode,
            isProcessingProvider = { !VideoSharingHelper.canStartVideoProcessing(_uiState.value) },
            onProceed = {
                val latest = _uiState.value
                if (VideoSharingHelper.canStartVideoProcessing(latest)) {
                    enqueueVideoWork(latest)
                }
            }
        )
    }

    fun continueAfterUnavailableStorage() {
        preflightCoordinator.continueAfterUnavailableStorage()
    }

    fun dismissStoragePrompt() {
        preflightCoordinator.dismissStoragePrompt()
    }

    private fun enqueueVideoWork(currentState: UiState) {
        val uri = currentState.selectedVideoUri ?: return

        _uiState.update { it.copy(isProcessing = true, progress = 0, error = null, completedUris = emptyList()) }

        val preserveOriginal = StorageEstimatePolicy.isPreserveOriginal(
            VideoEstimateInput(
                sourceSizeBytes = null,
                durationMs = currentState.durationMs,
                width = currentState.width,
                height = currentState.height,
                sourceBitrateKbps = currentState.bitrateKbps,
                isPortrait = currentState.isPortrait,
                mode = currentState.selectedMode
            )
        )

        workScheduler.enqueueUniqueWork(
            inputUri = uri,
            durationMs = currentState.durationMs,
            isPortrait = currentState.isPortrait,
            mode = currentState.selectedMode,
            preserveOriginal = preserveOriginal
        )
    }

    fun cancelProcessing() {
        preflightCoordinator.clearWarning()
        workScheduler.cancelUniqueWork()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
