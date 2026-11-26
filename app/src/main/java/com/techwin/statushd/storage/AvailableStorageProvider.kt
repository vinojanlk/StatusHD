package com.techwin.statushd.storage

data class StorageAvailability(
    val internalCacheAvailableBytes: Long?,
    val sharedMediaAvailableBytes: Long?
)

fun interface AvailableStorageProvider {
    suspend fun getAvailability(): StorageAvailability
}

interface SourceSizeProvider {
    suspend fun querySizeBytes(uriString: String): Long?
}
