package com.techwin.statushd.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.techwin.statushd.BatchPhotoSelectionPolicy
import com.techwin.statushd.BatchPhotoViewModel
import com.techwin.statushd.PhotoViewModel
import com.techwin.statushd.VideoViewModel
import com.techwin.statushd.feature.status.SavedStatusViewModel
import com.techwin.statushd.ui.photo.BatchPhotoScreenContent
import com.techwin.statushd.ui.photo.PhotoScreenContent
import com.techwin.statushd.ui.saved.SavedStatusScreenContent
import com.techwin.statushd.ui.video.VideoScreenContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusHdApp(
    videoViewModel: VideoViewModel = viewModel(),
    photoViewModel: PhotoViewModel = viewModel(),
    batchPhotoViewModel: BatchPhotoViewModel = viewModel(),
    savedStatusViewModel: SavedStatusViewModel = viewModel()
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }
    var isBatchActive by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "StatusHD",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Private media preparation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MovieFilter,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAbout = true },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "About",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = "Video") },
                    label = { Text("Video", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSurface,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = "Photo") },
                    label = { Text("Photo", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSurface,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.BookmarkBorder, contentDescription = "Saved") },
                    label = { Text("Saved", fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSurface,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            when (selectedTab) {
                0 -> VideoScreenContent(viewModel = videoViewModel, paddingValues = paddingValues)
                1 -> if (isBatchActive) {
                    BatchPhotoScreenContent(
                        viewModel = batchPhotoViewModel,
                        paddingValues = paddingValues,
                        onBack = { isBatchActive = false }
                    )
                } else {
                    PhotoScreenContent(
                        viewModel = photoViewModel,
                        paddingValues = paddingValues,
                        onNavigateToBatch = { uris ->
                            val identifiers = uris.map { it.toString() }
                            val policyResult = BatchPhotoSelectionPolicy.validateAndDeduplicate(identifiers)
                            if (policyResult.isValidForStart) {
                                batchPhotoViewModel.initializeQueue(identifiers)
                                isBatchActive = true
                            } else {
                                Toast.makeText(context, "Select 2 to 10 photos for a batch.", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
                2 -> SavedStatusScreenContent(
                    viewModel = savedStatusViewModel,
                    paddingValues = paddingValues,
                    onNavigateToPhoto = { uri ->
                        photoViewModel.selectPhoto(uri)
                        selectedTab = 1
                    },
                    onNavigateToVideo = { uri, duration, w, h, bitrate ->
                        videoViewModel.selectVideo(uri, duration, w, h, bitrate)
                        selectedTab = 0
                    }
                )
            }
        }

        if (showAbout) {
            ModalBottomSheet(
                onDismissRequest = { showAbout = false },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("About StatusHD", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                    Text("StatusHD prepares media locally on your device.")
                    Text("Messaging platforms may apply additional compression.")
                    Text("StatusHD is an independent project and is not affiliated with, endorsed by or sponsored by WhatsApp or Meta.")
                }
            }
        }
    }
}
