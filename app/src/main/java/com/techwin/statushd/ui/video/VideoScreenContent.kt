package com.techwin.statushd.ui.video

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.techwin.statushd.QualityMode
import com.techwin.statushd.VideoViewModel
import com.techwin.statushd.ui.components.*

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

    AnimatedContent(
        targetState = Triple(uiState.selectedVideoUri != null, uiState.isProcessing, uiState.completedUris.isNotEmpty()),
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
        label = "VideoStateTransition"
    ) { (hasSelection, isProcessing, isSuccess) ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = paddingValues.calculateTopPadding() + 20.dp,
                    start = 20.dp,
                    end = 20.dp,
                    bottom = paddingValues.calculateBottomPadding() + 24.dp
                ),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            when {
                isProcessing -> {
                    Spacer(modifier = Modifier.height(32.dp))
                    ProcessingStage(
                        headline = "Preparing your video",
                        subtext = "", // We could add part info if ViewModel provided it
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
                isSuccess -> {
                    EmptyState(
                        icon = Icons.Default.CheckCircle,
                        title = "Video ready",
                        description = "Successfully split into ${uiState.completedUris.size} part(s)."
                    )

                    StatusPrimaryButton(
                        text = "Share next part",
                        icon = Icons.Default.Share,
                        onClick = {
                            val uris = ArrayList(uiState.completedUris)
                            val intent = if (uris.size == 1) {
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, uris.first())
                                }
                            } else {
                                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "video/mp4"
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                }
                            }
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try {
                                context.startActivity(Intent.createChooser(intent, "Share Video"))
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, "No sharing app found", Toast.LENGTH_SHORT).show()
                            }
                        }
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
                    Spacer(modifier = Modifier.height(32.dp))
                }
                hasSelection -> {
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

                    QualityOptionCard(
                        title = "Full HD Detail",
                        badgeText = "1080p",
                        primaryText = "More detail",
                        secondaryText = "Up to 30 sec per part",
                        tertiaryText = "",
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
                        tertiaryText = "",
                        icon = Icons.Default.Speed,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        isSelected = uiState.selectedMode == QualityMode.HD_LONG,
                        onClick = { viewModel.setMode(QualityMode.HD_LONG) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    StatusPrimaryButton(
                        text = "Prepare video",
                        onClick = { viewModel.startProcessing() }
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
                    Spacer(modifier = Modifier.height(32.dp))
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
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}
