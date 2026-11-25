package com.techwin.statushd

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import kotlin.math.ceil

enum class ShareIntentAction {
    SEND,
    SEND_MULTIPLE
}

data class ShareIntentSpec(
    val action: ShareIntentAction,
    val mimeType: String,
    val streamUris: List<String>,
    val flags: Int
)

object VideoSharingHelper {

    const val MIME_TYPE_VIDEO = "video/mp4"

    fun calculatePartCount(durationSeconds: Double, splitTimeSec: Int): Int {
        if (durationSeconds <= 0.0 || splitTimeSec <= 0) return 0
        return ceil(durationSeconds / splitTimeSec).toInt()
    }

    fun calculatePartCount(durationMs: Long, splitTimeSec: Int): Int {
        if (durationMs <= 0L || splitTimeSec <= 0) return 0
        return calculatePartCount(durationMs / 1000.0, splitTimeSec)
    }

    fun createShareSpec(
        outputUris: List<String>,
        sourceUri: String? = null
    ): ShareIntentSpec? {
        if (outputUris.isEmpty()) return null

        val filtered = outputUris.filter { uri ->
            uri.isNotBlank() && (sourceUri == null || uri != sourceUri)
        }
        if (filtered.isEmpty()) return null

        val action = if (filtered.size == 1) {
            ShareIntentAction.SEND
        } else {
            ShareIntentAction.SEND_MULTIPLE
        }

        return ShareIntentSpec(
            action = action,
            mimeType = MIME_TYPE_VIDEO,
            streamUris = filtered,
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    fun createShareIntent(
        uris: List<Uri>,
        sourceUri: Uri? = null,
        chooserTitle: String? = null
    ): Intent? {
        val uriStrings = uris.map { it.toString() }
        val spec = createShareSpec(uriStrings, sourceUri?.toString()) ?: return null

        val filteredUris = uris.filter { uri ->
            uri.toString().isNotBlank() && (sourceUri == null || uri != sourceUri)
        }
        if (filteredUris.isEmpty()) return null

        val intent = when (spec.action) {
            ShareIntentAction.SEND -> {
                Intent(Intent.ACTION_SEND).apply {
                    type = spec.mimeType
                    putExtra(Intent.EXTRA_STREAM, filteredUris.first())
                    clipData = ClipData.newRawUri("Video Part", filteredUris.first())
                }
            }
            ShareIntentAction.SEND_MULTIPLE -> {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = spec.mimeType
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(filteredUris))
                    val clip = ClipData.newRawUri("Video Part 1", filteredUris.first())
                    for (i in 1 until filteredUris.size) {
                        clip.addItem(ClipData.Item(filteredUris[i]))
                    }
                    this.clipData = clip
                }
            }
        }
        intent.addFlags(spec.flags)

        val title = chooserTitle ?: if (spec.action == ShareIntentAction.SEND) {
            "Share video part"
        } else {
            "Share all video parts"
        }
        return Intent.createChooser(intent, title)
    }

    fun createSinglePartShareIntent(uri: Uri): Intent {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE_VIDEO
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Video Part", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "Share video part")
    }

    fun canStartVideoProcessing(hasSelectedVideo: Boolean, isProcessing: Boolean): Boolean {
        return hasSelectedVideo && !isProcessing
    }

    fun canStartVideoProcessing(currentState: UiState): Boolean {
        return canStartVideoProcessing(currentState.selectedVideoUri != null, currentState.isProcessing)
    }
}
