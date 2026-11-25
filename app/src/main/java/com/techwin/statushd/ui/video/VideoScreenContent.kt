package com.techwin.statushd.ui.video

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.techwin.statushd.QualityMode
import com.techwin.statushd.VideoSharingHelper
import com.techwin.statushd.VideoViewModel
import com.techwin.statushd.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoPartMetadata(
    val partNumber: Int,
    val uri: Uri,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Long?,
    val destination: String
)

@Composable
fun VideoScreenContent(viewModel: VideoViewModel, paddingValues: PaddingValues) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, it)
                val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val rotStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                val bitStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)

                var width = wStr?.toIntOrNull() ?: 0
                var height = hStr?.toIntOrNull() ?: 0
                val rot = rotStr?.toIntOrNull() ?: 0
                val bitKbps = (bitStr?.toIntOrNull() ?: 0) / 1000

                if (rot == 90 || rot == 270) {
                    val tmp = width
                    width = height
                    height = tmp
                }
                viewModel.selectVideo(it, durStr?.toLongOrNull() ?: 0L, width, height, bitKbps)
            } catch (e: Exception) {
                Toast.makeText(context, "The selected video could not be read.", Toast.LENGTH_SHORT).show()
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    val hasSelection = uiState.selectedVideoUri != null
    val isProcessing = uiState.isProcessing
    val isSuccess = uiState.completedUris.isNotEmpty()
    val showPersistentBar = hasSelection && !isSuccess

    val partMetadataList by produceState<List<VideoPartMetadata>>(initialValue = emptyList(), uiState.completedUris) {
        if (uiState.completedUris.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            uiState.completedUris.mapIndexed { index, uri ->
                var durationMs: Long? = null
                var width: Int? = null
                var height: Int? = null
                var sizeBytes: Long? = null
                var destination = "Movies/HDStatus"

                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                        height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}

                try {
                    context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.SIZE, MediaStore.Video.Media.RELATIVE_PATH),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                                sizeBytes = cursor.getLong(sizeIdx)
                            }
                            val pathIdx = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                            if (pathIdx >= 0 && !cursor.isNull(pathIdx)) {
                                val path = cursor.getString(pathIdx)
                                if (!path.isNullOrBlank()) {
                                    destination = path.trimEnd('/')
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}

                if (sizeBytes == null || sizeBytes == 0L) {
                    try {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            if (pfd.statSize > 0) sizeBytes = pfd.statSize
                        }
                    } catch (_: Exception) {}
                }

                VideoPartMetadata(
                    partNumber = index + 1,
                    uri = uri,
                    durationMs = durationMs,
                    width = width,
                    height = height,
                    sizeBytes = sizeBytes,
                    destination = destination
                )
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showPersistentBar) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = paddingValues.calculateBottomPadding()),
                    color = MaterialTheme.colorScheme.background,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        StatusPrimaryButton(
                            text = "Prepare video",
                            enabled = !isProcessing,
                            onClick = { viewModel.startProcessing() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        val contentBottomPadding = if (showPersistentBar) {
            innerPadding.calculateBottomPadding() + 20.dp
        } else {
            paddingValues.calculateBottomPadding() + 24.dp
        }

        AnimatedContent(
            targetState = Triple(hasSelection, isProcessing, isSuccess),
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
            label = "VideoStateTransition"
        ) { (selected, processing, success) ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = paddingValues.calculateTopPadding() + 20.dp,
                        start = 20.dp,
                        end = 20.dp,
                        bottom = contentBottomPadding
                    ),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                when {
                    processing -> {
                        Spacer(modifier = Modifier.height(32.dp))
                        ProcessingStage(
                            headline = "Preparing your video",
                            subtext = "",
                            progress = uiState.progress / 100f,
                            stages = listOf("Preparing", "Encoding", "Saving"),
                            currentStageIndex = if (uiState.progress < 10) 0 else if (uiState.progress < 90) 1 else 2
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        OutlinedButton(
                            onClick = { viewModel.cancelProcessing() },
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                    }

                    success -> {
                        val expectedParts = VideoSharingHelper.calculatePartCount(uiState.durationMs, uiState.selectedMode.splitTime)
                        val isPartialSuccess = expectedParts > 0 && uiState.completedUris.size < expectedParts

                        if (isPartialSuccess) {
                            EmptyState(
                                icon = Icons.Default.Warning,
                                title = "Some parts are ready",
                                description = "${uiState.completedUris.size} ${if (uiState.completedUris.size == 1) "part was" else "parts were"} saved to Movies/HDStatus."
                            )

                            StatusPrimaryButton(
                                text = "Share successful parts",
                                icon = Icons.Default.Share,
                                onClick = {
                                    val intent = VideoSharingHelper.createShareIntent(
                                        uris = uiState.completedUris,
                                        sourceUri = uiState.selectedVideoUri,
                                        chooserTitle = "Share successful parts"
                                    )
                                    if (intent != null) {
                                        try {
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "No sharing app found", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )

                            OutlinedButton(
                                onClick = { openGallery(context) },
                                modifier = Modifier.fillMaxWidth().height(50.dp)
                            ) {
                                Text("Open Gallery")
                            }

                            OutlinedButton(
                                onClick = {
                                    viewModel.clearError()
                                    viewModel.startProcessing()
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp)
                            ) {
                                Text("Try again")
                            }
                        } else {
                            val count = uiState.completedUris.size
                            EmptyState(
                                icon = Icons.Default.CheckCircle,
                                title = "Video ready",
                                description = "$count ${if (count == 1) "part" else "parts"} saved to Movies/HDStatus."
                            )

                            if (count >= 2) {
                                StatusPrimaryButton(
                                    text = "Share all parts",
                                    icon = Icons.Default.Share,
                                    enabled = true,
                                    onClick = {
                                        val intent = VideoSharingHelper.createShareIntent(
                                            uris = uiState.completedUris,
                                            sourceUri = uiState.selectedVideoUri,
                                            chooserTitle = "Share all video parts"
                                        )
                                        if (intent != null) {
                                            try {
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "No sharing app found", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            } else {
                                StatusPrimaryButton(
                                    text = "Share video",
                                    icon = Icons.Default.Share,
                                    enabled = true,
                                    onClick = {
                                        val intent = VideoSharingHelper.createShareIntent(
                                            uris = uiState.completedUris,
                                            sourceUri = uiState.selectedVideoUri,
                                            chooserTitle = "Share video"
                                        )
                                        if (intent != null) {
                                            try {
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "No sharing app found", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        Text(
                            text = "Created parts (${uiState.completedUris.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        partMetadataList.forEach { part ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Part ${part.partNumber} of ${uiState.completedUris.size}",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                        OutlinedButton(
                                            onClick = {
                                                try {
                                                    val shareIntent = VideoSharingHelper.createSinglePartShareIntent(part.uri)
                                                    context.startActivity(shareIntent)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "No sharing app found", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Share", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }

                                    if (part.durationMs != null && part.durationMs > 0) {
                                        Text(
                                            text = "Duration: ${part.durationMs / 1000}s",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (part.width != null && part.height != null && part.width > 0 && part.height > 0) {
                                        Text(
                                            text = "Resolution: ${part.width} × ${part.height}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (part.sizeBytes != null && part.sizeBytes > 0) {
                                        Text(
                                            text = "Size: ${Formatter.formatShortFileSize(context, part.sizeBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = "Saved to: ${part.destination}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                launcher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.VideoOnly
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("Choose another video")
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                    }

                    selected -> {
                        MediaInfoCard(
                            uri = uiState.selectedVideoUri!!,
                            filename = "Selected Video",
                            badgeText = "MP4",
                            dimensions = "${uiState.width} × ${uiState.height}",
                            sizeStr = "",
                            durationStr = "${uiState.durationMs / 1000}s",
                            isVideo = true
                        )

                        Text(
                            text = "Choose your output",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        val fullHdParts = VideoSharingHelper.calculatePartCount(uiState.durationMs, QualityMode.FULL_HD.splitTime)
                        val hdLongParts = VideoSharingHelper.calculatePartCount(uiState.durationMs, QualityMode.HD_LONG.splitTime)

                        QualityOptionCard(
                            title = "Full HD Detail",
                            badgeText = "1080p",
                            primaryText = "More detail",
                            secondaryText = "Up to 30 sec per part",
                            tertiaryText = if (uiState.selectedMode == QualityMode.FULL_HD) {
                                "Estimated output: $fullHdParts ${if (fullHdParts == 1) "part" else "parts"}"
                            } else "",
                            icon = Icons.Default.HighQuality,
                            accentColor = MaterialTheme.colorScheme.primary,
                            isSelected = uiState.selectedMode == QualityMode.FULL_HD,
                            onClick = { viewModel.setMode(QualityMode.FULL_HD) }
                        )

                        QualityOptionCard(
                            title = "HD Longer",
                            badgeText = "720p",
                            primaryText = "Fewer parts",
                            secondaryText = "Up to 60 sec per part",
                            tertiaryText = if (uiState.selectedMode == QualityMode.HD_LONG) {
                                "Estimated output: $hdLongParts ${if (hdLongParts == 1) "part" else "parts"}"
                            } else "",
                            icon = Icons.Default.Speed,
                            accentColor = MaterialTheme.colorScheme.secondary,
                            isSelected = uiState.selectedMode == QualityMode.HD_LONG,
                            onClick = { viewModel.setMode(QualityMode.HD_LONG) }
                        )

                        OutlinedButton(
                            onClick = {
                                launcher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.VideoOnly
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("Choose another video")
                        }
                    }

                    else -> {
                        StatusHeroCard(
                            badgeText = "VIDEO STATUS",
                            title = "Make every frame count",
                            description = "Prepare and split an existing video while keeping more visible detail.",
                            gradientColors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
                            buttonText = "Choose video",
                            buttonIcon = Icons.Default.VideoLibrary,
                            onButtonClick = {
                                launcher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.VideoOnly
                                    )
                                )
                            },
                            graphicContent = {
                                VideoHeroGraphic()
                            }
                        )

                        PrivacyStrip(
                            icon = Icons.Default.Lock,
                            title = "Private by design",
                            subtitle = "Processing happens on this device."
                        )

                        Text(
                            text = "Choose your output",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        QualityOptionCard(
                            title = "Full HD Detail",
                            badgeText = "1080p",
                            primaryText = "Best for fine detail",
                            secondaryText = "30-second parts",
                            tertiaryText = "",
                            icon = Icons.Default.HighQuality,
                            accentColor = MaterialTheme.colorScheme.primary,
                            isSelected = true,
                            onClick = { }
                        )

                        QualityOptionCard(
                            title = "HD Longer",
                            badgeText = "720p",
                            primaryText = "Best for fewer parts",
                            secondaryText = "60-second parts",
                            tertiaryText = "",
                            icon = Icons.Default.Speed,
                            accentColor = MaterialTheme.colorScheme.secondary,
                            isSelected = false,
                            onClick = { }
                        )

                        Text(
                            text = "Final upload quality is controlled by the receiving platform.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

private fun openGallery(context: Context) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                type = "video/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(fallback)
        } catch (_: Exception) {
            Toast.makeText(context, "Could not open Gallery", Toast.LENGTH_SHORT).show()
        }
    }
}
