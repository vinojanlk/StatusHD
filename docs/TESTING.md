# Testing

## Unit tests

Run the app's local unit tests from the project root:

**Windows**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

**Linux/macOS**

```sh
./gradlew :app:testDebugUnitTest
```

The current unit tests cover project logic such as bitrate calculation. They do not replace physical-device checks of Android services, media decoding, Gallery publication, or external sharing.

## Physical-device checklist

- Run a short and a longer portrait video in **Full HD Detail**; check output dimensions, playback, and segment durations around the 30-second split interval.
- Repeat in **HD Longer Status**; check output dimensions, playback, and segment durations around the 60-second split interval.
- Check that a source that meets the selected profile's duration, dimensions, and reported bitrate limits follows the **Preserve Original** path.
- Check audio/video synchronization at the beginning, middle, and end of both single-part and split outputs.
- Cancel during processing; confirm the worker stops, the UI reflects cancellation, and temporary cache files are cleaned up.
- Confirm each completed part appears in Gallery under Movies/HDStatus and can be opened.
- Use the app's manual sharing action to select WhatsApp and verify that the intended video part(s) are attached; inspect the received/status result without assuming WhatsApp preserves the source encoding.
- Exercise the foreground worker on API 34 (`DATA_SYNC`) and API 35 or later (`MEDIA_PROCESSING`) devices or emulators.
- Verify notification permission and behavior on Android versions that require it.

## Known experimental limitations

Segment muxer boundaries may not equal exact requested durations. Runtime behavior, cancellation timing, codecs, storage, and external-app handling vary across devices and OS versions. WhatsApp may transcode or recompress shared videos, and Status HD cannot guarantee delivered resolution, bitrate, or visual quality.
