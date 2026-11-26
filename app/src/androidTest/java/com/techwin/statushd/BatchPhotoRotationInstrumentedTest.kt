package com.techwin.statushd

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchPhotoRotationInstrumentedTest {

    private lateinit var context: Context
    private lateinit var fixtures: MediaStoreTestFixtures

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixtures = MediaStoreTestFixtures(context)
    }

    @After
    fun tearDown() {
        fixtures.cleanup()
        assertEquals(
            "Cleanup must leave no lingering test-prefix rows in MediaStore",
            0,
            fixtures.countLingeringTestPrefixRows()
        )
    }

    @Test
    fun batchPhotoProcessing_survivesActivityRecreationAndPublishesThreeOutputs() = runBlocking {
        val initialBatchCount = fixtures.queryRowsByRelativePath("Pictures/StatusHD/Batch/").size
        val initialPendingCount = fixtures.countPendingRows()

        // 1. Seed three valid JPEGs
        val uri1 = fixtures.seedAsset("landscape_4000x3000.jpg")
        val uri2 = fixtures.seedAsset("portrait_2400x3200.jpg")
        val uri3 = fixtures.seedAsset("landscape_4000x3000.jpg")

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            // 2. Navigate to Photo tab and deliver seeded URIs to Batch Photo without launching system Photo Picker
            waitUntil(timeoutMs = 10_000L) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = "Photo" in findComposeTexts(activity)
                }
                ready
            }

            scenario.onActivity { activity ->
                assertTrue("Expected Photo tab to be clickable", clickComposeNodeWithText(activity, "Photo"))
            }

            waitUntil(timeoutMs = 10_000L) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = "Prepare multiple photos" in findComposeTexts(activity)
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
                    assertTrue(
                        "Expected Prepare multiple photos button to be clickable",
                        clickComposeNodeWithText(activity, "Prepare multiple photos")
                    )
                }
                waitUntil(timeoutMs = 10_000L) {
                    var onBatchDraft = false
                    scenario.onActivity { activity ->
                        onBatchDraft = "Prepare 3 photos" in findComposeTexts(activity)
                    }
                    onBatchDraft
                }
            } finally {
                instrumentation.removeMonitor(monitor)
            }

            lateinit var initialViewModel: BatchPhotoViewModel
            scenario.onActivity { activity ->
                initialViewModel = ViewModelProvider(activity)[BatchPhotoViewModel::class.java]
            }

            waitUntil(timeoutMs = 10_000L) {
                val snap = initialViewModel.snapshot.value
                snap != null && snap.state == BatchState.Draft && snap.items.size == 3
            }

            // Observe Running + 1 Processing immediately when emitted
            val runningWithProcessing = CompletableDeferred<Unit>()
            val observerJob = launch(Dispatchers.Main.immediate) {
                initialViewModel.snapshot.collect { snap ->
                    if (snap != null &&
                        snap.state == BatchState.Running &&
                        snap.items.count { it.state == ItemState.Processing } == 1
                    ) {
                        runningWithProcessing.complete(Unit)
                    }
                }
            }

            try {
                // 3. Start Status HD batch (Status HD is selected by default)
                scenario.onActivity { activity ->
                    assertTrue(
                        "Expected Prepare 3 photos button to be clickable",
                        clickComposeNodeWithText(activity, "Prepare 3 photos")
                    )
                }

                // 4. Confirm state reaches Running with one item Processing
                withTimeout(10_000L) {
                    runningWithProcessing.await()
                }
            } finally {
                observerJob.cancel()
            }

            // 5. Recreate MainActivity using ActivityScenario.recreate()
            scenario.recreate()

            // 6. Confirm Batch Photo screen remains active after recreation
            waitUntil(timeoutMs = 10_000L) {
                var batchScreenVisible = false
                scenario.onActivity { activity ->
                    val texts = findComposeTexts(activity)
                    val isBatchUi = "Preparing photos" in texts ||
                        "Stopping batch..." in texts ||
                        "Batch ready" in texts
                    val isSinglePhotoUi = "Sharper photos, lighter files" in texts
                    batchScreenVisible = isBatchUi && !isSinglePhotoUi
                }
                batchScreenVisible
            }

            lateinit var recreatedViewModel: BatchPhotoViewModel
            scenario.onActivity { activity ->
                recreatedViewModel = ViewModelProvider(activity)[BatchPhotoViewModel::class.java]
            }

            // 7. Allow processing to finish
            waitUntil(timeoutMs = 30_000L) {
                var finishedUiAndState = false
                if (recreatedViewModel.snapshot.value?.state == BatchState.Finished) {
                    scenario.onActivity { activity ->
                        val texts = findComposeTexts(activity)
                        finishedUiAndState = "Batch ready" in texts && "Prepared: 3" in texts
                    }
                }
                finishedUiAndState
            }

            // 8. Confirm final state Finished and 3 Done, 0 Failed, 0 Skipped
            val finalSnapshot = recreatedViewModel.snapshot.value
            assertNotNull("Final snapshot must not be null after recreation", finalSnapshot)
            assertEquals(BatchState.Finished, finalSnapshot!!.state)
            assertEquals(PhotoQualityMode.STATUS_HD, finalSnapshot.lockedMode)

            val summary = BatchPhotoSummary.fromItems(finalSnapshot.items)
            assertEquals(3, summary.done)
            assertEquals(0, summary.failed)
            assertEquals(0, summary.skipped)

            scenario.onActivity { activity ->
                val texts = findComposeTexts(activity)
                assertTrue("Expected Batch ready text", "Batch ready" in texts)
                assertTrue("Expected Prepared: 3 text", "Prepared: 3" in texts)
                assertFalse("Single photo screen must not be active", "Sharper photos, lighter files" in texts)
            }

            // 9. Confirm exactly 3 outputs under Pictures/StatusHD/Batch/
            for (item in finalSnapshot.items) {
                val outputData = item.outputData
                assertNotNull("Output data must exist for Done item", outputData)
                val outputUri = Uri.parse(outputData!!.outputIdentifier)
                fixtures.trackUri(outputUri)

                val meta = fixtures.queryRowMetadata(outputUri)
                assertNotNull("Output row must exist in MediaStore", meta)
                assertEquals("image/jpeg", meta!!.mimeType)
                assertEquals(0, meta.isPending)
                assertTrue(
                    "RELATIVE_PATH must be in Pictures/StatusHD/Batch/",
                    meta.relativePath?.startsWith("Pictures/StatusHD/Batch") == true
                )
                assertTrue("Output JPEG must reopen cleanly", fixtures.reopenOutput(outputUri))
            }

            val finalBatchCount = fixtures.queryRowsByRelativePath("Pictures/StatusHD/Batch/").size
            assertEquals(
                "Expected exactly 3 new outputs under Pictures/StatusHD/Batch/",
                initialBatchCount + 3,
                finalBatchCount
            )

            // 10. Confirm no pending MediaStore rows remain
            assertEquals(
                "No pending MediaStore rows may remain after batch completion",
                initialPendingCount,
                fixtures.countPendingRows()
            )
        } finally {
            scenario.close()
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            delay(15L)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    private fun findComposeTexts(activity: Activity): List<String> {
        val texts = mutableListOf<String>()
        fun traverse(view: View) {
            if (view is ViewRootForTest) {
                val nodes = view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
                for (node in nodes) {
                    node.config.getOrNull(SemanticsProperties.Text)?.forEach { annotated ->
                        texts.add(annotated.text)
                    }
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    traverse(view.getChildAt(i))
                }
            }
        }
        traverse(activity.window.decorView)
        return texts
    }

    private fun clickComposeNodeWithText(activity: Activity, label: String): Boolean {
        var clicked = false
        fun traverse(view: View) {
            if (clicked) return
            if (view is ViewRootForTest) {
                val nodes = view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
                for (node in nodes) {
                    val nodeTexts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
                    if (label in nodeTexts) {
                        val onClick = node.config.getOrNull(SemanticsActions.OnClick)
                        if (onClick != null) {
                            clicked = onClick.action?.invoke() == true
                            return
                        }
                    }
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    traverse(view.getChildAt(i))
                }
            }
        }
        traverse(activity.window.decorView)
        return clicked
    }
}
