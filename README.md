# StatusHD

StatusHD is an experimental native Android student project for preparing photos
and videos for manual sharing through Android applications such as WhatsApp.

> StatusHD prepares media locally with the goal of reducing visible quality loss
> during status uploads while keeping media selection, Gallery publication, and
> sharing under the user’s control.

## Project status

StatusHD is a working student project and remains experimental.

It is not a production-quality media editor. Output quality, processing time,
storage behavior, and the treatment of shared media can vary depending on:

- the source media,
- the Android device,
- available storage and memory,
- the selected processing mode,
- the receiving application,
- and the receiving application’s current compression behavior.

StatusHD cannot control or guarantee how WhatsApp or another application
recompresses uploaded media.

## Features

### Video preparation

StatusHD supports two video-processing modes.

#### Full HD Detail

- Targets a `1080 × 1920` portrait output profile
- Targets a `1920 × 1080` landscape output profile
- Uses H.264 video encoding
- Splits longer output into parts of up to 30 seconds
- Prioritizes additional visual detail

#### HD Longer

- Targets a `720 × 1280` portrait output profile
- Targets a `1280 × 720` landscape output profile
- Uses H.264 video encoding
- Splits longer output into parts of up to 60 seconds
- Produces fewer parts for longer videos

The selected-video screen displays an estimated part count based on the source
duration and the selected mode. This is a segment-count estimate, not an
estimate of output size or processing time.

#### Preserve Original

When the source video already fits the selected profile’s duration, dimensions,
and reported bitrate conditions, StatusHD may copy the source without
transcoding it.

Preserve Original is an automatic optimization. It is not a separate
user-selectable quality mode.

### Video processing controls

- Video selection through Android Photo Picker
- Persistent **Prepare video** action
- Background processing through AndroidX WorkManager
- Foreground notification while processing
- Current progress reporting
- User-requested cancellation
- Automatic segment creation for longer videos
- Publication of completed MP4 files through MediaStore
- Output destination: `Movies/HDStatus`

### Video sharing

Completed video parts can be shared manually through the Android Sharesheet.

StatusHD supports:

- sharing one completed video part,
- sharing individual parts separately,
- and sharing multiple completed parts together.

One part uses `ACTION_SEND`. Multiple parts use `ACTION_SEND_MULTIPLE` with
ordered MediaStore content URIs and temporary read permission.

StatusHD does not automatically send media, control WhatsApp, or hardcode a
specific receiving application.

### Photo preparation

StatusHD supports JPEG photo preparation with two quality modes.

#### Status HD

- JPEG quality 90
- Fits portrait and square photos within `1080 × 1920`
- Fits landscape photos within `1920 × 1080`
- Preserves aspect ratio
- Does not crop, stretch, or upscale

#### Smaller File

- JPEG quality 80
- Uses the same dimension and aspect-ratio rules
- Produces a smaller file in many cases, depending on the source image

Single-photo outputs are published to:

```text
Pictures/StatusHD
