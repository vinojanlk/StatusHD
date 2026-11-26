package com.techwin.statushd.feature.status

import android.net.Uri

sealed interface SavedStatusUiState {
    object NoFolderSelected : SavedStatusUiState
    object PermissionLost : SavedStatusUiState
    object Loading : SavedStatusUiState
    data class FolderLoaded(
        val folderName: String,
        val treeUri: Uri,
        val items: List<StatusMediaItem>,
        val selectedItem: StatusMediaItem? = null,
        val isSaving: Boolean = false,
        val savedUri: Uri? = null,
        val savedMessage: String? = null,
        val isConsentGiven: Boolean = false,
        val showConsentDialog: Boolean = false
    ) : SavedStatusUiState
    data class Error(val message: String) : SavedStatusUiState
}
