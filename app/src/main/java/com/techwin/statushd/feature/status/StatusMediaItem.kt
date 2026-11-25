package com.techwin.statushd.feature.status

import android.net.Uri

enum class StatusMediaType {
    PHOTO,
    VIDEO
}

data class StatusMediaItem(
    val documentId: String,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val mediaType: StatusMediaType,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0
)
