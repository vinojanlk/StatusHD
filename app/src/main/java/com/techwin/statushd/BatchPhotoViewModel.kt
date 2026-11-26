package com.techwin.statushd

import android.app.Application
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BatchPhotoUiEvent {
    data class ShowMessage(val message: String) : BatchPhotoUiEvent
}

class BatchPhotoPreflightController(
    private val scope: CoroutineScope,
    private val runner: BatchPhotoRunner,
    private val sourceSizeProvider: SourceSizeProvider,
    storagePreflightChecker: StoragePreflightChecker,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _snapshot = MutableStateFlow<BatchPhotoQueueSnapshot?>(null)
    val snapshot: StateFlow<BatchPhotoQueueSnapshot?> = _snapshot.asStateFlow()

    private val _uiEvents = MutableStateFlow<BatchPhotoUiEvent?>(null)
    val uiEvents: StateFlow<BatchPhotoUiEvent?> = _uiEvents.asStateFlow()

    private val _draftMode = MutableStateFlow(PhotoQualityMode.STATUS_HD)
    val draftMode: StateFlow<PhotoQualityMode> = _draftMode.asStateFlow()

    private val preflightGate = StoragePreflightGate(
        scope = scope,
        storagePreflightChecker = storagePreflightChecker
    )

    val preflightPrompt: StateFlow<StoragePreflightPrompt?> = preflightGate.prompt
    val isCheckingStorage: StateFlow<Boolean> = preflightGate.isChecking
    val lowStorageWarning: StateFlow<StorageLowWarning?> = preflightGate.lowStorageWarning

    init {
        runner.onSnapshotChanged = { newSnapshot ->
            _snapshot.value = newSnapshot
            if (newSnapshot.state == BatchState.Finished) {
                preflightGate.clearWarning()
            }
        }
    }

    fun initializeQueue(identifiers: List<String>) {
        preflightGate.clearAll()
        _draftMode.value = PhotoQualityMode.STATUS_HD
        scope.launch {
            if (identifiers.size > 10) {
                _uiEvents.value = BatchPhotoUiEvent.ShowMessage("StatusHD can prepare up to 10 photos at once.")
                return@launch
            }

            val success = runner.initializeQueue(identifiers)
            if (!success && identifiers.isNotEmpty() && identifiers.size == 1) {
                _uiEvents.value = BatchPhotoUiEvent.ShowMessage("Select at least 2 photos for a batch.")
            }
        }
    }

    fun setDraftMode(mode: PhotoQualityMode) {
        if (_snapshot.value?.state != BatchState.Draft || preflightGate.isChecking.value) return
        preflightGate.dismissPrompt()
        _draftMode.value = mode
    }

    fun startBatch(mode: PhotoQualityMode) {
        val current = _snapshot.value ?: return
        if (current.state != BatchState.Draft || current.items.size < 2) return
        _draftMode.value = mode

        preflightGate.requestStart(
            canStart = {
                val snap = _snapshot.value
                snap != null && snap.state == BatchState.Draft && snap.items.size >= 2
            },
            estimateRequired = {
                val items = _snapshot.value?.items.orEmpty()
                    .take(StorageEstimatePolicy.MAX_BATCH_ITEMS)
                val inputs = items.map { item ->
                    PhotoEstimateInput(
                        sourceSizeBytes = sourceSizeProvider.querySizeBytes(item.sourceIdentifier)
                    )
                }
                StorageEstimatePolicy.estimateBatchPhotos(inputs, _draftMode.value)
            },
            onProceed = {
                val modeToRun = _draftMode.value
                scope.launch(ioDispatcher) {
                    runner.start(modeToRun)
                }
            }
        )
    }

    fun continueAfterUnavailableStorage() {
        preflightGate.continueAfterUnavailable()
    }

    fun dismissStoragePrompt() {
        preflightGate.dismissPrompt()
    }

    fun stopAfterCurrent() {
        scope.launch {
            runner.stopAfterCurrent()
        }
    }

    fun retryFailed() {
        scope.launch(ioDispatcher) {
            runner.retryFailed()
        }
    }

    fun reset() {
        preflightGate.clearAll()
        _draftMode.value = PhotoQualityMode.STATUS_HD
        scope.launch {
            runner.initializeQueue(emptyList())
            _snapshot.value = null
        }
    }

    fun clearEvent() {
        _uiEvents.value = null
    }
}

class BatchPhotoViewModel @JvmOverloads constructor(
    application: Application,
    availableStorageProvider: AvailableStorageProvider = StoragePreflightTestHooks.resolveStorageProvider(application),
    sourceSizeProvider: SourceSizeProvider = AndroidSourceSizeProvider(application),
    preparer: PhotoPreparer = AndroidPhotoPreparer(PhotoProcessor(application)),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {

    private val controller = BatchPhotoPreflightController(
        scope = viewModelScope,
        runner = BatchPhotoRunner(preparer),
        sourceSizeProvider = sourceSizeProvider,
        storagePreflightChecker = StoragePreflightChecker(availableStorageProvider),
        ioDispatcher = ioDispatcher
    )

    val snapshot: StateFlow<BatchPhotoQueueSnapshot?> = controller.snapshot
    val uiEvents: StateFlow<BatchPhotoUiEvent?> = controller.uiEvents
    val draftMode: StateFlow<PhotoQualityMode> = controller.draftMode
    val preflightPrompt: StateFlow<StoragePreflightPrompt?> = controller.preflightPrompt
    val isCheckingStorage: StateFlow<Boolean> = controller.isCheckingStorage
    val lowStorageWarning: StateFlow<StorageLowWarning?> = controller.lowStorageWarning

    fun initializeQueue(identifiers: List<String>) = controller.initializeQueue(identifiers)
    fun setDraftMode(mode: PhotoQualityMode) = controller.setDraftMode(mode)
    fun startBatch(mode: PhotoQualityMode) = controller.startBatch(mode)
    fun continueAfterUnavailableStorage() = controller.continueAfterUnavailableStorage()
    fun dismissStoragePrompt() = controller.dismissStoragePrompt()
    fun stopAfterCurrent() = controller.stopAfterCurrent()
    fun retryFailed() = controller.retryFailed()
    fun reset() = controller.reset()
    fun clearEvent() = controller.clearEvent()
}
