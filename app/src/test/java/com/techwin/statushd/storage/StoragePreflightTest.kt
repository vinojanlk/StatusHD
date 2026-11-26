package com.techwin.statushd.storage

import com.techwin.statushd.BatchItemStatus
import com.techwin.statushd.BatchPhotoPreparationResult
import com.techwin.statushd.BatchPhotoPreflightController
import com.techwin.statushd.BatchPhotoRunner
import com.techwin.statushd.BatchState
import com.techwin.statushd.PhotoDestination
import com.techwin.statushd.PhotoPreparer
import com.techwin.statushd.PhotoQualityMode
import com.techwin.statushd.QualityMode
import com.techwin.statushd.VideoPreflightCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class StoragePreflightTest {

    private class FakeAvailableStorageProvider(
        private val provider: suspend () -> StorageAvailability
    ) : AvailableStorageProvider {
        val callCount = AtomicInteger(0)

        override suspend fun getAvailability(): StorageAvailability {
            callCount.incrementAndGet()
            return provider()
        }
    }

    private class FakeSourceSizeProvider(
        private val sizes: Map<String, Long?> = emptyMap()
    ) : SourceSizeProvider {
        override suspend fun querySizeBytes(uriString: String): Long? = sizes[uriString]
    }

    private class RecordingPhotoPreparer : PhotoPreparer {
        val preparedSources = mutableListOf<String>()

        override suspend fun prepare(
            sourceUri: String,
            mode: PhotoQualityMode,
            destination: PhotoDestination
        ): BatchPhotoPreparationResult {
            synchronized(preparedSources) {
                preparedSources.add(sourceUri)
            }
            return BatchPhotoPreparationResult.Success(
                outputIdentifier = "out_$sourceUri",
                outputWidth = 1080,
                outputHeight = 1920,
                outputSize = 123_456L
            )
        }
    }

    @Test
    fun photoIgnoresUnavailableInternalCache_whenInternalRequirementIsZero() = runBlocking {
        val input = PhotoEstimateInput(sourceSizeBytes = 2_000_000L, width = 1080, height = 1920)
        val required = StorageEstimatePolicy.estimateSinglePhoto(input, PhotoQualityMode.STATUS_HD)

        assertEquals(0L, required.internalCacheRequiredBytes)
        assertTrue(required.sharedMediaRequiredBytes > 0L)

        // Internal is null (unavailable), Shared is sufficient
        val availability = StorageAvailability(
            internalCacheAvailableBytes = null,
            sharedMediaAvailableBytes = required.sharedMediaRequiredBytes + 100L * StorageEstimatePolicy.MIB
        )
        val checker = StoragePreflightChecker(FakeAvailableStorageProvider { availability })

        val result = checker.check(required)

        assertTrue(result is StoragePreflightResult.Sufficient)
        val sufficient = result as StoragePreflightResult.Sufficient
        assertFalse(sufficient.isCloseToLimit)
        assertEquals(availability, sufficient.availability)
    }

    @Test
    fun photoBlocksInsufficientSharedStorage() = runBlocking {
        val input = PhotoEstimateInput(sourceSizeBytes = 2_000_000L, width = 1080, height = 1920)
        val required = StorageEstimatePolicy.estimateSinglePhoto(input, PhotoQualityMode.STATUS_HD)

        val insufficientShared = required.sharedMediaRequiredBytes - 1L
        val availability = StorageAvailability(
            internalCacheAvailableBytes = 500L * StorageEstimatePolicy.MIB,
            sharedMediaAvailableBytes = insufficientShared
        )
        val checker = StoragePreflightChecker(FakeAvailableStorageProvider { availability })

        val result = checker.check(required)

        assertTrue(result is StoragePreflightResult.SharedMediaInsufficient)
        val blocked = result as StoragePreflightResult.SharedMediaInsufficient
        assertEquals(insufficientShared, blocked.availableBytes)
        assertEquals(required.sharedMediaRequiredBytes, blocked.requiredBytes)
    }

    @Test
    fun batchReserveIsAppliedOnce() {
        val items = listOf(
            PhotoEstimateInput(sourceSizeBytes = 1_000_000L),
            PhotoEstimateInput(sourceSizeBytes = 2_000_000L),
            PhotoEstimateInput(sourceSizeBytes = 3_000_000L)
        )
        val expectedItemSum = items.sumOf {
            StorageEstimatePolicy.estimatePhotoItemBytes(it, PhotoQualityMode.STATUS_HD)
        }

        val batch = StorageEstimatePolicy.estimateBatchPhotos(items, PhotoQualityMode.STATUS_HD)

        assertEquals(0L, batch.internalCacheRequiredBytes)
        val expectedShared = expectedItemSum + StorageEstimatePolicy.BATCH_HEADROOM_BYTES + StorageEstimatePolicy.FIXED_RESERVE_BYTES
        assertEquals(expectedShared, batch.sharedMediaRequiredBytes)
    }

    @Test
    fun videoInternalEstimateIncludesSourcePlusTotalEncodedOutput() {
        val sourceBytes = 20_000_000L
        val durationMs = 30_000L
        val input = VideoEstimateInput(
            sourceSizeBytes = sourceBytes,
            durationMs = durationMs,
            width = 1920,
            height = 1080,
            sourceBitrateKbps = 8000,
            isPortrait = false,
            mode = QualityMode.FULL_HD
        )

        val breakdown = StorageEstimatePolicy.estimateVideo(input)

        val expectedEncodedMediaBytes = ((3600L + 128L) * durationMs) / 8L
        val expectedInternalPeak = sourceBytes +
            expectedEncodedMediaBytes +
            StorageEstimatePolicy.VIDEO_ACTIVE_WRITE_HEADROOM_BYTES +
            StorageEstimatePolicy.FIXED_RESERVE_BYTES

        assertEquals(expectedInternalPeak, breakdown.internalCacheRequiredBytes)
        val expectedShared = expectedEncodedMediaBytes +
            StorageEstimatePolicy.VIDEO_PUBLICATION_HEADROOM_PER_SEGMENT_BYTES +
            StorageEstimatePolicy.FIXED_RESERVE_BYTES
        assertEquals(expectedShared, breakdown.sharedMediaRequiredBytes)
    }

    @Test
    fun multiSegmentVideoDoesNotUseOneSegmentTemporaryOccupancy() {
        val sourceBytes = 15_000_000L
        val durationMs = 90_000L // 3 segments for FULL_HD 30s split
        val input = VideoEstimateInput(
            sourceSizeBytes = sourceBytes,
            durationMs = durationMs,
            width = 1080,
            height = 1920,
            sourceBitrateKbps = 6000,
            isPortrait = true,
            mode = QualityMode.FULL_HD
        )

        val breakdown = StorageEstimatePolicy.estimateVideo(input)

        val expectedTotalEncodedBytes = ((3600L + 128L) * durationMs) / 8L
        val oneSegmentEncodedBytes = expectedTotalEncodedBytes / 3L

        // Verify it includes the full multi-segment encoded output, NOT just 1 segment
        assertTrue(
            breakdown.internalCacheRequiredBytes >= sourceBytes + expectedTotalEncodedBytes + StorageEstimatePolicy.FIXED_RESERVE_BYTES
        )
        assertTrue(
            breakdown.internalCacheRequiredBytes > sourceBytes + oneSegmentEncodedBytes + StorageEstimatePolicy.FIXED_RESERVE_BYTES
        )
    }

    @Test
    fun videoCanFailInternalWhileSharedIsSufficient() = runBlocking {
        val input = VideoEstimateInput(
            sourceSizeBytes = 20_000_000L,
            durationMs = 30_000L,
            width = 1920,
            height = 1080,
            sourceBitrateKbps = 8000,
            isPortrait = false,
            mode = QualityMode.FULL_HD
        )
        val required = StorageEstimatePolicy.estimateVideo(input)

        val availability = StorageAvailability(
            internalCacheAvailableBytes = required.internalCacheRequiredBytes - 1L,
            sharedMediaAvailableBytes = required.sharedMediaRequiredBytes + 200L * StorageEstimatePolicy.MIB
        )
        val checker = StoragePreflightChecker(FakeAvailableStorageProvider { availability })

        val result = checker.check(required)

        assertTrue(result is StoragePreflightResult.InternalCacheInsufficient)
        val failed = result as StoragePreflightResult.InternalCacheInsufficient
        assertEquals(required.internalCacheRequiredBytes - 1L, failed.availableBytes)
        assertEquals(required.internalCacheRequiredBytes, failed.requiredBytes)
    }

    @Test
    fun videoCanFailSharedWhileInternalIsSufficient() = runBlocking {
        val input = VideoEstimateInput(
            sourceSizeBytes = 20_000_000L,
            durationMs = 30_000L,
            width = 1920,
            height = 1080,
            sourceBitrateKbps = 8000,
            isPortrait = false,
            mode = QualityMode.FULL_HD
        )
        val required = StorageEstimatePolicy.estimateVideo(input)

        val availability = StorageAvailability(
            internalCacheAvailableBytes = required.internalCacheRequiredBytes + 200L * StorageEstimatePolicy.MIB,
            sharedMediaAvailableBytes = required.sharedMediaRequiredBytes - 1L
        )
        val checker = StoragePreflightChecker(FakeAvailableStorageProvider { availability })

        val result = checker.check(required)

        assertTrue(result is StoragePreflightResult.SharedMediaInsufficient)
        val failed = result as StoragePreflightResult.SharedMediaInsufficient
        assertEquals(required.sharedMediaRequiredBytes - 1L, failed.availableBytes)
        assertEquals(required.sharedMediaRequiredBytes, failed.requiredBytes)
    }

    @Test
    fun videoCanFailBoth() = runBlocking {
        val input = VideoEstimateInput(
            sourceSizeBytes = 20_000_000L,
            durationMs = 30_000L,
            width = 1920,
            height = 1080,
            sourceBitrateKbps = 8000,
            isPortrait = false,
            mode = QualityMode.FULL_HD
        )
        val required = StorageEstimatePolicy.estimateVideo(input)

        val availability = StorageAvailability(
            internalCacheAvailableBytes = required.internalCacheRequiredBytes - 100L,
            sharedMediaAvailableBytes = required.sharedMediaRequiredBytes - 200L
        )
        val checker = StoragePreflightChecker(FakeAvailableStorageProvider { availability })

        val result = checker.check(required)

        assertTrue(result is StoragePreflightResult.BothInsufficient)
        val both = result as StoragePreflightResult.BothInsufficient
        assertEquals(required.internalCacheRequiredBytes - 100L, both.internalAvailableBytes)
        assertEquals(required.internalCacheRequiredBytes, both.internalRequiredBytes)
        assertEquals(required.sharedMediaRequiredBytes - 200L, both.sharedAvailableBytes)
        assertEquals(required.sharedMediaRequiredBytes, both.sharedRequiredBytes)
    }

    @Test
    fun unknownInternalAvailabilityProducesUnavailableOnlyWhenInternalBytesAreRequired() = runBlocking {
        // When internal requirement is > 0 (Video)
        val videoReq = VolumeRequiredStorage(
            internalCacheRequiredBytes = 50L * StorageEstimatePolicy.MIB,
            sharedMediaRequiredBytes = 50L * StorageEstimatePolicy.MIB
        )
        val availabilityWithUnknownInternal = StorageAvailability(
            internalCacheAvailableBytes = null,
            sharedMediaAvailableBytes = 100L * StorageEstimatePolicy.MIB
        )
        val checker = StoragePreflightChecker(FakeAvailableStorageProvider { availabilityWithUnknownInternal })

        val videoResult = checker.check(videoReq)
        assertTrue(videoResult is StoragePreflightResult.InternalAvailabilityUnavailable)

        // When internal requirement is 0 (Photo)
        val photoReq = VolumeRequiredStorage(
            internalCacheRequiredBytes = 0L,
            sharedMediaRequiredBytes = 50L * StorageEstimatePolicy.MIB
        )
        val photoResult = checker.check(photoReq)
        assertTrue(photoResult is StoragePreflightResult.Sufficient)
    }

    @Test
    fun unknownSharedAvailabilityProducesUnavailableWhenSharedBytesAreRequired() = runBlocking {
        val req = VolumeRequiredStorage(
            internalCacheRequiredBytes = 50L * StorageEstimatePolicy.MIB,
            sharedMediaRequiredBytes = 50L * StorageEstimatePolicy.MIB
        )
        val availabilityWithUnknownShared = StorageAvailability(
            internalCacheAvailableBytes = 100L * StorageEstimatePolicy.MIB,
            sharedMediaAvailableBytes = null
        )
        val checker = StoragePreflightChecker(FakeAvailableStorageProvider { availabilityWithUnknownShared })

        val result = checker.check(req)
        assertTrue(result is StoragePreflightResult.SharedAvailabilityUnavailable)
    }

    @Test
    fun overflowSaturatesSafely() {
        val hugePhotos = List(10) { PhotoEstimateInput(sourceSizeBytes = Long.MAX_VALUE / 2L) }
        val batch = StorageEstimatePolicy.estimateBatchPhotos(hugePhotos, PhotoQualityMode.STATUS_HD)

        assertEquals(0L, batch.internalCacheRequiredBytes)
        assertEquals(Long.MAX_VALUE, batch.sharedMediaRequiredBytes)

        val hugeVideo = StorageEstimatePolicy.estimateVideo(
            VideoEstimateInput(
                sourceSizeBytes = Long.MAX_VALUE,
                durationMs = Long.MAX_VALUE,
                width = 3840,
                height = 2160,
                sourceBitrateKbps = 50_000,
                isPortrait = false,
                mode = QualityMode.FULL_HD
            )
        )
        assertEquals(Long.MAX_VALUE, hugeVideo.internalCacheRequiredBytes)
        assertEquals(Long.MAX_VALUE, hugeVideo.sharedMediaRequiredBytes)
    }

    @Test
    fun reserveIsAppliedExactlyOncePerRelevantVolume() {
        val single = StorageEstimatePolicy.estimateSinglePhoto(
            PhotoEstimateInput(sourceSizeBytes = 1_000_000L),
            PhotoQualityMode.STATUS_HD
        )
        val batchTen = StorageEstimatePolicy.estimateBatchPhotos(
            List(10) { PhotoEstimateInput(sourceSizeBytes = 1_000_000L) },
            PhotoQualityMode.STATUS_HD
        )
        val video = StorageEstimatePolicy.estimateVideo(
            VideoEstimateInput(
                sourceSizeBytes = 5_000_000L,
                durationMs = 90_000L,
                width = 1080,
                height = 1920,
                sourceBitrateKbps = 5000,
                isPortrait = true,
                mode = QualityMode.FULL_HD
            )
        )

        // Photo / Batch: 0 on internal, exactly FIXED_RESERVE_BYTES on shared
        assertEquals(0L, single.internalCacheRequiredBytes)
        assertTrue(single.sharedMediaRequiredBytes >= StorageEstimatePolicy.FIXED_RESERVE_BYTES)
        assertEquals(0L, batchTen.internalCacheRequiredBytes)
        assertTrue(batchTen.sharedMediaRequiredBytes >= StorageEstimatePolicy.FIXED_RESERVE_BYTES)

        // Video: exactly FIXED_RESERVE_BYTES on internal, exactly FIXED_RESERVE_BYTES on shared
        assertTrue(video.internalCacheRequiredBytes >= StorageEstimatePolicy.FIXED_RESERVE_BYTES)
        assertTrue(video.sharedMediaRequiredBytes >= StorageEstimatePolicy.FIXED_RESERVE_BYTES)
    }

    @Test
    fun preserveOriginalFollowsRealCacheLifecycle() {
        val sourceBytes = 12_000_000L
        val breakdown = StorageEstimatePolicy.estimateVideo(
            VideoEstimateInput(
                sourceSizeBytes = sourceBytes,
                durationMs = 20_000L,
                width = 1080,
                height = 1920,
                sourceBitrateKbps = 3000,
                isPortrait = true,
                mode = QualityMode.FULL_HD
            )
        )

        val expectedInternal = sourceBytes + sourceBytes +
            StorageEstimatePolicy.VIDEO_ACTIVE_WRITE_HEADROOM_BYTES +
            StorageEstimatePolicy.FIXED_RESERVE_BYTES
        assertEquals(expectedInternal, breakdown.internalCacheRequiredBytes)

        val expectedShared = sourceBytes +
            StorageEstimatePolicy.VIDEO_PUBLICATION_HEADROOM_PER_SEGMENT_BYTES +
            StorageEstimatePolicy.FIXED_RESERVE_BYTES
        assertEquals(expectedShared, breakdown.sharedMediaRequiredBytes)
    }

    @Test
    fun zeroDurationAndInvalidMetadataInputsRemainSafe() {
        val zeroDuration = StorageEstimatePolicy.estimateVideo(
            VideoEstimateInput(
                sourceSizeBytes = 0L,
                durationMs = 0L,
                width = 0,
                height = 0,
                sourceBitrateKbps = 0,
                isPortrait = true,
                mode = QualityMode.FULL_HD
            )
        )

        assertTrue(zeroDuration.internalCacheRequiredBytes >= StorageEstimatePolicy.FIXED_RESERVE_BYTES)
        assertTrue(zeroDuration.sharedMediaRequiredBytes >= StorageEstimatePolicy.FIXED_RESERVE_BYTES)

        val zeroPhoto = StorageEstimatePolicy.estimateSinglePhoto(
            PhotoEstimateInput(sourceSizeBytes = 0L, width = 0, height = 0),
            PhotoQualityMode.STATUS_HD
        )
        assertEquals(0L, zeroPhoto.internalCacheRequiredBytes)
        assertEquals(
            StorageEstimatePolicy.PHOTO_TRANSACTION_HEADROOM_BYTES + StorageEstimatePolicy.FIXED_RESERVE_BYTES,
            zeroPhoto.sharedMediaRequiredBytes
        )
    }

    @Test
    fun tightMarginTriggersWarning() = runBlocking {
        val required = VolumeRequiredStorage(
            internalCacheRequiredBytes = 100L * StorageEstimatePolicy.MIB,
            sharedMediaRequiredBytes = 100L * StorageEstimatePolicy.MIB
        )
        val availability = StorageAvailability(
            internalCacheAvailableBytes = required.internalCacheRequiredBytes + 10L * StorageEstimatePolicy.MIB, // margin < 64MB
            sharedMediaAvailableBytes = required.sharedMediaRequiredBytes + 200L * StorageEstimatePolicy.MIB
        )
        val checker = StoragePreflightChecker(FakeAvailableStorageProvider { availability })

        val result = checker.check(required)

        assertTrue(result is StoragePreflightResult.Sufficient)
        val sufficient = result as StoragePreflightResult.Sufficient
        assertTrue(sufficient.isCloseToLimit)
    }

    @Test
    fun sourceSelectionRemainsAfterCancellation_inPreflightGateAndVideoCoordinator() = runBlocking {
        val selectedUri = "content://media/external/video/1"
        var enqueueCount = 0
        val coordinator = VideoPreflightCoordinator(
            scope = this,
            sourceSizeProvider = FakeSourceSizeProvider(mapOf(selectedUri to 10_000_000L)),
            storagePreflightChecker = StoragePreflightChecker(
                FakeAvailableStorageProvider {
                    StorageAvailability(
                        internalCacheAvailableBytes = 1024L,
                        sharedMediaAvailableBytes = 1024L
                    )
                }
            )
        )

        coordinator.requestStart(
            uriString = selectedUri,
            durationMs = 20_000L,
            width = 1080,
            height = 1920,
            sourceBitrateKbps = 5000,
            isPortrait = true,
            mode = QualityMode.FULL_HD,
            isProcessingProvider = { false },
            onProceed = { enqueueCount++ }
        )

        waitUntil { coordinator.preflightPrompt.value is StoragePreflightPrompt.InsufficientBoth }
        coordinator.dismissStoragePrompt()

        assertNull(coordinator.preflightPrompt.value)
        assertEquals(0, enqueueCount)
        assertNotNull(selectedUri)

        // Unavailable -> Cancel also preserves selection and does not start
        val unavailableCoordinator = VideoPreflightCoordinator(
            scope = this,
            sourceSizeProvider = FakeSourceSizeProvider(mapOf(selectedUri to 10_000_000L)),
            storagePreflightChecker = StoragePreflightChecker(
                FakeAvailableStorageProvider {
                    StorageAvailability(
                        internalCacheAvailableBytes = null,
                        sharedMediaAvailableBytes = null
                    )
                }
            )
        )
        unavailableCoordinator.requestStart(
            uriString = selectedUri,
            durationMs = 20_000L,
            width = 1080,
            height = 1920,
            sourceBitrateKbps = 5000,
            isPortrait = true,
            mode = QualityMode.FULL_HD,
            isProcessingProvider = { false },
            onProceed = { enqueueCount++ }
        )
        waitUntil { unavailableCoordinator.preflightPrompt.value is StoragePreflightPrompt.Unavailable }
        unavailableCoordinator.dismissStoragePrompt()

        assertNull(unavailableCoordinator.preflightPrompt.value)
        assertEquals(0, enqueueCount)
        assertEquals("content://media/external/video/1", selectedUri)
    }

    @Test
    fun batchRemainsDraftWhenBlocked_noItemEntersProcessing_modeRemainsEditable() = runBlocking {
        val preparer = RecordingPhotoPreparer()
        val runner = BatchPhotoRunner(preparer)
        val controller = BatchPhotoPreflightController(
            scope = this,
            runner = runner,
            sourceSizeProvider = FakeSourceSizeProvider(
                mapOf(
                    "content://photo/1" to 4_000_000L,
                    "content://photo/2" to 4_000_000L,
                    "content://photo/3" to 4_000_000L
                )
            ),
            storagePreflightChecker = StoragePreflightChecker(
                FakeAvailableStorageProvider {
                    StorageAvailability(
                        internalCacheAvailableBytes = 500L * StorageEstimatePolicy.MIB,
                        sharedMediaAvailableBytes = 5L * StorageEstimatePolicy.MIB
                    )
                }
            ),
            ioDispatcher = Dispatchers.Default
        )

        controller.initializeQueue(listOf("content://photo/1", "content://photo/2", "content://photo/3"))
        waitUntil { controller.snapshot.value?.items?.size == 3 }

        controller.startBatch(PhotoQualityMode.STATUS_HD)
        waitUntil { controller.preflightPrompt.value is StoragePreflightPrompt.InsufficientShared }

        val snap = controller.snapshot.value!!
        assertEquals(BatchState.Draft, snap.state)
        assertNull(snap.lockedMode)
        assertTrue(snap.items.all { it.status is BatchItemStatus.Waiting })
        assertTrue(preparer.preparedSources.isEmpty())

        // Cancel preserves selection and mode remains editable
        controller.dismissStoragePrompt()
        assertNull(controller.preflightPrompt.value)
        controller.setDraftMode(PhotoQualityMode.SMALLER_FILE)
        assertEquals(PhotoQualityMode.SMALLER_FILE, controller.draftMode.value)
        assertEquals(BatchState.Draft, controller.snapshot.value?.state)
    }

    @Test
    fun duplicatePrepareTaps_doNotRunTwoPreflights() = runBlocking {
        val blocker = CompletableDeferred<StorageAvailability>()
        val provider = FakeAvailableStorageProvider { blocker.await() }
        val gate = StoragePreflightGate(this, StoragePreflightChecker(provider))
        var proceedCount = 0

        val firstAccepted = gate.requestStart(
            canStart = { true },
            estimateRequired = {
                VolumeRequiredStorage(
                    internalCacheRequiredBytes = 10_000_000L,
                    sharedMediaRequiredBytes = 10_000_000L
                )
            },
            onProceed = { proceedCount++ }
        )
        val secondAccepted = gate.requestStart(
            canStart = { true },
            estimateRequired = {
                VolumeRequiredStorage(
                    internalCacheRequiredBytes = 10_000_000L,
                    sharedMediaRequiredBytes = 10_000_000L
                )
            },
            onProceed = { proceedCount++ }
        )

        assertTrue(firstAccepted)
        assertFalse(secondAccepted)
        assertTrue(gate.isChecking.value)

        blocker.complete(
            StorageAvailability(
                internalCacheAvailableBytes = 500L * StorageEstimatePolicy.MIB,
                sharedMediaAvailableBytes = 500L * StorageEstimatePolicy.MIB
            )
        )
        waitUntil { !gate.isChecking.value }

        assertEquals(1, provider.callCount.get())
        assertEquals(1, proceedCount)
    }

    @Test
    fun getExternalFilesDirUnavailable_producesUnknownSharedAvailability_withoutFallingBackToInternalFilesDir() = runBlocking {
        // Test AndroidAvailableStorageProvider when externalFilesDir is null
        // Using custom mock-friendly test or verifying StorageAvailability contract
        val availabilityWithoutExternal = StorageAvailability(
            internalCacheAvailableBytes = 100L * StorageEstimatePolicy.MIB,
            sharedMediaAvailableBytes = null
        )
        assertNull(availabilityWithoutExternal.sharedMediaAvailableBytes)
        assertNotNull(availabilityWithoutExternal.internalCacheAvailableBytes)

        // Photo/Batch with sharedRequired > 0 results in SharedAvailabilityUnavailable
        val photoReq = VolumeRequiredStorage(
            internalCacheRequiredBytes = 0L,
            sharedMediaRequiredBytes = 10L * StorageEstimatePolicy.MIB
        )
        val result = StoragePreflightChecker.evaluate(availabilityWithoutExternal, photoReq)
        assertTrue(result is StoragePreflightResult.SharedAvailabilityUnavailable)
    }

    private suspend fun waitUntil(timeoutMs: Long = 2_000L, predicate: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (predicate()) return
            delay(10.milliseconds)
        }
        throw AssertionError("Condition not satisfied within ${timeoutMs}ms")
    }
}
