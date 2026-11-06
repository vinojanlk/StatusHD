# Status HD

Status HD is an experimental Android student project for preparing videos for sharing as WhatsApp Status.

> Status HD prepares videos with the goal of reducing visible quality loss during WhatsApp Status upload.

## Project status

This is a working student project and remains experimental. It is not a production-quality media editor, and output quality, processing time, and WhatsApp's treatment of shared video can vary by source video, device, and app version.

## Features

- **Full HD Detail**: targets a 1080 × 1920 portrait (or 1920 × 1080 landscape) H.264 output profile and splits output at 30-second intervals.
- **HD Longer Status**: targets a 720 × 1280 portrait (or 1280 × 720 landscape) H.264 output profile and splits output at 60-second intervals.
- **Preserve Original**: automatically copies the input without transcoding when its duration, dimensions, and reported bitrate fit the selected profile.
- Background processing with a foreground notification, progress reporting, and cancellation.
- Automatic segment output for longer videos.
- Publication of completed MP4 files to the device Gallery through MediaStore.
- Manual sharing through the Android share intent, targeting WhatsApp when available.
- Video selection through the Android Photo Picker.

The output profiles are targets, not guarantees about final dimensions, bitrate, playback, or WhatsApp upload behavior.

## Privacy

The app reads the video selected through Android's Photo Picker, processes it locally using the app and its bundled native media libraries, and writes completed output to the device's Movies/HDStatus Gallery location. The project does not implement a video-upload service. Sharing sends selected output to the app chosen by the user, such as WhatsApp; those apps' privacy practices apply after sharing.

## Technology

- Kotlin and Jetpack Compose
- AndroidX Lifecycle `ViewModel` with Kotlin `StateFlow`
- AndroidX WorkManager for background work
- Android Photo Picker and MediaStore
- FFmpegKit maintained distribution (`ffmpeg-kit-min-gpl:8.1.9`), FFmpeg, and libx264
- Minimum Android API 29; target API 35

### Foreground-service compatibility

The foreground worker uses the `dataSync` foreground-service type on API 29–34 and `mediaProcessing` on API 35 and later. The manifest declares both corresponding foreground-service permissions and service types.

## Build

Install a compatible JDK and Android SDK (including the platform/build tools required by the Gradle configuration), then use the Gradle Wrapper from the project root.

**Windows PowerShell**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

**Linux/macOS**

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The wrapper downloads its configured Gradle distribution if it is not already cached. Keep machine-specific SDK configuration in the ignored `local.properties` file; do not commit signing keys or credentials.

## Limitations

- This is an experimental student project; processing and behavior have not been validated across all Android devices or source formats.
- Segment boundaries can vary with source media and the segment muxer; verify duration and playback for the intended sharing workflow.
- Audio synchronization, cancellation timing, storage behavior, and WhatsApp handling should be tested on a physical device.
- WhatsApp may transcode or recompress shared videos. Status HD cannot control or guarantee the result.
- Preserve Original is conditional and is not a user-selectable standalone quality profile.
- The project does not currently include an automated physical-device test suite.

## Screenshots

Screenshots are intentionally not included automatically. See [docs/screenshots/README.md](docs/screenshots/README.md) for the manual review requirements and relative screenshot path.

## Third-party licensing

See [NOTICE.md](NOTICE.md) and [LICENSE-REVIEW.md](LICENSE-REVIEW.md) for the resolved FFmpegKit dependency metadata, third-party notices, and the project's pending license review. No project license has been selected pending confirmation of the applicable GPL obligations.

## Disclaimer

Status HD is an independent experimental project and is not affiliated with, endorsed by or sponsored by WhatsApp or Meta.
