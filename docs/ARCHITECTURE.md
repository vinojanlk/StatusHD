# Architecture

## UI and state

`MainActivity` hosts the Jetpack Compose screen. It launches the Android Photo Picker for video selection, reads selected-video metadata, presents the quality choices and processing state, and exposes progress, cancellation, Gallery results, and manual sharing.

`VideoViewModel` owns the screen's `UiState` in a `StateFlow`. It enqueues and observes the unique WorkManager request, translates work progress and terminal states into UI state, and requests cancellation through WorkManager.

## Processing pipeline

1. The Photo Picker returns a content URI. The activity obtains duration, dimensions, rotation, and reported bitrate through `MediaMetadataRetriever`.
2. The ViewModel determines whether the selected input meets the selected mode's preserve-original conditions and creates WorkManager input data.
3. `VideoWorker` runs as a `CoroutineWorker`, publishes foreground notifications, and invokes `VideoProcessor`.
4. `VideoProcessor` copies the selected input into app cache and invokes FFmpegKit with the configured encoding or stream-copy arguments. The transcode pipeline uses FFmpeg with libx264 for video encoding, AAC audio, scaling/padding, and the segment muxer. Statistics are converted to progress updates.
5. On success, the worker publishes output files to the Movies/HDStatus Gallery location through `MediaStoreHelper`, then deletes the temporary output files.
6. The ViewModel observes completion and the Compose UI offers a manual Android share action for the resulting MediaStore URIs.

## Foreground-service API compatibility

The worker supplies `FOREGROUND_SERVICE_TYPE_DATA_SYNC` on API 29–34 and `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` on API 35 and later. The merged manifest declares both corresponding service types and permissions. Below API 29 it uses the two-argument `ForegroundInfo` constructor.

## Cancellation and cleanup

The UI asks WorkManager to cancel the unique work. Worker cancellation propagates through the cancellable coroutine to FFmpegKit's session-cancel API. On cancellation and worker failure, `VideoWorker` attempts to delete the cached input and generated `output_*.mp4` files. `VideoProcessor` also removes the copied input in its `finally` block. After publication, the worker deletes each temporary output file.

Cleanup is best-effort; validate cancellation and temporary-file behavior on physical devices.
