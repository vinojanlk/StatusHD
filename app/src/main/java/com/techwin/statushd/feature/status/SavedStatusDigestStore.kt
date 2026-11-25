package com.techwin.statushd.feature.status

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

private val Context.statusDataStore by preferencesDataStore(name = "status_preferences")

data class DigestRecord(
    val sha256: String,
    val outputUriString: String,
    val mimeType: String,
    val size: Long
)

class SavedStatusDigestStore(private val context: Context) {

    companion object {
        private val TREE_URI_KEY = stringPreferencesKey("selected_tree_uri")
        private val FOLDER_NAME_KEY = stringPreferencesKey("selected_folder_name")
        private val CONSENT_GIVEN_KEY = booleanPreferencesKey("privacy_consent_given")
        private val DIGEST_LEDGER_KEY = stringPreferencesKey("digest_ledger_data")
    }

    val selectedTreeUri: Flow<String?> = context.statusDataStore.data.map { prefs ->
        prefs[TREE_URI_KEY]
    }

    val selectedFolderName: Flow<String?> = context.statusDataStore.data.map { prefs ->
        prefs[FOLDER_NAME_KEY]
    }

    val isConsentGiven: Flow<Boolean> = context.statusDataStore.data.map { prefs ->
        prefs[CONSENT_GIVEN_KEY] ?: false
    }

    suspend fun saveFolderInfo(treeUriString: String, folderName: String) {
        context.statusDataStore.edit { prefs ->
            prefs[TREE_URI_KEY] = treeUriString
            prefs[FOLDER_NAME_KEY] = folderName
        }
    }

    suspend fun setConsentGiven(given: Boolean) {
        context.statusDataStore.edit { prefs ->
            prefs[CONSENT_GIVEN_KEY] = given
        }
    }

    suspend fun clearFolderInfo() {
        context.statusDataStore.edit { prefs ->
            prefs.remove(TREE_URI_KEY)
            prefs.remove(FOLDER_NAME_KEY)
        }
    }

    suspend fun getDigestRecord(sha256: String): DigestRecord? {
        val prefs = context.statusDataStore.data.firstOrNull() ?: return null
        val ledgerStr = prefs[DIGEST_LEDGER_KEY] ?: return null
        if (ledgerStr.isEmpty()) return null

        ledgerStr.split(";").forEach { entry ->
            val parts = entry.split("|")
            if (parts.size == 4 && parts[0] == sha256) {
                return DigestRecord(parts[0], parts[1], parts[2], parts[3].toLongOrNull() ?: 0L)
            }
        }
        return null
    }

    suspend fun saveDigestRecord(record: DigestRecord) {
        context.statusDataStore.edit { prefs ->
            val existing = prefs[DIGEST_LEDGER_KEY] ?: ""
            val newEntry = "${record.sha256}|${record.outputUriString}|${record.mimeType}|${record.size}"
            val updatedLedger = if (existing.isEmpty()) newEntry else "$existing;$newEntry"
            prefs[DIGEST_LEDGER_KEY] = updatedLedger
        }
    }
}
