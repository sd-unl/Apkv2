# FFmpeg GUI Android App

A fully-featured Android application that wraps FFmpeg with a graphical user interface.

## Features
- Pick video/audio files from device storage
- Run common FFmpeg operations (convert, compress, extract audio, trim, etc.)
- Real-time FFmpeg log output
- Progress tracking
- Custom FFmpeg command input
- Built using [arthenica/mobile-ffmpeg](https://github.com/arthenica/mobile-ffmpeg) (now ffmpeg-kit)

## Build Instructions

### Prerequisites
- Android Studio (Hedgehog or newer)
- JDK 17
- Android SDK (API 24+)
- Gradle 8.x

### Steps
1. Clone or extract this project
2. Open in Android Studio
3. Sync Gradle (File > Sync Project with Gradle Files)
4. Connect device or start emulator (API 24+)
5. Click Run or use `./gradlew assembleDebug`
6. Find APK at: `app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions
Push to GitHub and the included workflow will automatically build the APK.
Download the artifact from the Actions tab.

## FFmpeg Operations Supported
- Convert video format (e.g., MKV → MP4)
- Extract audio (video → MP3/AAC)
- Compress video (reduce bitrate)
- Trim video (set start/end time)
- Change resolution
- Custom FFmpeg commands

## Library Used
- `com.arthenica:ffmpeg-kit-android-full:6.0-2`
  See: https://github.com/arthenica/ffmpeg-kit
