package com.techwin.statushd.feature.status

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedStatusScreen(
    viewModel: SavedStatusViewModel = viewModel(),
    onOptimizePhoto: (Uri) -> Unit = {},
    onOptimizeVideo: (Uri, Long, Int, Int, Int) -> Unit = { _, _, _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val treeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val docId = try { DocumentsContract.getTreeDocumentId(it) } catch (_: Exception) { "" }
            val folderName = if (docId.contains(":")) docId.substringAfter(":") else "Status Folder"
            viewModel.selectFolder(it, folderName)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        when (val state = uiState) {
            SavedStatusUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            SavedStatusUiState.NoFolderSelected -> {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Saved Status Viewer",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Select your status media folder to view and save statuses.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Folder Navigation Guidance", fontWeight = FontWeight.Bold)
                            Text(
                                "Open Internal storage → Android → media → com.whatsapp → WhatsApp → Media → .Statuses",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "For WhatsApp Business, locate under com.whatsapp.w4b",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { treeLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("Choose status folder", style = MaterialTheme.typography.titleMedium)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "StatusHD reads only the folder you select. StatusHD does not read chats, contacts or messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "StatusHD is an independent project and is not affiliated with, endorsed by or sponsored by WhatsApp or Meta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }
            }

            SavedStatusUiState.PermissionLost -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Folder access was removed. Choose the folder again.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { treeLauncher.launch(null) }) {
                        Text("Choose status folder")
                    }
                }
            }

            is SavedStatusUiState.FolderLoaded -> {
                // Header & Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.folderName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${state.items.size} item(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { viewModel.refreshItems() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    OutlinedButton(onClick = { viewModel.disconnectFolder() }) {
                        Text("Disconnect folder")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (state.items.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No viewed status media was found in this folder.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.items, key = { it.documentId }) { item ->
                            StatusGridItem(
                                item = item,
                                onClick = { viewModel.selectItem(item) }
                            )
                        }
                    }
                }

                // Detail Dialog
                state.selectedItem?.let { selected ->
                    StatusDetailDialog(
                        item = selected,
                        isSaving = state.isSaving,
                        savedUri = state.savedUri,
                        savedMessage = state.savedMessage,
                        onDismiss = { viewModel.selectItem(null) },
                        onSave = { viewModel.saveSelectedItem() },
                        onShare = {
                            state.savedUri?.let { uri ->
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = selected.mimeType
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri(null, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                try {
                                    context.startActivity(Intent.createChooser(intent, "Share Saved Status"))
                                } catch (_: Exception) {
                                    Toast.makeText(context, "No app available to share", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onOptimize = {
                            viewModel.selectItem(null)
                            if (selected.mediaType == StatusMediaType.PHOTO) {
                                onOptimizePhoto(selected.uri)
                            } else {
                                onOptimizeVideo(
                                    selected.uri,
                                    selected.durationMs,
                                    selected.width,
                                    selected.height,
                                    0
                                )
                            }
                        }
                    )
                }

                // Consent Dialog
                if (state.showConsentDialog) {
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissConsentDialog() },
                        title = { Text("Privacy & Copyright Notice", fontWeight = FontWeight.Bold) },
                        text = {
                            Text(
                                "Save only media you own or have permission from the creator and the people shown to keep. Viewing a status does not grant permission to repost it."
                            )
                        },
                        confirmButton = {
                            Button(onClick = { viewModel.confirmConsent() }) {
                                Text("I understand")
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { viewModel.dismissConsentDialog() }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }

            is SavedStatusUiState.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.checkAndLoadFolder() }) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
fun StatusGridItem(
    item: StatusMediaItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(180.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.uri,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Badge Top Right
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.65f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.mediaType == StatusMediaType.VIDEO) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        val sec = item.durationMs / 1000
                        Text(
                            text = "${sec}s",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        Text(
                            text = "PHOTO",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // Time Bottom Left
            if (item.lastModified > 0) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.65f)
                ) {
                    val dateStr = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(item.lastModified))
                    Text(
                        text = dateStr,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusDetailDialog(
    item: StatusMediaItem,
    isSaving: Boolean,
    savedUri: Uri?,
    savedMessage: String?,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onOptimize: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = "Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (item.mediaType == StatusMediaType.PHOTO) "Photo Status" else "Video Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Type: ${item.mimeType}", style = MaterialTheme.typography.bodySmall)
                    if (item.width > 0 && item.height > 0) {
                        Text("Dimensions: ${item.width} × ${item.height}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (item.durationMs > 0) {
                        Text("Duration: ${item.durationMs / 1000} seconds", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("File size: ${Formatter.formatShortFileSize(context, item.size)}", style = MaterialTheme.typography.bodySmall)
                    if (item.lastModified > 0) {
                        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(item.lastModified))
                        Text("Modified: $dateStr", style = MaterialTheme.typography.bodySmall)
                    }
                }

                savedMessage?.let { msg ->
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSave,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save to Gallery")
                        }

                        if (savedUri != null) {
                            Button(
                                onClick = onShare,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Share")
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onOptimize,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Optimize with StatusHD")
                    }
                }

                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}
