package com.techwin.statushd

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.graphics.toArgb
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
import com.techwin.statushd.ui.theme.AppearanceViewModel
import com.techwin.statushd.ui.theme.PrimaryContainerDark
import com.techwin.statushd.ui.theme.PrimaryContainerLight
import com.techwin.statushd.ui.theme.SurfaceContainerLowLight
import com.techwin.statushd.ui.theme.SurfaceDark
import com.techwin.statushd.ui.theme.ThemePreferenceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AppearanceThemeInstrumentedTest {

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
        repository.setThemeMode(AppThemeMode.SYSTEM)
    }

    @After
    fun tearDown() = runBlocking {
        try {
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
    fun defaultPreference_followsSystemLight() = runBlocking {
        setEmulatorNightMode("no")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntilScreenTheme(scenario, expectDark = false)
            waitUntilOrbContentDescription(scenario, "Appearance: System default")
            assertEquals(AppThemeMode.SYSTEM, repository.themeMode.first())
        } finally {
            scenario.close()
        }
    }

    @Test
    fun aboutSheet_doesNotContainAppearanceSelector_andShowsDisclaimers() = runBlocking {
        setEmulatorNightMode("no")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openAboutSheet(scenario)
            waitUntil(10_000L) {
                var ok = false
                scenario.onActivity { activity ->
                    val texts = findAllComposeTexts(activity)
                    val descs = findAllContentDescriptions(activity)
                    ok = "About StatusHD" in texts &&
                        "StatusHD prepares media locally on your device." in texts &&
                        "Messaging platforms may apply additional compression." in texts &&
                        "StatusHD is an independent project and is not affiliated with, endorsed by or sponsored by WhatsApp or Meta." in texts &&
                        "Appearance" !in texts &&
                        "Follow the device appearance setting" !in texts &&
                        "Always use the light appearance" !in texts &&
                        "Always use the dark appearance" !in texts &&
                        "System default" !in descs
                }
                ok
            }
            dismissAboutSheet(scenario)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun systemDefault_followsSystemDark_andOrbRemainsSystem() = runBlocking {
        setEmulatorNightMode("no")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntilScreenTheme(scenario, expectDark = false)
            waitUntilOrbContentDescription(scenario, "Appearance: System default")

            setEmulatorNightMode("yes")
            waitUntilScreenTheme(scenario, expectDark = true)
            waitUntilOrbContentDescription(scenario, "Appearance: System default")
            assertEquals(AppThemeMode.SYSTEM, repository.themeMode.first())

            setEmulatorNightMode("no")
            waitUntilScreenTheme(scenario, expectDark = false)
            waitUntilOrbContentDescription(scenario, "Appearance: System default")
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

            selectThemeModeViaOrbMenu(scenario, AppThemeMode.LIGHT)
            waitUntilScreenTheme(scenario, expectDark = false)
            waitUntilOrbContentDescription(scenario, "Appearance: Light")
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

            selectThemeModeViaOrbMenu(scenario, AppThemeMode.DARK)
            waitUntilScreenTheme(scenario, expectDark = true)
            waitUntilOrbContentDescription(scenario, "Appearance: Dark")
        } finally {
            scenario.close()
        }
    }

    @Test
    fun themeOrb_cyclesModesThroughViewModel() = runBlocking {
        setEmulatorNightMode("no")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntilOrbContentDescription(scenario, "Appearance: System default")
            lateinit var viewModel: AppearanceViewModel
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[AppearanceViewModel::class.java]
            }
            assertEquals(AppThemeMode.SYSTEM, viewModel.themeMode.value)

            // 1. Tap orb: SYSTEM -> LIGHT
            clickThemeOrb(scenario, expectedCurrentDesc = "Appearance: System default")
            waitUntil(10_000L) {
                viewModel.themeMode.value == AppThemeMode.LIGHT &&
                    repository.themeMode.first() == AppThemeMode.LIGHT
            }
            waitUntilOrbContentDescription(scenario, "Appearance: Light")
            waitUntilScreenTheme(scenario, expectDark = false)

            // 2. Tap orb: LIGHT -> DARK
            clickThemeOrb(scenario, expectedCurrentDesc = "Appearance: Light")
            waitUntil(10_000L) {
                viewModel.themeMode.value == AppThemeMode.DARK &&
                    repository.themeMode.first() == AppThemeMode.DARK
            }
            waitUntilOrbContentDescription(scenario, "Appearance: Dark")
            waitUntilScreenTheme(scenario, expectDark = true)

            // 3. Tap orb: DARK -> SYSTEM
            clickThemeOrb(scenario, expectedCurrentDesc = "Appearance: Dark")
            waitUntil(10_000L) {
                viewModel.themeMode.value == AppThemeMode.SYSTEM &&
                    repository.themeMode.first() == AppThemeMode.SYSTEM
            }
            waitUntilOrbContentDescription(scenario, "Appearance: System default")
            waitUntilScreenTheme(scenario, expectDark = false)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun themeOrb_directMenuSelectsSystemLightAndDark() = runBlocking {
        setEmulatorNightMode("no")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntilOrbContentDescription(scenario, "Appearance: System default")
            lateinit var viewModel: AppearanceViewModel
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[AppearanceViewModel::class.java]
            }

            // Direct menu: Select Dark
            selectThemeModeViaOrbMenu(scenario, AppThemeMode.DARK)
            waitUntil(10_000L) {
                viewModel.themeMode.value == AppThemeMode.DARK &&
                    repository.themeMode.first() == AppThemeMode.DARK
            }
            waitUntilOrbContentDescription(scenario, "Appearance: Dark")
            waitUntilScreenTheme(scenario, expectDark = true)

            // Direct menu: Select Light
            selectThemeModeViaOrbMenu(scenario, AppThemeMode.LIGHT)
            waitUntil(10_000L) {
                viewModel.themeMode.value == AppThemeMode.LIGHT &&
                    repository.themeMode.first() == AppThemeMode.LIGHT
            }
            waitUntilOrbContentDescription(scenario, "Appearance: Light")
            waitUntilScreenTheme(scenario, expectDark = false)

            // Direct menu: Select System default
            selectThemeModeViaOrbMenu(scenario, AppThemeMode.SYSTEM)
            waitUntil(10_000L) {
                viewModel.themeMode.value == AppThemeMode.SYSTEM &&
                    repository.themeMode.first() == AppThemeMode.SYSTEM
            }
            waitUntilOrbContentDescription(scenario, "Appearance: System default")
            waitUntilScreenTheme(scenario, expectDark = false)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun topBarControls_exposeExpectedContentDescriptions_andOperateIndependently() = runBlocking {
        setEmulatorNightMode("no")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntil(10_000L) {
                var hasBoth = false
                scenario.onActivity { activity ->
                    val descs = findAllContentDescriptions(activity)
                    hasBoth = "Appearance: System default" in descs && "About" in descs
                }
                hasBoth
            }

            // Opening About sheet operates independently and does not alter appearance mode
            openAboutSheet(scenario)
            assertEquals(AppThemeMode.SYSTEM, repository.themeMode.first())
            dismissAboutSheet(scenario)
            waitUntilOrbContentDescription(scenario, "Appearance: System default")
        } finally {
            scenario.close()
        }
    }

    @Test
    fun qualityCards_useSemanticColors_inLightAndDarkModes_andSurviveFontScale13() = runBlocking {
        setEmulatorNightMode("no")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            // Light mode check
            waitUntilScreenTheme(scenario, expectDark = false)
            waitUntilQualityCardColors(
                scenario = scenario,
                selectedCardTitle = "Full HD Detail",
                unselectedCardTitle = "HD Longer",
                expectedSelectedColor = PrimaryContainerLight.toArgb(),
                expectedUnselectedColor = SurfaceContainerLowLight.toArgb()
            )

            // Dark mode check
            clickThemeOrb(scenario, expectedCurrentDesc = "Appearance: System default")
            waitUntilOrbContentDescription(scenario, "Appearance: Light")
            clickThemeOrb(scenario, expectedCurrentDesc = "Appearance: Light")
            waitUntilOrbContentDescription(scenario, "Appearance: Dark")
            waitUntilScreenTheme(scenario, expectDark = true)

            waitUntilQualityCardColors(
                scenario = scenario,
                selectedCardTitle = "Full HD Detail",
                unselectedCardTitle = "HD Longer",
                expectedSelectedColor = PrimaryContainerDark.toArgb(),
                expectedUnselectedColor = SurfaceDark.toArgb()
            )

            // Font scale 1.3 check in Light mode: ensure output cards remain visible without overlap or clipping
            clickThemeOrb(scenario, expectedCurrentDesc = "Appearance: Dark")
            waitUntilOrbContentDescription(scenario, "Appearance: System default")
            waitUntilScreenTheme(scenario, expectDark = false)

            setFontScale("1.3")
            waitUntil(10_000L) {
                var validAtScale = false
                scenario.onActivity { activity ->
                    val texts = findAllComposeTexts(activity)
                    val titleBounds = findUnmergedTextBoundsInWindow(activity, "Full HD Detail")
                    val badgeBounds = findUnmergedTextBoundsInWindow(activity, "1080P")
                    val primaryBounds = findUnmergedTextBoundsInWindow(activity, "Best for fine detail")
                    val secondaryBounds = findUnmergedTextBoundsInWindow(activity, "30-second parts")
                    val unselectedBounds = findUnmergedTextBoundsInWindow(activity, "HD Longer")
                    validAtScale = "Full HD Detail" in texts &&
                        "HD Longer" in texts &&
                        titleBounds != null &&
                        badgeBounds != null &&
                        primaryBounds != null &&
                        secondaryBounds != null &&
                        unselectedBounds != null &&
                        titleBounds.height() > 0 &&
                        badgeBounds.height() > 0 &&
                        primaryBounds.height() > 0 &&
                        secondaryBounds.height() > 0 &&
                        unselectedBounds.height() > 0 &&
                        titleBounds.bottom <= badgeBounds.top &&
                        badgeBounds.bottom <= primaryBounds.top &&
                        primaryBounds.bottom <= secondaryBounds.top
                }
                validAtScale
            }
        } finally {
            setFontScale("1.0")
            scenario.close()
        }
    }

    @Test
    fun preference_survivesActivityRecreation() = runBlocking {
        setEmulatorNightMode("no")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntilScreenTheme(scenario, expectDark = false)

            selectThemeModeViaOrbMenu(scenario, AppThemeMode.DARK)
            waitUntilScreenTheme(scenario, expectDark = true)
            waitUntilOrbContentDescription(scenario, "Appearance: Dark")

            scenario.recreate()
            waitUntilScreenTheme(scenario, expectDark = true)
            waitUntilOrbContentDescription(scenario, "Appearance: Dark")
            assertEquals(AppThemeMode.DARK, repository.themeMode.first())
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
            selectThemeModeViaOrbMenu(firstScenario, AppThemeMode.DARK)
            waitUntil(10_000L) { repository.themeMode.first() == AppThemeMode.DARK }
            waitUntilOrbContentDescription(firstScenario, "Appearance: Dark")
        } finally {
            firstScenario.close()
        }

        val secondScenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitUntilScreenTheme(secondScenario, expectDark = true)
            waitUntilOrbContentDescription(secondScenario, "Appearance: Dark")
            assertEquals(AppThemeMode.DARK, repository.themeMode.first())
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
                val targetMode = if (expectDark) AppThemeMode.DARK else AppThemeMode.LIGHT
                selectThemeModeViaOrbMenu(scenario, targetMode)

                clickTabAndWaitForText(scenario, "Video", "Make every frame count")
                waitUntilScreenTheme(scenario, expectDark = expectDark)

                clickTabAndWaitForText(scenario, "Photo", "Sharper photos, lighter files")
                waitUntilScreenTheme(scenario, expectDark = expectDark)

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
            selectThemeModeViaOrbMenu(scenario, AppThemeMode.LIGHT)

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

            selectThemeModeViaOrbMenu(scenario, AppThemeMode.DARK)
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
                val targetMode = if (expectDark) AppThemeMode.DARK else AppThemeMode.LIGHT
                selectThemeModeViaOrbMenu(scenario, targetMode)

                clickTabAndWaitForText(scenario, "Video", "Prepare video")
                waitUntilPersistentBarTheme(scenario, buttonLabel = "Prepare video", expectDark = expectDark)

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
                if ("About StatusHD" in findAllComposeTexts(activity)) {
                    opened = true
                } else {
                    clickComposeNodeByContentDescription(activity, "About")
                }
            }
            opened
        }
    }

    private suspend fun dismissAboutSheet(scenario: ActivityScenario<MainActivity>) {
        executeShell("input keyevent 4")
        waitUntil(10_000L) {
            var dismissed = false
            scenario.onActivity { activity ->
                dismissed = "About StatusHD" !in findAllComposeTexts(activity)
            }
            dismissed
        }
    }

    private suspend fun selectThemeModeViaOrbMenu(
        scenario: ActivityScenario<MainActivity>,
        mode: AppThemeMode
    ) {
        val label = when (mode) {
            AppThemeMode.SYSTEM -> "System default"
            AppThemeMode.LIGHT -> "Light"
            AppThemeMode.DARK -> "Dark"
        }
        waitUntil(10_000L) {
            var selected = false
            scenario.onActivity { activity ->
                if (clickComposeNodeWithText(activity, label)) {
                    selected = true
                } else {
                    val currentDesc = findAllContentDescriptions(activity)
                        .firstOrNull { it.startsWith("Appearance:") } ?: "Appearance: System default"
                    longClickComposeNodeByContentDescription(activity, currentDesc)
                }
            }
            selected
        }
        waitUntil(10_000L) {
            repository.themeMode.first() == mode
        }
    }

    private suspend fun clickThemeOrb(scenario: ActivityScenario<MainActivity>, expectedCurrentDesc: String) {
        waitUntil(10_000L) {
            var clicked = false
            scenario.onActivity { activity ->
                clicked = clickComposeNodeByContentDescription(activity, expectedCurrentDesc)
            }
            clicked
        }
    }

    private suspend fun waitUntilOrbContentDescription(
        scenario: ActivityScenario<MainActivity>,
        expectedDesc: String
    ) {
        waitUntil(10_000L) {
            var found = false
            scenario.onActivity { activity ->
                found = expectedDesc in findAllContentDescriptions(activity)
            }
            found
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

    private suspend fun waitUntilQualityCardColors(
        scenario: ActivityScenario<MainActivity>,
        selectedCardTitle: String,
        unselectedCardTitle: String,
        expectedSelectedColor: Int,
        expectedUnselectedColor: Int
    ) {
        waitUntil(10_000L) {
            var matched = false
            var bitmap: Bitmap? = null
            var selectedCardBounds: Rect? = null
            var unselectedCardBounds: Rect? = null

            scenario.onActivity { activity ->
                selectedCardBounds = findCardBoundsByTitle(activity, selectedCardTitle, expectSelected = true)
                unselectedCardBounds = findCardBoundsByTitle(activity, unselectedCardTitle, expectSelected = false)
                if (selectedCardBounds != null && unselectedCardBounds != null) {
                    bitmap = captureWindowBitmap(activity)
                }
            }

            val bmp = bitmap ?: return@waitUntil false
            val selBounds = selectedCardBounds
            val unselBounds = unselectedCardBounds
            if (selBounds == null || unselBounds == null) {
                bmp.recycle()
                return@waitUntil false
            }

            try {
                // Sample inside top-right padding area of each card (well inside border, clear of text)
                val selX = (selBounds.left + 20).coerceIn(0, bmp.width - 1)
                val selY = (selBounds.bottom - 20).coerceIn(0, bmp.height - 1)
                val unselX = (unselBounds.left + 20).coerceIn(0, bmp.width - 1)
                val unselY = (unselBounds.bottom - 20).coerceIn(0, bmp.height - 1)

                val selPixel = bmp.getPixel(selX, selY)
                val unselPixel = bmp.getPixel(unselX, unselY)

                matched = colorsClose(selPixel, expectedSelectedColor) &&
                    colorsClose(unselPixel, expectedUnselectedColor)
            } finally {
                bmp.recycle()
            }
            matched
        }
    }

    private fun colorsClose(actual: Int, expected: Int, tolerance: Int = 18): Boolean {
        val dr = abs(android.graphics.Color.red(actual) - android.graphics.Color.red(expected))
        val dg = abs(android.graphics.Color.green(actual) - android.graphics.Color.green(expected))
        val db = abs(android.graphics.Color.blue(actual) - android.graphics.Color.blue(expected))
        return dr <= tolerance && dg <= tolerance && db <= tolerance
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
            BufferedReader(InputStreamReader(android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor))).use { reader ->
                reader.readText()
            }
        }
    }

    private fun collectAllSemanticsNodes(
        activity: Activity,
        mergingEnabled: Boolean = true
    ): List<SemanticsNode> {
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
        val roots = (WindowInspector.getGlobalWindowViews() + activity.window.decorView).distinct()
        for (root in roots) {
            traverse(root)
        }
        return result
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

    private fun findCardBoundsByTitle(activity: Activity, title: String, expectSelected: Boolean): Rect? {
        for (node in collectAllSemanticsNodes(activity)) {
            val nodeTexts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
            val selectedProp = node.config.getOrNull(SemanticsProperties.Selected)
            if (title in nodeTexts && selectedProp == expectSelected) {
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

    private fun longClickComposeNodeByContentDescription(activity: Activity, contentDesc: String): Boolean {
        for (node in collectAllSemanticsNodes(activity)) {
            val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            if (contentDesc in descriptions) {
                val onLongClick = node.config.getOrNull(SemanticsActions.OnLongClick)
                if (onLongClick != null && onLongClick.action?.invoke() == true) {
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
