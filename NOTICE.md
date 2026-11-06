# Third-party notices

Status HD uses third-party media software. This notice identifies the resolved Android dependency and licensing information available in its cached Maven POM; it is not legal advice and does not replace the license texts or source notices shipped by the relevant projects.

## FFmpegKit Android dependency

The app declares:

```text
dev.ffmpegkit-maintained:ffmpeg-kit-min-gpl:8.1.9
```

The resolved POM identifies the artifact as an actively maintained FFmpegKit drop-in distribution and describes GPL video encoders including x264. Its `<licenses>` metadata lists the GNU General Public License v3.0 and GNU Lesser General Public License v3.0:

- GPL v3.0, as linked by the dependency POM: <https://github.com/ffmpegkit-maintained/ffmpeg/blob/main/LICENSE.GPLv3>
- LGPL v3.0, as linked by the dependency POM: <https://github.com/ffmpegkit-maintained/ffmpeg/blob/main/LICENSE>
- Dependency metadata and releases: <https://github.com/ffmpegkit-maintained/ffmpeg/releases>

The selected `min-gpl` package contains GPL components. Confirm the full component list and the corresponding license and source obligations for the exact artifact before distributing compiled binaries.

## FFmpeg

FFmpeg is third-party software used by FFmpegKit. FFmpeg licensing depends on the build configuration and enabled components. The resolved package POM lists both GPL v3.0 and LGPL v3.0 and describes GPL components; consult the package's complete notices and FFmpeg's licensing information for the exact bundled build:

- <https://ffmpeg.org/legal.html>

## libx264

libx264 is third-party software included as a GPL encoder according to the resolved dependency's POM description. Its applicable terms must be verified against the exact bundled component and source distribution before redistribution:

- <https://code.videolan.org/videolan/x264>

## Redistribution

Redistribution of compiled APK/AAB files requires compliance with all applicable licenses, including relevant notice, source-availability, and other copyleft requirements. The project does not publish an APK/AAB as part of this repository preparation.

## Trademarks

WhatsApp and Meta names and marks belong to their respective owners. Their use in this project describes compatibility/sharing intent only and does not imply affiliation, endorsement, or sponsorship.
