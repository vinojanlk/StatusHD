package com.techwin.statushd

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun PhotoScreen(viewModel: PhotoViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.selectPhoto(it)
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is PhotoUiState.Error) {
            val msg = (uiState as PhotoUiState.Error).message
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (val state = uiState) {
            is PhotoUiState.Idle -> {
                Spacer(modifier = Modifier.height(48.dp))
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "JPEG Photo Optimizer",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Optimize photos privately on your device. Perfect formatting for status sharing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = {
                        photoPickerLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("Choose photo", style = MaterialTheme.typography.titleMedium)
                }
            }

            is PhotoUiState.Selected -> {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    colors = CardDefaults.elevatedCardColors()
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        coil.compose.AsyncImage(
                            model = state.uri,
                            contentDescription = "Selected photo preview",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Selected Photo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Filename: ${state.fileName}", style = MaterialTheme.typography.bodyMedium)
                        Text("Input dimensions: ${state.inputWidth} × ${state.inputHeight}", style = MaterialTheme.typography.bodyMedium)
                        Text("Input size: ${state.inputSize / 1024} KB", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Source location and camera metadata are not copied.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    "Select Quality Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Start)
                )

                PhotoQualityMode.values().forEach { mode ->
                    val isSelected = state.mode == mode
                    Card(
                        onClick = { viewModel.setMode(mode) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.setMode(mode) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(mode.title, fontWeight = FontWeight.Bold)
                                Text(mode.desc, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.startProcessing() },
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text("Prepare photo")
                    }
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text("Choose another photo")
                    }
                }
            }

            is PhotoUiState.Processing, is PhotoUiState.Saving, PhotoUiState.Canceling -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(64.dp))
                    val statusText = when (state) {
                        is PhotoUiState.Processing -> "Preparing photo... ${state.progress}%"
                        PhotoUiState.Saving -> "Saving to MediaStore..."
                        else -> "Canceling operation..."
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (state !is PhotoUiState.Canceling) {
                        OutlinedButton(onClick = { viewModel.cancelProcessing() }) {
                            Text("Cancel")
                        }
                    }
                }
            }

            is PhotoUiState.Saved -> {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    colors = CardDefaults.elevatedCardColors()
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        coil.compose.AsyncImage(
                            model = state.outputUri,
                            contentDescription = "Saved photo preview",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Text("Photo Optimized Successfully!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Output dimensions: ${state.outputWidth} × ${state.outputHeight}", style = MaterialTheme.typography.bodyMedium)
                        Text("Output size: ${state.outputSize / 1024} KB", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Saved to Pictures/StatusHD",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
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
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("Share")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { viewModel.reset() },
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("Choose another photo")
                        }
                    }
                }
            }

            is PhotoUiState.Error -> {
                Spacer(modifier = Modifier.height(48.dp))
                Button(
                    onClick = {
                        viewModel.reset()
                        photoPickerLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("Choose photo", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
