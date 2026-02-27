# JAVC Player

An Android video player built with libVLC and Jetpack Compose, designed primarily for streaming videos from Stremio.

## Features

- **Stremio Integration** -- Registers as an external video player via `ACTION_VIEW` intent. Select this app as your external player in Stremio settings.
- **Resume Playback** -- Automatically remembers where you stopped in a video and resumes from that position next time.
- **Hold-to-2x Speed** -- Press and hold anywhere on the video to play at 2x speed. Release to return to normal speed.
- **Track Selection** -- Detects and lists all audio tracks, subtitle tracks, and chapters embedded in the stream. Accessible via the track selection button in the controls overlay.
- **Manual URL Entry** -- When launched directly (not from Stremio), provides a URL input screen to play any stream.

## Requirements

- Android 7.0 (API 24) or higher
- Android Studio Hedgehog or newer

## Building

1. Open the project in Android Studio
2. Sync Gradle files
3. Build and run on a device or emulator

## Dependencies

- [libVLC Android](https://code.videolan.org/videolan/vlc-android) (`org.videolan.android:libvlc-all:3.6.2`) -- Video playback engine
- Jetpack Compose with Material 3 -- UI framework
- Room -- Local database for resume position persistence
- Kotlin Coroutines -- Async state management

## Usage with Stremio

1. Install both Stremio and this app on your Android device
2. In Stremio, go to Settings and set the external player
3. When you play a video in Stremio, select "JAVC Player (Just Another Vibe Coded Player)" from the app chooser
4. The video will open in this player with full controls
