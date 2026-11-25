package com.techwin.statushd.ui.photo

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
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
import com.techwin.statushd.PhotoQualityMode
import com.techwin.statushd.PhotoUiState
import com.techwin.statushd.PhotoViewModel
import com.techwin.statushd.ui.components.*

@Composable
fun PhotoScreenContent(
    viewModel: PhotoViewModel, 
    paddingValues: PaddingValues,
    onNavigateToBatch: (List<Uri>) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.selectPhoto(it) }
    }

    val batchPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        when {
            uris.isEmpty() -> {
                // preserve current screen
            }
            uris.size == 1 || uris.size > 10 -> {
                Toast.makeText(context, "Select 2 to 10 photos for a batch.", Toast.LENGTH_LONG).show()
            }
            else -> {
                onNavigateToBatch(uris)
            }
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is PhotoUiState.Error) {
            Toast.makeText(context, (uiState as PhotoUiState.Error).message, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    val isSelected = uiState is PhotoUiState.Selected
    val isProcessing = uiState is PhotoUiState.Processing || uiState is PhotoUiState.Saving || uiState is PhotoUiState.Canceling
    val showPersistentBar = isSelected || isProcessing

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
                            text = "Prepare photo",
                            enabled = isSelected && !isProcessing,
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
            targetState = uiState,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
            label = "PhotoStateTransition"
        ) { state ->
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
                when (state) {
                    is PhotoUiState.Idle, is PhotoUiState.Error -> {
                        StatusHeroCard(
                            badgeText = "PHOTO STATUS",
                            title = "Sharper photos, lighter files",
                            description = "Resize an existing JPEG without stretching or cropping.",
                            gradientColors = listOf(MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
                            buttonText = "Choose photo",
                            buttonIcon = Icons.Default.PhotoLibrary,
                            onButtonClick = {
                                photoPickerLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            graphicContent = {
                                PhotoHeroGraphic()
                            }
                        )

                        PrivacyStrip(
                            icon = Icons.Default.Security,
                            title = "Personal metadata is not copied",
                            subtitle = "Source location and camera details stay out of the optimized copy."
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        StatusPrimaryButton(
                            text = "Prepare multiple photos",
                            icon = Icons.Default.Collections,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = {
                                batchPickerLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        )

                        Text(
                            text = "Choose your output",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        QualityOptionCard(
                            title = "Status HD",
                            badgeText = "Quality 90",
                            primaryText = "Higher detail",
                            secondaryText = "Best for photography",
                            tertiaryText = "",
                            icon = Icons.Default.HighQuality,
                            accentColor = MaterialTheme.colorScheme.primary,
                            isSelected = true,
                            onClick = { }
                        )

                        QualityOptionCard(
                            title = "Smaller File",
                            badgeText = "Quality 80",
                            primaryText = "Reduced size",
                            secondaryText = "Best for faster sharing",
                            tertiaryText = "",
                            icon = Icons.Default.Compress,
                            accentColor = MaterialTheme.colorScheme.secondary,
                            isSelected = false,
                            onClick = { }
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                    }

                    is PhotoUiState.Selected -> {
                        MediaInfoCard(
                            uri = state.uri,
                            filename = state.fileName,
                            badgeText = "JPEG",
                            dimensions = "${state.inputWidth} × ${state.inputHeight}",
                            sizeStr = Formatter.formatShortFileSize(context, state.inputSize),
                            isVideo = false
                        )

                        Text(
                            text = "Choose your output",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        QualityOptionCard(
                            title = "Status HD",
                            badgeText = "Quality 90",
                            primaryText = "Higher detail",
                            secondaryText = "Best for photography",
                            tertiaryText = "",
                            icon = Icons.Default.HighQuality,
                            accentColor = MaterialTheme.colorScheme.primary,
                            isSelected = state.mode == PhotoQualityMode.STATUS_HD,
                            onClick = { viewModel.setMode(PhotoQualityMode.STATUS_HD) }
                        )

                        QualityOptionCard(
                            title = "Smaller File",
                            badgeText = "Quality 80",
                            primaryText = "Reduced size",
                            secondaryText = "Best for faster sharing",
                            tertiaryText = "",
                            icon = Icons.Default.Compress,
                            accentColor = MaterialTheme.colorScheme.secondary,
                            isSelected = state.mode == PhotoQualityMode.SMALLER_FILE,
                            onClick = { viewModel.setMode(PhotoQualityMode.SMALLER_FILE) }
                        )

                        OutlinedButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("Choose another photo")
                        }
                    }

                    is PhotoUiState.Processing, PhotoUiState.Saving, PhotoUiState.Canceling -> {
                        Spacer(modifier = Modifier.height(32.dp))
                        val progress = if (state is PhotoUiState.Processing) state.progress / 100f else 0.5f
                        val currentStageIndex = when (state) {
                            is PhotoUiState.Processing -> if (state.progress < 30) 0 else 1
                            PhotoUiState.Saving -> 2
                            else -> 2
                        }
                        val headline = if (state is PhotoUiState.Canceling) "Canceling operation..." else "Preparing your photo"

                        ProcessingStage(
                            headline = headline,
                            subtext = "",
                            progress = progress,
                            stages = listOf("Reading", "Resizing", "Saving"),
                            currentStageIndex = currentStageIndex
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        if (state !is PhotoUiState.Canceling) {
                            OutlinedButton(
                                onClick = { viewModel.cancelProcessing() },
                                modifier = Modifier.fillMaxWidth().height(50.dp)
                            ) {
                                Text("Cancel")
                            }
                        }
                    }

                    is PhotoUiState.Saved -> {
                        EmptyState(
                            icon = Icons.Default.CheckCircle,
                            title = "Photo ready",
                            description = "Saved to Pictures/StatusHD"
                        )

                        MediaInfoCard(
                            uri = state.outputUri,
                            filename = "Optimized Photo",
                            badgeText = "JPEG",
                            dimensions = "${state.outputWidth} × ${state.outputHeight}",
                            sizeStr = Formatter.formatShortFileSize(context, state.outputSize),
                            isVideo = false
                        )

                        StatusPrimaryButton(
                            text = "Share photo",
                            icon = Icons.Default.Share,
                            onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/jpeg"
                                    putExtra(Intent.EXTRA_STREAM, state.outputUri)
                                    clipData = ClipData.newRawUri(null, state.outputUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                try {
                                    context.startActivity(Intent.createChooser(intent, "Share Photo"))
                                } catch (e: ActivityNotFoundException) {
                                    Toast.makeText(context, "No sharing app found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )

                        OutlinedButton(
                            onClick = { viewModel.reset() },
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("Choose another photo")
                        }
                    }
                }
            }
        }
    }
}
