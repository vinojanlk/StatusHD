package com.techwin.statushd

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
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
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.techwin.statushd.ui.theme.AppThemeMode
import com.techwin.statushd.ui.theme.ThemePreferenceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AppearanceThemeInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repository: ThemePreferenceRepository
    private lateinit var fixtures: MediaStoreTestFixtures
    private var originalNightMode: String = "no"

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        repository = ThemePreferenceRepository(context)
        fixtures = MediaStoreTestFixtures(context)
        originalNightMode = readEmulatorNightMode()
        repository.setThemeMode(AppThemeMode.SYSTEM)
    }

    @After
    fun tearDown() = runBlocking {
        try {
            repository.setThemeMode(AppThemeMode.SYSTEM)
            setEmulatorNightMode(originalNightMode)
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
    fun defaultPreference_followsSystemLight() = runBlocking {
        setEmulatorNightMode("no")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntilScreenTheme(scenario, expectDark = false)

            openAboutSheet(scenario)
            waitUntil(10_000L) {
                var selected = false
                scenario.onActivity { activity ->
                    val texts = findAllComposeTexts(activity)
                    selected = "Appearance" in texts &&
                        "System default" in texts &&
                        "Light" in texts &&
                        "Dark" in texts &&
                        isComposeNodeSelected(activity, "System default")
                }
                selected
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun systemDefault_followsSystemDark_andReactsToSystemChange() = runBlocking {
        setEmulatorNightMode("no")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntilScreenTheme(scenario, expectDark = false)

            setEmulatorNightMode("yes")
            waitUntilScreenTheme(scenario, expectDark = true)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun explicitLight_staysLightWhenDeviceIsDark() = runBlocking {
        setEmulatorNightMode("yes")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntilScreenTheme(scenario, expectDark = true)

            openAboutSheet(scenario)
            clickOptionInAboutSheet(scenario, "Light")
            waitUntil(10_000L) { repository.themeMode.first() == AppThemeMode.LIGHT }
            dismissAboutSheet(scenario)

            waitUntilScreenTheme(scenario, expectDark = false)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun explicitDark_staysDarkWhenDeviceIsLight() = runBlocking {
        setEmulatorNightMode("no")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntilScreenTheme(scenario, expectDark = false)

            openAboutSheet(scenario)
            clickOptionInAboutSheet(scenario, "Dark")
            waitUntil(10_000L) { repository.themeMode.first() == AppThemeMode.DARK }
            dismissAboutSheet(scenario)

            waitUntilScreenTheme(scenario, expectDark = true)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun preference_survivesActivityRecreation() = runBlocking {
        setEmulatorNightMode("no")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntilScreenTheme(scenario, expectDark = false)

            openAboutSheet(scenario)
            clickOptionInAboutSheet(scenario, "Dark")
            waitUntil(10_000L) { repository.themeMode.first() == AppThemeMode.DARK }
            dismissAboutSheet(scenario)
            waitUntilScreenTheme(scenario, expectDark = true)

            scenario.recreate()
            waitUntilScreenTheme(scenario, expectDark = true)

            openAboutSheet(scenario)
            waitUntil(10_000L) {
                var selected = false
                scenario.onActivity { activity ->
                    selected = isComposeNodeSelected(activity, "Dark")
                }
                selected
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun preference_survivesFreshActivityLaunch() = runBlocking {
        setEmulatorNightMode("no")
        val firstScenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntilScreenTheme(firstScenario, expectDark = false)
            openAboutSheet(firstScenario)
            clickOptionInAboutSheet(firstScenario, "Dark")
            waitUntil(10_000L) { repository.themeMode.first() == AppThemeMode.DARK }
        } finally {
            firstScenario.close()
        }

        val secondScenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntilScreenTheme(secondScenario, expectDark = true)
            openAboutSheet(secondScenario)
            waitUntil(10_000L) {
                var selected = false
                secondScenario.onActivity { activity ->
                    selected = isComposeNodeSelected(activity, "Dark")
                }
                selected
            }
        } finally {
            secondScenario.close()
        }
    }

    @Test
    fun videoPhotoAndSavedDestinations_renderInBothLightAndDarkThemes() = runBlocking {
        setEmulatorNightMode("no")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            for (expectDark in listOf(false, true)) {
                val targetOption = if (expectDark) "Dark" else "Light"
                val targetMode = if (expectDark) AppThemeMode.DARK else AppThemeMode.LIGHT

                openAboutSheet(scenario)
                clickOptionInAboutSheet(scenario, targetOption)
                waitUntil(10_000L) { repository.themeMode.first() == targetMode }
                dismissAboutSheet(scenario)

                // Video destination
                clickTabAndWaitForText(scenario, "Video", "Make every frame count")
                waitUntilScreenTheme(scenario, expectDark = expectDark)

                // Photo destination
                clickTabAndWaitForText(scenario, "Photo", "Sharper photos, lighter files")
                waitUntilScreenTheme(scenario, expectDark = expectDark)

                // Saved destination
                clickTabAndWaitForText(scenario, "Saved", "Keep a copy of viewed media")
                waitUntilScreenTheme(scenario, expectDark = expectDark)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun batchPhotoScreen_respectsSelectedTheme() = runBlocking {
        setEmulatorNightMode("no")
        val uri1 = fixtures.seedAsset("landscape_4000x3000.jpg")
        val uri2 = fixtures.seedAsset("portrait_2400x3200.jpg")

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openAboutSheet(scenario)
            clickOptionInAboutSheet(scenario, "Light")
            waitUntil(10_000L) { repository.themeMode.first() == AppThemeMode.LIGHT }
            dismissAboutSheet(scenario)

            clickTabAndWaitForText(scenario, "Photo", "Prepare multiple photos")

            val resultIntent = Intent().apply {
                clipData = ClipData.newUri(context.contentResolver, "batch_photos", uri1).apply {
                    addItem(ClipData.Item(uri2))
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
                    var onBatch = false
                    scenario.onActivity { activity ->
                        onBatch = "Batch Preparation" in findAllComposeTexts(activity)
                    }
                    onBatch
                }
            } finally {
                instrumentation.removeMonitor(monitor)
            }

            waitUntilScreenTheme(scenario, expectDark = false)

            // Switch to Dark mode while Batch Photo is active
            openAboutSheet(scenario)
            clickOptionInAboutSheet(scenario, "Dark")
            waitUntil(10_000L) { repository.themeMode.first() == AppThemeMode.DARK }
            dismissAboutSheet(scenario)

            waitUntil(10_000L) {
                var stillOnBatch = false
                scenario.onActivity { activity ->
                    stillOnBatch = "Batch Preparation" in findAllComposeTexts(activity)
                }
                stillOnBatch
            }
            waitUntilScreenTheme(scenario, expectDark = true)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun persistentActionBars_andSystemBars_respectSelectedTheme() = runBlocking {
        setEmulatorNightMode("no")
        val photoUri = fixtures.seedAsset("landscape_4000x3000.jpg")

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            // Prepare both Video and Photo selections so their persistent bottom action bars appear
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[VideoViewModel::class.java].selectVideo(
                    uri = photoUri,
                    duration = 15_000L,
                    width = 1080,
                    height = 1920,
                    bitrateKbps = 4000
                )
                ViewModelProvider(activity)[PhotoViewModel::class.java].selectPhoto(photoUri)
            }

            for (expectDark in listOf(false, true)) {
                val targetOption = if (expectDark) "Dark" else "Light"
                val targetMode = if (expectDark) AppThemeMode.DARK else AppThemeMode.LIGHT

                openAboutSheet(scenario)
                clickOptionInAboutSheet(scenario, targetOption)
                waitUntil(10_000L) { repository.themeMode.first() == targetMode }
                dismissAboutSheet(scenario)

                // Verify persistent "Prepare video" action bar and system bars
                clickTabAndWaitForText(scenario, "Video", "Prepare video")
                waitUntilPersistentBarTheme(scenario, buttonLabel = "Prepare video", expectDark = expectDark)

                // Verify persistent "Prepare photo" action bar and system bars
                clickTabAndWaitForText(scenario, "Photo", "Prepare photo")
                waitUntilPersistentBarTheme(scenario, buttonLabel = "Prepare photo", expectDark = expectDark)
            }
        } finally {
            scenario.close()
        }
    }

    private suspend fun openAboutSheet(scenario: ActivityScenario<MainActivity>) {
        waitUntil(10_000L) {
            var opened = false
            scenario.onActivity { activity ->
                if ("Appearance" in findAllComposeTexts(activity)) {
                    opened = true
                } else {
                    clickComposeNodeByContentDescription(activity, "About")
                }
            }
            opened
        }
    }

    private suspend fun clickOptionInAboutSheet(scenario: ActivityScenario<MainActivity>, title: String) {
        waitUntil(10_000L) {
            var clicked = false
            scenario.onActivity { activity ->
                clicked = clickComposeNodeWithText(activity, title)
            }
            clicked
        }
    }

    private suspend fun dismissAboutSheet(scenario: ActivityScenario<MainActivity>) {
        executeShell("input keyevent 4")
        waitUntil(10_000L) {
            var dismissed = false
            scenario.onActivity { activity ->
                dismissed = "Appearance" !in findAllComposeTexts(activity)
            }
            dismissed
        }
    }

    private suspend fun clickTabAndWaitForText(
        scenario: ActivityScenario<MainActivity>,
        tabLabel: String,
        expectedText: String
    ) {
        waitUntil(10_000L) {
            var done = false
            scenario.onActivity { activity ->
                val texts = findAllComposeTexts(activity)
                if (expectedText in texts) {
                    done = true
                } else {
                    clickComposeNodeWithText(activity, tabLabel)
                }
            }
            done
        }
    }

    private suspend fun waitUntilScreenTheme(
        scenario: ActivityScenario<MainActivity>,
        expectDark: Boolean
    ) {
        waitUntil(10_000L) {
            var result = false
            var capturedBitmap: Bitmap? = null
            var lightStatusBars = false
            var lightNavBars = false

            scenario.onActivity { activity ->
                if ("Appearance" in findAllComposeTexts(activity)) {
                    return@onActivity
                }
                val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                lightStatusBars = controller.isAppearanceLightStatusBars
                lightNavBars = controller.isAppearanceLightNavigationBars
                capturedBitmap = captureWindowBitmap(activity)
            }

            val bitmap = capturedBitmap ?: return@waitUntil false
            try {
                // Sample left margin outside cards/hero (within 20.dp padding) at vertical midpoint
                val sampleX = 10.coerceIn(0, bitmap.width - 1)
                val sampleY = (bitmap.height / 2).coerceIn(0, bitmap.height - 1)
                val luminance = ColorUtils.calculateLuminance(bitmap.getPixel(sampleX, sampleY))
                val isRenderedDark = luminance < 0.25
                val isRenderedLight = luminance > 0.70

                result = if (expectDark) {
                    isRenderedDark && !lightStatusBars && !lightNavBars
                } else {
                    isRenderedLight && lightStatusBars && lightNavBars
                }
            } finally {
                bitmap.recycle()
            }
            result
        }
    }

    private suspend fun waitUntilPersistentBarTheme(
        scenario: ActivityScenario<MainActivity>,
        buttonLabel: String,
        expectDark: Boolean
    ) {
        waitUntil(10_000L) {
            var matched = false
            var capturedBitmap: Bitmap? = null
            var buttonBounds: Rect? = null
            var lightStatusBars = false
            var lightNavBars = false

            scenario.onActivity { activity ->
                if ("Appearance" in findAllComposeTexts(activity)) {
                    return@onActivity
                }
                val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                lightStatusBars = controller.isAppearanceLightStatusBars
                lightNavBars = controller.isAppearanceLightNavigationBars
                buttonBounds = findComposeNodeBoundsInWindow(activity, buttonLabel)
                if (buttonBounds != null) {
                    capturedBitmap = captureWindowBitmap(activity)
                }
            }

            val bitmap = capturedBitmap ?: return@waitUntil false
            val bounds = buttonBounds ?: run {
                bitmap.recycle()
                return@waitUntil false
            }

            try {
                // Sample inside the persistent bar's left horizontal padding next to the action button
                val sampleX = (bounds.left - 16).coerceIn(4, bitmap.width - 1)
                val sampleY = bounds.centerY().coerceIn(0, bitmap.height - 1)
                val luminance = ColorUtils.calculateLuminance(bitmap.getPixel(sampleX, sampleY))
                val isBarDark = luminance < 0.25
                val isBarLight = luminance > 0.70

                matched = if (expectDark) {
                    isBarDark && !lightStatusBars && !lightNavBars
                } else {
                    isBarLight && lightStatusBars && lightNavBars
                }
            } finally {
                bitmap.recycle()
            }
            matched
        }
    }

    private fun captureWindowBitmap(activity: Activity): Bitmap? {
        val decor = activity.window.decorView
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val handlerThread = HandlerThread("PixelCopyThread").apply { start() }
        return try {
            val latch = CountDownLatch(1)
            var copyResult = PixelCopy.ERROR_UNKNOWN
            PixelCopy.request(
                activity.window,
                bitmap,
                { result ->
                    copyResult = result
                    latch.countDown()
                },
                Handler(handlerThread.looper)
            )
            val completed = latch.await(2, TimeUnit.SECONDS)
            if (!completed || copyResult != PixelCopy.SUCCESS) {
                bitmap.recycle()
                null
            } else {
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
            delay(40L)
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

    private fun executeShell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return pfd.use { descriptor ->
            BufferedReader(InputStreamReader(android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor))).use { reader ->
                reader.readText()
            }
        }
    }

    private fun collectAllSemanticsNodes(activity: Activity): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun traverse(view: View) {
            if (view is ViewRootForTest) {
                result.addAll(view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true))
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    traverse(view.getChildAt(i))
                }
            }
        }
        val roots = (WindowInspector.getGlobalWindowViews() + activity.window.decorView).distinct()
        for (root in roots) {
            traverse(root)
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

    private fun isComposeNodeSelected(activity: Activity, label: String): Boolean {
        for (node in collectAllSemanticsNodes(activity)) {
            val nodeTexts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
            if (label in nodeTexts) {
                if (node.config.getOrNull(SemanticsProperties.Selected) == true) {
                    return true
                }
            }
        }
        return false
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

    private fun clickComposeNodeByContentDescription(activity: Activity, contentDesc: String): Boolean {
        for (node in collectAllSemanticsNodes(activity)) {
            val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            if (contentDesc in descriptions) {
                val onClick = node.config.getOrNull(SemanticsActions.OnClick)
                if (onClick != null && onClick.action?.invoke() == true) {
                    return true
                }
            }
        }
        return false
    }

    private fun findComposeNodeBoundsInWindow(activity: Activity, label: String): Rect? {
        for (node in collectAllSemanticsNodes(activity)) {
            val nodeTexts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
            if (label in nodeTexts) {
                val rect = node.boundsInWindow
                return Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            }
        }
        return null
    }
}
