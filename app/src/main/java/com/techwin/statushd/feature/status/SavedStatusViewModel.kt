package com.techwin.statushd.feature.status

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class SavedStatusViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<SavedStatusUiState>(SavedStatusUiState.Loading)
    val uiState: StateFlow<SavedStatusUiState> = _uiState.asStateFlow()

    private val digestStore = SavedStatusDigestStore(application)
    private val folderRepository = StatusFolderRepository(application)
    private val savedRepository = SavedStatusRepository(application, digestStore)

    private var currentSaveJob: Job? = null

    init {
        checkAndLoadFolder()
    }

    fun checkAndLoadFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = SavedStatusUiState.Loading
            val storedUriStr = digestStore.selectedTreeUri.firstOrNull()
            val folderName = digestStore.selectedFolderName.firstOrNull() ?: "Status Folder"
            val consentGiven = digestStore.isConsentGiven.firstOrNull() ?: false

            if (storedUriStr.isNullOrEmpty()) {
                _uiState.value = SavedStatusUiState.NoFolderSelected
                return@launch
            }

            val treeUri = Uri.parse(storedUriStr)
            val context = getApplication<Application>()

            // Verify persisted URI permission still exists
            val hasPermission = context.contentResolver.persistedUriPermissions.any { perm ->
                perm.uri == treeUri && perm.isReadPermission
            }

            if (!hasPermission) {
                _uiState.value = SavedStatusUiState.PermissionLost
                return@launch
            }

            val items = folderRepository.queryStatusFolder(treeUri)
            _uiState.value = SavedStatusUiState.FolderLoaded(
                folderName = folderName,
                treeUri = treeUri,
                items = items,
                isConsentGiven = consentGiven
            )
        }
    }

    fun selectFolder(treeUri: Uri, folderName: String) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                digestStore.saveFolderInfo(treeUri.toString(), folderName)
                val consentGiven = digestStore.isConsentGiven.firstOrNull() ?: false

                val items = folderRepository.queryStatusFolder(treeUri)
                _uiState.value = SavedStatusUiState.FolderLoaded(
                    folderName = folderName,
                    treeUri = treeUri,
                    items = items,
                    isConsentGiven = consentGiven
                )
            } catch (e: Exception) {
                Log.e("SavedStatusVM", "Failed to take persistable URI permission", e)
                _uiState.value = SavedStatusUiState.Error("Failed to access selected folder.")
            }
        }
    }

    fun refreshItems() {
        val state = _uiState.value
        if (state is SavedStatusUiState.FolderLoaded) {
            viewModelScope.launch(Dispatchers.IO) {
                val items = folderRepository.queryStatusFolder(state.treeUri)
                _uiState.value = state.copy(items = items)
            }
        } else {
            checkAndLoadFolder()
        }
    }

    fun selectItem(item: StatusMediaItem?) {
        val state = _uiState.value
        if (state is SavedStatusUiState.FolderLoaded) {
            _uiState.value = state.copy(selectedItem = item, savedUri = null, savedMessage = null)
        }
    }

    fun saveSelectedItem() {
        val state = _uiState.value as? SavedStatusUiState.FolderLoaded ?: return
        val item = state.selectedItem ?: return

        if (!state.isConsentGiven) {
            _uiState.value = state.copy(showConsentDialog = true)
            return
        }

        if (currentSaveJob?.isActive == true) return

        currentSaveJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = state.copy(isSaving = true)
                val savedUri = savedRepository.saveStatusItem(item)
                val latestState = _uiState.value as? SavedStatusUiState.FolderLoaded
                if (latestState != null) {
                    _uiState.value = latestState.copy(
                        isSaving = false,
                        savedUri = savedUri,
                        savedMessage = "Saved to Gallery!"
                    )
                }
            } catch (e: AlreadySavedException) {
                val latestState = _uiState.value as? SavedStatusUiState.FolderLoaded
                if (latestState != null) {
                    _uiState.value = latestState.copy(
                        isSaving = false,
                        savedUri = e.savedUri,
                        savedMessage = "Already saved to Gallery"
                    )
                }
            } catch (e: Exception) {
                Log.e("SavedStatusVM", "Failed to save status item", e)
                val latestState = _uiState.value as? SavedStatusUiState.FolderLoaded
                if (latestState != null) {
                    _uiState.value = latestState.copy(
                        isSaving = false,
                        savedMessage = "Failed to save media item"
                    )
                }
            } finally {
                currentSaveJob = null
            }
        }
    }

    fun confirmConsent() {
        val state = _uiState.value as? SavedStatusUiState.FolderLoaded ?: return
        viewModelScope.launch(Dispatchers.IO) {
            digestStore.setConsentGiven(true)
            val updatedState = state.copy(isConsentGiven = true, showConsentDialog = false)
            _uiState.value = updatedState
            saveSelectedItem()
        }
    }

    fun dismissConsentDialog() {
        val state = _uiState.value as? SavedStatusUiState.FolderLoaded ?: return
        _uiState.value = state.copy(showConsentDialog = false)
    }

    fun disconnectFolder() {
        val state = _uiState.value as? SavedStatusUiState.FolderLoaded ?: return
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    state.treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            digestStore.clearFolderInfo()
            _uiState.value = SavedStatusUiState.NoFolderSelected
        }
    }

    fun clearSavedMessage() {
        val state = _uiState.value as? SavedStatusUiState.FolderLoaded ?: return
        _uiState.value = state.copy(savedMessage = null)
    }
}
