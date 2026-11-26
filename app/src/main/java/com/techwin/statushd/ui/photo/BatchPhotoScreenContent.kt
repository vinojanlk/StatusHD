package com.techwin.statushd.ui.photo

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.techwin.statushd.BatchItemStatus
import com.techwin.statushd.BatchPhotoItem
import com.techwin.statushd.BatchPhotoViewModel
import com.techwin.statushd.BatchState
import com.techwin.statushd.ItemSkipReason
import com.techwin.statushd.ItemState
import com.techwin.statushd.PhotoQualityMode
import com.techwin.statushd.StopResult
import com.techwin.statushd.ui.components.QualityOptionCard
import com.techwin.statushd.ui.components.StatusPrimaryButton

@Composable
fun BatchPhotoScreenContent(
    viewModel: BatchPhotoViewModel,
    paddingValues: PaddingValues,
    onBack: () -> Unit
) {
    val snapshot by viewModel.snapshot.collectAsState()
    val context = LocalContext.current

    val currentSnapshot = snapshot
    if (currentSnapshot == null) {
        LaunchedEffect(Unit) {
            onBack()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val isRunningOrStopping = currentSnapshot.state == BatchState.Running || currentSnapshot.state == BatchState.Stopping

    var pendingNavigationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showStopConfirmDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = isRunningOrStopping || currentSnapshot.state == BatchState.Draft || currentSnapshot.state == BatchState.Finished) {
        if (isRunningOrStopping) {
            showStopConfirmDialog = true
        } else {
            viewModel.reset()
            onBack()
        }
    }

    if (showStopConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showStopConfirmDialog = false },
            title = { Text("Batch processing is active", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) },
            text = { Text("Stop batch processing after current photo and leave?") },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirmDialog = false
                        viewModel.stopAfterCurrent()
                        pendingNavigationAction = {
                            viewModel.reset()
                            onBack()
                        }
                    }
                ) {
                    Text("Stop after current and leave")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showStopConfirmDialog = false }) {
                    Text("Stay")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    LaunchedEffect(currentSnapshot.state, pendingNavigationAction) {
        if (currentSnapshot.state == BatchState.Finished && pendingNavigationAction != null) {
            val action = pendingNavigationAction
            pendingNavigationAction = null
            action?.invoke()
        }
    }

    AnimatedContent(
        targetState = currentSnapshot.state,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
        label = "BatchPhotoStateTransition"
    ) { state ->
        when (state) {
            BatchState.Draft -> {
                var selectedMode by remember { mutableStateOf(PhotoQualityMode.STATUS_HD) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = paddingValues.calculateTopPadding() + 20.dp,
                            start = 20.dp,
                            end = 20.dp,
                            bottom = paddingValues.calculateBottomPadding() + 24.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Batch Preparation",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${currentSnapshot.items.size} photos selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(currentSnapshot.items, key = { it.id }) { item ->
                            BatchDraftItemRow(
                                item = item,
                                onRemove = {
                                    val currentIdentifiers = currentSnapshot.items.map { it.sourceIdentifier }
                                    val newIdentifiers = currentIdentifiers - item.sourceIdentifier
                                    if (newIdentifiers.size < 2) {
                                        viewModel.reset()
                                        onBack()
                                    } else {
                                        viewModel.initializeQueue(newIdentifiers)
                                    }
                                }
                            )
                        }
                    }

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
                        isSelected = selectedMode == PhotoQualityMode.STATUS_HD,
                        onClick = { selectedMode = PhotoQualityMode.STATUS_HD }
                    )

                    QualityOptionCard(
                        title = "Smaller File",
                        badgeText = "Quality 80",
                        primaryText = "Reduced size",
                        secondaryText = "Best for faster sharing",
                        tertiaryText = "",
                        icon = Icons.Default.Compress,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        isSelected = selectedMode == PhotoQualityMode.SMALLER_FILE,
                        onClick = { selectedMode = PhotoQualityMode.SMALLER_FILE }
                    )

                    StatusPrimaryButton(
                        text = "Prepare ${currentSnapshot.items.size} photos",
                        onClick = { viewModel.startBatch(selectedMode) }
                    )
                }
            }

            BatchState.Running, BatchState.Stopping -> {
                val processingIndex = currentSnapshot.items.indexOfFirst { it.state == ItemState.Processing }
                    .takeIf { it >= 0 } ?: currentSnapshot.items.indexOfFirst { it.state == ItemState.Waiting }

                val displayIndex = if (processingIndex >= 0) processingIndex + 1 else currentSnapshot.items.size
                val total = currentSnapshot.items.size
                val summary = com.techwin.statushd.BatchPhotoSummary.fromItems(currentSnapshot.items)
                val progressPercent = summary.done.toFloat() / total

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = paddingValues.calculateTopPadding() + 20.dp,
                            start = 20.dp,
                            end = 20.dp,
                            bottom = paddingValues.calculateBottomPadding() + 24.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (state == BatchState.Stopping) "Stopping batch..." else "Preparing photos",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Item $displayIndex of $total",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LinearProgressIndicator(
                        progress = { progressPercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(currentSnapshot.items, key = { it.id }) { item ->
                            BatchStatusItemRow(item = item)
                        }
                    }

                    if (state == BatchState.Running) {
                        OutlinedButton(
                            onClick = { viewModel.stopAfterCurrent() },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("Stop after current")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { },
                            enabled = false,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("Stopping...")
                        }
                    }
                }
            }

            BatchState.Finished -> {
                val summary = com.techwin.statushd.BatchPhotoSummary.fromItems(currentSnapshot.items)
                val totalOutputSize = currentSnapshot.items.sumOf { it.outputData?.outputSize ?: 0L }
                val formattedSize = Formatter.formatShortFileSize(context, totalOutputSize)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = paddingValues.calculateTopPadding() + 20.dp,
                            start = 20.dp,
                            end = 20.dp,
                            bottom = paddingValues.calculateBottomPadding() + 24.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (summary.done > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Batch ready",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    } else {
                        Text(
                            text = "Batch finished",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Prepared: ${summary.done}", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
                            if (summary.failed > 0) {
                                Text("Failed: ${summary.failed}", color = MaterialTheme.colorScheme.error)
                            }
                            if (summary.skipped > 0) {
                                Text("Skipped: ${summary.skipped}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (summary.done > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Total output size: $formattedSize", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Saved to Pictures/StatusHD/Batch", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(currentSnapshot.items, key = { it.id }) { item ->
                            BatchStatusItemRow(item = item)
                        }
                    }

                    if (summary.done > 0) {
                        StatusPrimaryButton(
                            text = "Open Gallery",
                            icon = Icons.Default.PhotoLibrary,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    type = "image/*"
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (e: ActivityNotFoundException) {
                                    Toast.makeText(context, "No gallery app found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (summary.failed > 0) {
                            Button(
                                onClick = { viewModel.retryFailed() },
                                modifier = Modifier.weight(1f).height(56.dp),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Text("Retry failed")
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.reset()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text("Start another batch")
                    }
                }
            }
        }
    }
}

@Composable
fun BatchDraftItemRow(
    item: BatchPhotoItem,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Photo,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Photo ${item.originalOrderIndex + 1}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "Remove item", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BatchStatusItemRow(item: BatchPhotoItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Photo,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Photo ${item.originalOrderIndex + 1}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val statusText = when (item.state) {
                ItemState.Waiting -> "Waiting"
                ItemState.Processing -> "Processing..."
                ItemState.Done -> "Done"
                ItemState.Failed -> "Failed"
                ItemState.Skipped -> "Skipped"
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = when (item.state) {
                    ItemState.Done -> MaterialTheme.colorScheme.primary
                    ItemState.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        when (item.state) {
            ItemState.Done -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            ItemState.Failed -> Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
            ItemState.Processing -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            else -> {}
        }
    }
}
