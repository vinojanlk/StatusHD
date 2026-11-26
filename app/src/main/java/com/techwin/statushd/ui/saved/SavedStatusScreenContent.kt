package com.techwin.statushd.ui.saved

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.techwin.statushd.feature.status.SavedStatusUiState
import com.techwin.statushd.feature.status.SavedStatusViewModel
import com.techwin.statushd.feature.status.StatusMediaItem
import com.techwin.statushd.feature.status.StatusMediaType
import com.techwin.statushd.ui.components.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedStatusScreenContent(
    viewModel: SavedStatusViewModel,
    paddingValues: PaddingValues,
    onNavigateToPhoto: (Uri) -> Unit,
    onNavigateToVideo: (Uri, Long, Int, Int, Int) -> Unit
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

    AnimatedContent(
        targetState = uiState,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
        label = "SavedStatusTransition"
    ) { state ->
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            when (state) {
                SavedStatusUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                SavedStatusUiState.NoFolderSelected -> {
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
                        verticalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        StatusHeroCard(
                            badgeText = "SAVED MEDIA",
                            title = "Keep a copy of viewed media",
                            description = "Choose an accessible media folder, then save selected photos or videos to Gallery.",
                            gradientColors = listOf(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f), MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                            buttonText = "Choose status folder",
                            buttonIcon = Icons.Default.FolderOpen,
                            onButtonClick = { treeLauncher.launch(null) },
                            graphicContent = {
                                SavedHeroGraphic()
                            }
                        )

                        PrivacyStrip(
                            icon = Icons.Default.Shield,
                            title = "Folder access only",
                            subtitle = "StatusHD can read only the folder selected through Android."
                        )

                        var expanded by remember { mutableStateOf(false) }
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Where is the folder?",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Icon(
                                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (expanded) "Collapse" else "Expand",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (expanded) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        "Internal storage\n→ Android\n→ media\n→ com.whatsapp\n→ WhatsApp\n→ Media\n→ .Statuses\n\nFor WhatsApp Business, locate under com.whatsapp.w4b\n\nAvailability depends on the device and app version.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Save only media you own or have permission to keep.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                SavedStatusUiState.PermissionLost -> {
                    Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                        ErrorCard(
                            message = "The selected folder is no longer available.",
                            onRetry = { treeLauncher.launch(null) }
                        )
                    }
                }

                is SavedStatusUiState.FolderLoaded -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                top = paddingValues.calculateTopPadding() + 16.dp,
                                start = 20.dp,
                                end = 20.dp
                            )
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                        Row(
                            modifier = Modifier.padding(20.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = state.folderName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TonalBadge(text = "Connected")
                                }
                                Text(
                                    text = "${state.items.size} items",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row {
                                IconButton(onClick = { viewModel.refreshItems() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh folder", tint = MaterialTheme.colorScheme.primary)
                                }
                                var menuExpanded by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Change folder", color = MaterialTheme.colorScheme.onBackground) },
                                            onClick = { menuExpanded = false; treeLauncher.launch(null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Disconnect", color = MaterialTheme.colorScheme.error) },
                                            onClick = { menuExpanded = false; viewModel.disconnectFolder() }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Recent media",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (state.items.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
                            EmptyState(
                                icon = Icons.Default.SearchOff,
                                title = "No supported media found",
                                description = "View a status first, then refresh this folder."
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = paddingValues.calculateBottomPadding() + 24.dp)
                        ) {
                            items(state.items, key = { it.documentId }) { item ->
                                StatusGridItem(
                                    item = item,
                                    onClick = { viewModel.selectItem(item) }
                                )
                            }
                        }
                    }
                } // Close FolderLoaded wrapper

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
                                    onNavigateToPhoto(selected.uri)
                                } else {
                                    onNavigateToVideo(
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
                            title = { Text("Privacy & Copyright Notice", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) },
                            text = {
                                Text("Save only media you own or have permission from the creator and the people shown to keep. Viewing a status does not grant permission to repost it.")
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
                            },
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is SavedStatusUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ErrorCard(
                            message = state.message,
                            onRetry = { viewModel.checkAndLoadFolder() }
                        )
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
        modifier = Modifier.fillMaxWidth().aspectRatio(4f/5f).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.uri,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Gradient Overlay at bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .height(80.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
            )

            // Badge Top Right
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.7f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.mediaType == StatusMediaType.VIDEO) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
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
                val dateStr = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(item.lastModified))
                Text(
                    text = dateStr,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                )
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
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = "Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    if (item.mediaType == StatusMediaType.VIDEO) {
                        Icon(
                            imageVector = Icons.Default.PlayCircle,
                            contentDescription = "Play",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (item.mediaType == StatusMediaType.PHOTO) "Photo Status" else "Video Status",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text("Type: ${item.mimeType}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.width > 0 && item.height > 0) {
                        Text("Dimensions: ${item.width} × ${item.height}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.durationMs > 0) {
                        Text("Duration: ${item.durationMs / 1000} seconds", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("File size: ${Formatter.formatShortFileSize(context, item.size)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp), color = MaterialTheme.colorScheme.primary)
                        Text("Saving to Gallery", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    }
                } else {
                    if (savedUri != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(18.dp)) {
                                    Text("Back to saved media")
                                }
                                StatusPrimaryButton(text = "Share", icon = Icons.Default.Share, onClick = onShare, modifier = Modifier.weight(1f))
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatusPrimaryButton(
                                text = "Save to Gallery",
                                icon = Icons.Default.Download,
                                onClick = onSave
                            )
                            OutlinedButton(
                                onClick = onOptimize,
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text("Optimize with StatusHD", style = MaterialTheme.typography.titleMedium)
                            }
                            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
