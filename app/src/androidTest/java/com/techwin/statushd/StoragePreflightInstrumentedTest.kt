package com.techwin.statushd

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.techwin.statushd.storage.AndroidAvailableStorageProvider
import com.techwin.statushd.storage.AvailableStorageProvider
import com.techwin.statushd.storage.StorageAvailability
import com.techwin.statushd.storage.StoragePreflightTestHooks
import com.techwin.statushd.storage.StorageSettingsNavigator
import com.techwin.statushd.ui.theme.AppThemeMode
import com.techwin.statushd.ui.theme.ThemePreferenceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@RunWith(AndroidJUnit4::class)
class StoragePreflightInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repository: ThemePreferenceRepository
    private lateinit var fixtures: MediaStoreTestFixtures
    private var originalNightMode: String = "no"
    private var originalFontScale: String = "1.0"

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        repository = ThemePreferenceRepository(context)
        fixtures = MediaStoreTestFixtures(context)
        originalNightMode = readEmulatorNightMode()
        originalFontScale = readFontScale()
        StoragePreflightTestHooks.reset()
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManager.getInstance(context).pruneWork().result.get()
        repository.setThemeMode(AppThemeMode.SYSTEM)
    }

    @After
    fun tearDown() = runBlocking {
        try {
            StoragePreflightTestHooks.reset()
            WorkManager.getInstance(context).cancelAllWork().result.get()
            WorkManager.getInstance(context).pruneWork().result.get()
            repository.setThemeMode(AppThemeMode.SYSTEM)
            setEmulatorNightMode(originalNightMode)
            setFontScale(originalFontScale)
        } finally {
            fixtures.cleanup()
            assertEquals(
                "Cleanup must leave no lingering test-prefix rows in MediaStore",
                0,
                fixtures.countLingeringTestPrefixRows()
            )
        }
    }

    @Test
    fun realStatFs_returnsNonNegativeAvailableBytes() = runBlocking {
        val provider = AndroidAvailableStorageProvider(context)
        val availability = provider.getAvailability()
        assertNotNull("Real StatFs should return non-null internal cache availability", availability.internalCacheAvailableBytes)
        assertNotNull("Real StatFs should return non-null shared media availability", availability.sharedMediaAvailableBytes)
        assertTrue("Internal cache available must be >= 0", availability.internalCacheAvailableBytes!! >= 0L)
        assertTrue("Shared media available must be >= 0", availability.sharedMediaAvailableBytes!! >= 0L)
    }

    @Test
    fun sufficientStorage_startsSinglePhoto_andSavesToMediaStore() = runBlocking {
        val seededUri = fixtures.seedAsset("small_500x500.jpg")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var photoViewModel: PhotoViewModel
            scenario.onActivity { activity ->
                photoViewModel = ViewModelProvider(activity)[PhotoViewModel::class.java]
                clickComposeNodeWithText(activity, "Photo")
            }

            waitUntil(10_000L) {
                var onPhotoTab = false
                scenario.onActivity { activity ->
                    onPhotoTab = "Choose photo" in findAllComposeTexts(activity)
                }
                onPhotoTab
            }

            scenario.onActivity {
                photoViewModel.selectPhoto(seededUri)
            }

            waitUntil(10_000L) {
                var buttonVisible = false
                scenario.onActivity { activity ->
                    buttonVisible = photoViewModel.uiState.value is PhotoUiState.Selected &&
                        "Prepare photo" in findAllComposeTexts(activity)
                }
                buttonVisible
            }

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Prepare photo"))
            }

            waitUntil(20_000L) {
                photoViewModel.uiState.value is PhotoUiState.Saved
            }

            val saved = photoViewModel.uiState.value as PhotoUiState.Saved
            fixtures.trackUri(saved.outputUri)
            assertTrue(fixtures.reopenOutput(saved.outputUri))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun insufficientSharedProvider_blocksSinglePhoto_andCancelPreservesSelection() = runBlocking {
        val seededUri = fixtures.seedAsset("small_500x500.jpg")
        // Internal cache capacity is high, shared storage is 1024 bytes
        StoragePreflightTestHooks.availableStorageProviderFactory = {
            AvailableStorageProvider {
                StorageAvailability(
                    internalCacheAvailableBytes = 500L * 1024L * 1024L,
                    sharedMediaAvailableBytes = 1024L
                )
            }
        }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var photoViewModel: PhotoViewModel
            scenario.onActivity { activity ->
                photoViewModel = ViewModelProvider(activity)[PhotoViewModel::class.java]
                clickComposeNodeWithText(activity, "Photo")
            }

            waitUntil(10_000L) {
                var onPhotoTab = false
                scenario.onActivity { activity ->
                    onPhotoTab = "Choose photo" in findAllComposeTexts(activity)
                }
                onPhotoTab
            }

            scenario.onActivity {
                photoViewModel.selectPhoto(seededUri)
            }
            waitUntil(10_000L) {
                var buttonVisible = false
                scenario.onActivity { activity ->
                    buttonVisible = photoViewModel.uiState.value is PhotoUiState.Selected &&
                        "Prepare photo" in findAllComposeTexts(activity)
                }
                buttonVisible
            }

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Prepare photo"))
            }

            waitUntil(10_000L) {
                var hasDialog = false
                scenario.onActivity { activity ->
                    val texts = findAllComposeTexts(activity)
                    hasDialog = "Not enough storage" in texts &&
                        texts.any { it.startsWith("StatusHD needs about ") && it.contains("free shared storage") } &&
                        "Manage storage" in texts &&
                        "Cancel" in texts
                }
                hasDialog
            }

            assertTrue(photoViewModel.uiState.value is PhotoUiState.Selected)

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Cancel"))
            }

            waitUntil(10_000L) {
                var dialogGone = false
                scenario.onActivity { activity ->
                    dialogGone = "Not enough storage" !in findAllComposeTexts(activity)
                }
                dialogGone
            }

            val finalState = photoViewModel.uiState.value
            assertTrue(finalState is PhotoUiState.Selected)
            assertEquals(seededUri, (finalState as PhotoUiState.Selected).uri)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun insufficientSharedProvider_blocksBatchBeforeRunning_andPreservesDraft() = runBlocking {
        StoragePreflightTestHooks.availableStorageProviderFactory = {
            AvailableStorageProvider {
                StorageAvailability(
                    internalCacheAvailableBytes = 500L * 1024L * 1024L,
                    sharedMediaAvailableBytes = 1024L
                )
            }
        }

        val uri1 = fixtures.seedAsset("landscape_4000x3000.jpg")
        val uri2 = fixtures.seedAsset("portrait_2400x3200.jpg")
        val uri3 = fixtures.seedAsset("small_500x500.jpg")

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntil(10_000L) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = "Photo" in findAllComposeTexts(activity)
                }
                ready
            }

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Photo"))
            }

            waitUntil(10_000L) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = "Prepare multiple photos" in findAllComposeTexts(activity)
                }
                ready
            }

            val resultIntent = Intent().apply {
                clipData = ClipData.newUri(context.contentResolver, "batch_photos", uri1).apply {
                    addItem(ClipData.Item(uri2))
                    addItem(ClipData.Item(uri3))
                }
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val monitor = instrumentation.addMonitor(
                null as IntentFilter?,
                Instrumentation.ActivityResult(Activity.RESULT_OK, resultIntent),
                true
            )

            try {
                scenario.onActivity { activity ->
                    assertTrue(clickComposeNodeWithText(activity, "Prepare multiple photos"))
                }
                waitUntil(10_000L) {
                    var onBatchDraft = false
                    scenario.onActivity { activity ->
                        onBatchDraft = "Prepare 3 photos" in findAllComposeTexts(activity)
                    }
                    onBatchDraft
                }
            } finally {
                instrumentation.removeMonitor(monitor)
            }

            lateinit var batchViewModel: BatchPhotoViewModel
            scenario.onActivity { activity ->
                batchViewModel = ViewModelProvider(activity)[BatchPhotoViewModel::class.java]
            }

            waitUntil(10_000L) {
                val snap = batchViewModel.snapshot.value
                snap != null && snap.state == BatchState.Draft && snap.items.size == 3
            }

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Prepare 3 photos"))
            }

            waitUntil(10_000L) {
                var hasDialog = false
                scenario.onActivity { activity ->
                    val texts = findAllComposeTexts(activity)
                    hasDialog = "Not enough storage" in texts &&
                        texts.any { it.contains("free shared storage") } &&
                        "Manage storage" in texts &&
                        "Cancel" in texts
                }
                hasDialog
            }

            val snapshot = batchViewModel.snapshot.value!!
            assertEquals(BatchState.Draft, snapshot.state)
            assertNull(snapshot.lockedMode)
            assertTrue(snapshot.items.all { it.state == ItemState.Waiting })

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Cancel"))
            }

            waitUntil(10_000L) {
                var gone = false
                scenario.onActivity { activity ->
                    gone = "Not enough storage" !in findAllComposeTexts(activity)
                }
                gone
            }

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Smaller File"))
            }

            waitUntil(10_000L) {
                batchViewModel.draftMode.value == PhotoQualityMode.SMALLER_FILE
            }
            assertEquals(BatchState.Draft, batchViewModel.snapshot.value?.state)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun insufficientInternalCache_blocksVideoBeforeWorkManagerEnqueue() = runBlocking {
        // Internal cache is 1024 bytes, shared storage is plenty
        StoragePreflightTestHooks.availableStorageProviderFactory = {
            AvailableStorageProvider {
                StorageAvailability(
                    internalCacheAvailableBytes = 1024L,
                    sharedMediaAvailableBytes = 500L * 1024L * 1024L
                )
            }
        }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var videoViewModel: VideoViewModel
            val dummyVideoUri = Uri.parse("content://media/external/video/media/999999")
            scenario.onActivity { activity ->
                videoViewModel = ViewModelProvider(activity)[VideoViewModel::class.java]
                videoViewModel.selectVideo(
                    uri = dummyVideoUri,
                    duration = 25_000L,
                    width = 1080,
                    height = 1920,
                    bitrateKbps = 4500
                )
            }

            waitUntil(10_000L) {
                var buttonReady = false
                scenario.onActivity { activity ->
                    buttonReady = "Prepare video" in findAllComposeTexts(activity)
                }
                buttonReady
            }

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Prepare video"))
            }

            waitUntil(10_000L) {
                var hasDialog = false
                scenario.onActivity { activity ->
                    val texts = findAllComposeTexts(activity)
                    hasDialog = "Not enough storage" in texts &&
                        texts.any { it.contains("temporary app storage to prepare this video") }
                }
                hasDialog
            }

            assertFalse(videoViewModel.uiState.value.isProcessing)
            val enqueued = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("status_video_transcode")
                .get()
            assertTrue("WorkManager must not enqueue work when storage is insufficient", enqueued.isEmpty())

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Cancel"))
            }

            waitUntil(10_000L) {
                var gone = false
                scenario.onActivity { activity ->
                    gone = "Not enough storage" !in findAllComposeTexts(activity)
                }
                gone
            }
            assertEquals(dummyVideoUri, videoViewModel.uiState.value.selectedVideoUri)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun unavailableRequiredVolume_offersContinue_andProceedsWhenClicked() = runBlocking {
        val seededUri = fixtures.seedAsset("small_500x500.jpg")
        StoragePreflightTestHooks.availableStorageProviderFactory = {
            AvailableStorageProvider {
                StorageAvailability(
                    internalCacheAvailableBytes = null,
                    sharedMediaAvailableBytes = null
                )
            }
        }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var photoViewModel: PhotoViewModel
            scenario.onActivity { activity ->
                photoViewModel = ViewModelProvider(activity)[PhotoViewModel::class.java]
                clickComposeNodeWithText(activity, "Photo")
            }

            waitUntil(10_000L) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = "Choose photo" in findAllComposeTexts(activity)
                }
                ready
            }

            scenario.onActivity {
                photoViewModel.selectPhoto(seededUri)
            }
            waitUntil(10_000L) {
                var buttonVisible = false
                scenario.onActivity { activity ->
                    buttonVisible = photoViewModel.uiState.value is PhotoUiState.Selected &&
                        "Prepare photo" in findAllComposeTexts(activity)
                }
                buttonVisible
            }

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Prepare photo"))
            }

            waitUntil(10_000L) {
                var hasUnavailableDialog = false
                scenario.onActivity { activity ->
                    val texts = findAllComposeTexts(activity)
                    hasUnavailableDialog = "StatusHD could not check available storage." in texts &&
                        "Continue" in texts &&
                        "Cancel" in texts
                }
                hasUnavailableDialog
            }

            assertTrue(photoViewModel.uiState.value is PhotoUiState.Selected)

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Continue"))
            }

            waitUntil(20_000L) {
                photoViewModel.uiState.value is PhotoUiState.Saved
            }

            val saved = photoViewModel.uiState.value as PhotoUiState.Saved
            fixtures.trackUri(saved.outputUri)
            assertTrue(fixtures.reopenOutput(saved.outputUri))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun manageStorageIntent_resolves_andLaunchesSettingsWithoutStartingProcessing() = runBlocking {
        val intent = StorageSettingsNavigator.createManageStorageIntent()
        assertEquals(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, intent.action)
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertNotNull("ACTION_INTERNAL_STORAGE_SETTINGS must resolve on Android", resolved)

        StoragePreflightTestHooks.availableStorageProviderFactory = {
            AvailableStorageProvider {
                StorageAvailability(
                    internalCacheAvailableBytes = 1024L,
                    sharedMediaAvailableBytes = 1024L
                )
            }
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(
            IntentFilter(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, Intent()),
            true
        )

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var videoViewModel: VideoViewModel
            val dummyVideoUri = Uri.parse("content://media/external/video/media/999998")
            scenario.onActivity { activity ->
                videoViewModel = ViewModelProvider(activity)[VideoViewModel::class.java]
                videoViewModel.selectVideo(dummyVideoUri, 20_000L, 1080, 1920, 4500)
            }

            waitUntil(10_000L) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = "Prepare video" in findAllComposeTexts(activity)
                }
                ready
            }

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Prepare video"))
            }

            waitUntil(10_000L) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = "Manage storage" in findAllComposeTexts(activity)
                }
                ready
            }

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Manage storage"))
            }

            waitUntil(10_000L) {
                monitor.hits >= 1
            }

            assertEquals(1, monitor.hits)
            assertFalse(videoViewModel.uiState.value.isProcessing)
            assertEquals(dummyVideoUri, videoViewModel.uiState.value.selectedVideoUri)
        } finally {
            instrumentation.removeMonitor(monitor)
            scenario.close()
        }
    }

    @Test
    fun lightThemeDialogContrast_fontScale13_andThemeOrbUnaffected() = runBlocking {
        setEmulatorNightMode("no")
        repository.setThemeMode(AppThemeMode.LIGHT)
        StoragePreflightTestHooks.availableStorageProviderFactory = {
            AvailableStorageProvider {
                StorageAvailability(
                    internalCacheAvailableBytes = 1024L,
                    sharedMediaAvailableBytes = 1024L
                )
            }
        }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var videoViewModel: VideoViewModel
            scenario.onActivity { activity ->
                videoViewModel = ViewModelProvider(activity)[VideoViewModel::class.java]
                videoViewModel.selectVideo(
                    Uri.parse("content://media/external/video/media/999997"),
                    25_000L,
                    1080,
                    1920,
                    4500
                )
            }

            waitUntil(10_000L) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = "Prepare video" in findAllComposeTexts(activity) &&
                        "Appearance: Light" in findAllContentDescriptions(activity)
                }
                ready
            }

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Prepare video"))
            }

            waitUntil(10_000L) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = "Not enough storage" in findAllComposeTexts(activity)
                }
                ready
            }

            verifyDialogLuminanceAndContrast(expectDark = false)

            setFontScale("1.3")
            waitUntil(10_000L) {
                var noClipping = false
                scenario.onActivity { activity ->
                    val titleBounds = findUnmergedTextBoundsInWindow(activity, "Not enough storage")
                    val manageBounds = findUnmergedTextBoundsInWindow(activity, "Manage storage")
                    val cancelBounds = findUnmergedTextBoundsInWindow(activity, "Cancel")
                    noClipping = titleBounds != null &&
                        manageBounds != null &&
                        cancelBounds != null &&
                        titleBounds.height() > 0 &&
                        manageBounds.height() > 0 &&
                        cancelBounds.height() > 0 &&
                        titleBounds.bottom <= manageBounds.top &&
                        titleBounds.bottom <= cancelBounds.top &&
                        "Appearance: Light" in findAllContentDescriptions(activity)
                }
                noClipping
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun darkThemeDialogContrast() = runBlocking {
        setEmulatorNightMode("yes")
        repository.setThemeMode(AppThemeMode.DARK)
        StoragePreflightTestHooks.availableStorageProviderFactory = {
            AvailableStorageProvider {
                StorageAvailability(
                    internalCacheAvailableBytes = 1024L,
                    sharedMediaAvailableBytes = 1024L
                )
            }
        }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var videoViewModel: VideoViewModel
            scenario.onActivity { activity ->
                videoViewModel = ViewModelProvider(activity)[VideoViewModel::class.java]
                videoViewModel.selectVideo(
                    Uri.parse("content://media/external/video/media/999996"),
                    25_000L,
                    1080,
                    1920,
                    4500
                )
            }

            waitUntil(10_000L) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = "Prepare video" in findAllComposeTexts(activity)
                }
                ready
            }

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Prepare video"))
            }

            waitUntil(10_000L) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = "Not enough storage" in findAllComposeTexts(activity)
                }
                ready
            }

            verifyDialogLuminanceAndContrast(expectDark = true)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun rotationWhileDialogIsVisible_doesNotStartProcessing_andPreservesDialog() = runBlocking {
        StoragePreflightTestHooks.availableStorageProviderFactory = {
            AvailableStorageProvider {
                StorageAvailability(
                    internalCacheAvailableBytes = 1024L,
                    sharedMediaAvailableBytes = 1024L
                )
            }
        }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var videoViewModel: VideoViewModel
            val dummyVideoUri = Uri.parse("content://media/external/video/media/999995")
            scenario.onActivity { activity ->
                videoViewModel = ViewModelProvider(activity)[VideoViewModel::class.java]
                videoViewModel.selectVideo(dummyVideoUri, 25_000L, 1080, 1920, 4500)
            }

            waitUntil(10_000L) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = "Prepare video" in findAllComposeTexts(activity)
                }
                ready
            }

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Prepare video"))
            }

            waitUntil(10_000L) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = "Not enough storage" in findAllComposeTexts(activity)
                }
                ready
            }

            // Simulate configuration change / rotation
            scenario.recreate()

            waitUntil(10_000L) {
                var stillVisible = false
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[VideoViewModel::class.java]
                    val texts = findAllComposeTexts(activity)
                    stillVisible = "Not enough storage" in texts &&
                        "Manage storage" in texts &&
                        "Cancel" in texts &&
                        !vm.uiState.value.isProcessing
                }
                stillVisible
            }

            val enqueued = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("status_video_transcode")
                .get()
            assertTrue("Rotation while dialog is visible must not enqueue WorkManager work", enqueued.isEmpty())
        } finally {
            scenario.close()
        }
    }

    @Test
    fun videoProcessing_hidesPrepareActionBar_andAllowsBackgroundNavigationWithConfirmation() = runBlocking {
        val seededVideoUri = fixtures.seedAsset("small_500x500.jpg") // Valid content URI in test scope

        StoragePreflightTestHooks.availableStorageProviderFactory = {
            AvailableStorageProvider {
                StorageAvailability(
                    internalCacheAvailableBytes = 500L * 1024L * 1024L,
                    sharedMediaAvailableBytes = 500L * 1024L * 1024L
                )
            }
        }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var videoViewModel: VideoViewModel
            scenario.onActivity { activity ->
                videoViewModel = ViewModelProvider(activity)[VideoViewModel::class.java]
                videoViewModel.selectVideo(seededVideoUri, 25_000L, 1080, 1920, 4500)
            }

            waitUntil(10_000L) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = "Prepare video" in findAllComposeTexts(activity)
                }
                ready
            }

            // Click Prepare video to start processing
            scenario.onActivity { activity ->
                clickComposeNodeWithText(activity, "Prepare video")
            }

            // WorkManager starts running VideoWorker in background. VideoViewModel sets isProcessing = true immediately
            waitUntil(15_000L) {
                var processingUi = false
                scenario.onActivity { activity ->
                    val texts = findAllComposeTexts(activity)
                    processingUi = "Preparing your video" in texts &&
                        "Cancel" in texts &&
                        "Prepare video" !in texts
                }
                processingUi
            }

            // Attempt bottom-navigation to Photo tab while processing
            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Photo"))
            }

            // Dialog appears
            waitUntil(10_000L) {
                var hasDialog = false
                scenario.onActivity { activity ->
                    val texts = findAllComposeTexts(activity)
                    hasDialog = "Video preparation is active" in texts &&
                        "Leave this screen while StatusHD continues preparing the video?" in texts &&
                        "Stay" in texts &&
                        "Continue in background" in texts
                }
                hasDialog
            }

            // Click Stay: remains on Video processing screen
            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Stay"))
            }

            waitUntil(10_000L) {
                var stillProcessing = false
                scenario.onActivity { activity ->
                    val texts = findAllComposeTexts(activity)
                    stillProcessing = "Video preparation is active" !in texts &&
                        ("Preparing your video" in texts || videoViewModel.uiState.value.isProcessing)
                }
                stillProcessing
            }

            // Attempt bottom-navigation to Photo again and choose Continue in background
            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Photo"))
            }

            waitUntil(10_000L) {
                var hasDialog = false
                scenario.onActivity { activity ->
                    hasDialog = "Video preparation is active" in findAllComposeTexts(activity)
                }
                hasDialog
            }

            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Continue in background"))
            }

            // Navigates to Photo tab while WorkManager continues
            waitUntil(10_000L) {
                var onPhotoTab = false
                scenario.onActivity { activity ->
                    onPhotoTab = "Choose photo" in findAllComposeTexts(activity)
                }
                onPhotoTab
            }

            // Navigate back to Video tab
            scenario.onActivity { activity ->
                assertTrue(clickComposeNodeWithText(activity, "Video"))
            }

            // Verify returning to Video reflects current state without crashing
            waitUntil(10_000L) {
                var onVideo = false
                scenario.onActivity { activity ->
                    val texts = findAllComposeTexts(activity)
                    onVideo = "Video STATUS" in texts ||
                        "Selected Video" in texts ||
                        "Preparing your video" in texts ||
                        "Video ready" in texts ||
                        "Some parts are ready" in texts
                }
                onVideo
            }
        } finally {
            scenario.close()
        }
    }

    private suspend fun verifyDialogLuminanceAndContrast(expectDark: Boolean) {
        waitUntil(10_000L) {
            var matched: Boolean
            var capturedBitmap: Bitmap? = null
            var titleBounds: Rect? = null

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val roots = WindowInspector.getGlobalWindowViews()
                val dialogRoot = roots.lastOrNull()
                if (dialogRoot != null) {
                    for (node in collectSemanticsFromRoot(dialogRoot, mergingEnabled = false)) {
                        val texts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
                        if ("Not enough storage" in texts && node.children.isEmpty()) {
                            val r = node.boundsInWindow
                            titleBounds = Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                            break
                        }
                    }
                    if (titleBounds != null) {
                        capturedBitmap = captureViewBitmap(dialogRoot)
                    }
                }
            }

            val bitmap = capturedBitmap ?: return@waitUntil false
            val bounds = titleBounds ?: run {
                bitmap.recycle()
                return@waitUntil false
            }

            try {
                // Sample dialog container background just above the title text
                val bgX = bounds.centerX().coerceIn(4, bitmap.width - 5)
                val bgY = (bounds.top - 12).coerceIn(4, bitmap.height - 5)
                val bgPixel = bitmap.getPixel(bgX, bgY)
                val bgLum = ColorUtils.calculateLuminance(bgPixel)

                // Find highest-contrast foreground pixel inside the title bounding box
                var bestContrast = 0.0
                val left = bounds.left.coerceIn(0, bitmap.width - 1)
                val right = bounds.right.coerceIn(left, bitmap.width - 1)
                val top = bounds.top.coerceIn(0, bitmap.height - 1)
                val bottom = bounds.bottom.coerceIn(top, bitmap.height - 1)
                for (y in top..bottom step 2) {
                    for (x in left..right step 2) {
                        val c = ColorUtils.calculateContrast(bitmap.getPixel(x, y), bgPixel)
                        if (c > bestContrast) bestContrast = c
                    }
                }

                val themeMatches = if (expectDark) bgLum < 0.15 else bgLum > 0.75
                matched = themeMatches && bestContrast >= 4.5
            } finally {
                bitmap.recycle()
            }
            matched
        }
    }

    private fun captureViewBitmap(rootView: View): Bitmap? {
        val width = rootView.width
        val height = rootView.height
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val handlerThread = HandlerThread("DialogPixelCopyThread").apply { start() }
        return try {
            val latch = CountDownLatch(1)
            var copyResult = PixelCopy.ERROR_UNKNOWN
            val contextWindow = (rootView.context as? Activity)?.window
            if (contextWindow != null && rootView == contextWindow.decorView) {
                PixelCopy.request(
                    contextWindow,
                    bitmap,
                    { result ->
                        copyResult = result
                        latch.countDown()
                    },
                    Handler(handlerThread.looper)
                )
                latch.await(2, TimeUnit.SECONDS)
                if (copyResult == PixelCopy.SUCCESS) bitmap else {
                    bitmap.recycle()
                    null
                }
            } else {
                val canvas = Canvas(bitmap)
                rootView.draw(canvas)
                bitmap
            }
        } finally {
            handlerThread.quitSafely()
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: suspend () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            delay(40.milliseconds)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    private fun readEmulatorNightMode(): String {
        val output = executeShell("cmd uimode night")
        val value = output.substringAfter("Night mode:", "").trim().lowercase()
        return when (value) {
            "yes", "no", "auto" -> value
            else -> "no"
        }
    }

    private fun setEmulatorNightMode(mode: String) {
        val normalized = when (mode) {
            "yes", "no", "auto" -> mode
            else -> "no"
        }
        executeShell("cmd uimode night $normalized")
    }

    private fun readFontScale(): String {
        val output = executeShell("settings get system font_scale").trim()
        return if (output.toFloatOrNull() != null) output else "1.0"
    }

    private fun setFontScale(scale: String) {
        val value = scale.toFloatOrNull() ?: 1.0f
        executeShell("settings put system font_scale $value")
    }

    private fun executeShell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return pfd.use { descriptor ->
            BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(descriptor))).use { reader ->
                reader.readText()
            }
        }
    }

    private fun collectSemanticsFromRoot(root: View, mergingEnabled: Boolean): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun traverse(view: View) {
            if (view is ViewRootForTest) {
                result.addAll(view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = mergingEnabled))
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    traverse(view.getChildAt(i))
                }
            }
        }
        traverse(root)
        return result
    }

    private fun collectAllSemanticsNodes(
        activity: Activity,
        mergingEnabled: Boolean = true
    ): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        val roots = (WindowInspector.getGlobalWindowViews() + activity.window.decorView).distinct()
        for (root in roots) {
            result.addAll(collectSemanticsFromRoot(root, mergingEnabled))
        }
        return result
    }

    private fun findAllComposeTexts(activity: Activity): List<String> {
        val texts = mutableListOf<String>()
        for (node in collectAllSemanticsNodes(activity)) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { annotated ->
                texts.add(annotated.text)
            }
        }
        return texts
    }

    private fun findAllContentDescriptions(activity: Activity): List<String> {
        val descriptions = mutableListOf<String>()
        for (node in collectAllSemanticsNodes(activity)) {
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { desc ->
                descriptions.add(desc)
            }
        }
        return descriptions
    }

    private fun findUnmergedTextBoundsInWindow(activity: Activity, label: String): Rect? {
        for (node in collectAllSemanticsNodes(activity, mergingEnabled = false)) {
            val nodeTexts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
            if (label in nodeTexts && node.children.isEmpty()) {
                val rect = node.boundsInWindow
                return Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            }
        }
        return null
    }

    private fun clickComposeNodeWithText(activity: Activity, label: String): Boolean {
        for (node in collectAllSemanticsNodes(activity)) {
            val nodeTexts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
            if (label in nodeTexts) {
                val onClick = node.config.getOrNull(SemanticsActions.OnClick)
                if (onClick != null && onClick.action?.invoke() == true) {
                    return true
                }
            }
        }
        return false
    }
}
