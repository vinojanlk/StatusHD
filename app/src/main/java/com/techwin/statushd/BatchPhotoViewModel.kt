package com.techwin.statushd

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BatchPhotoUiEvent {
    data class ShowMessage(val message: String) : BatchPhotoUiEvent
}

class BatchPhotoViewModel(application: Application) : AndroidViewModel(application) {

    private val preparer = AndroidPhotoPreparer(PhotoProcessor(application))
    private val runner = BatchPhotoRunner(preparer)

    private val _snapshot = MutableStateFlow<BatchPhotoQueueSnapshot?>(null)
    val snapshot = _snapshot.asStateFlow()

    private val _uiEvents = MutableStateFlow<BatchPhotoUiEvent?>(null)
    val uiEvents = _uiEvents.asStateFlow()

    init {
        runner.onSnapshotChanged = { newSnapshot ->
            _snapshot.value = newSnapshot
        }
    }

    fun initializeQueue(identifiers: List<String>) {
        viewModelScope.launch {
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

    fun startBatch(mode: PhotoQualityMode) {
        viewModelScope.launch(Dispatchers.IO) {
            runner.start(mode)
        }
    }

    fun stopAfterCurrent() {
        viewModelScope.launch {
            runner.stopAfterCurrent()
        }
    }

    fun retryFailed() {
        viewModelScope.launch(Dispatchers.IO) {
            runner.retryFailed()
        }
    }

    fun reset() {
        viewModelScope.launch {
            runner.initializeQueue(emptyList())
            _snapshot.value = null
        }
    }
    
    fun clearEvent() {
        _uiEvents.value = null
    }
}
