<p align="center">
  <img src="assets/banner.svg" alt="CloudWalk — Tiny native Android SoundCloud client" width="100%">
</p>

<p align="center">
  <a href="README.ja.md">日本語</a> · English
</p>

<p align="center">
  <a href="https://github.com/nisesimadao/CloudWalk/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/nisesimadao/CloudWalk?style=flat-square&color=ff6a00"></a>
  <a href="https://github.com/nisesimadao/CloudWalk/actions"><img alt="Android build" src="https://img.shields.io/github/actions/workflow/status/nisesimadao/CloudWalk/android.yml?branch=main&style=flat-square&label=build"></a>
  <img alt="Android 8+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="English and Japanese" src="https://img.shields.io/badge/UI-English%20%7C%20Japanese-555?style=flat-square">
  <img alt="No WebView" src="https://img.shields.io/badge/UI-Native%20Views-ff6a00?style=flat-square">
</p>

# CloudWalk

CloudWalk is a **tiny native Android SoundCloud client** built for fast startup, low memory use, and older phones. It uses Android Views and platform media APIs instead of a WebView or a heavy UI stack.

<p align="center">
  <a href="https://github.com/nisesimadao/CloudWalk/releases/latest"><b>Download the latest APK</b></a>
</p>

## What it does

- Search and play public SoundCloud tracks
- Cover Flow home screen and native Now Playing UI
- Queue, reorder, shuffle and repeat
- Background playback with MediaSession controls
- Temporary session cache for offline playback
- Local audio files in the same player
- English / Japanese UI
- Android 8.0 (API 26) and newer

## Screenshots

<table>
  <tr>
    <td align="center"><b>Home / Cover Flow</b></td>
    <td align="center"><b>Search</b></td>
    <td align="center"><b>Now Playing</b></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/home.png" width="260" alt="CloudWalk home"></td>
    <td><img src="docs/screenshots/search.png" width="260" alt="CloudWalk search"></td>
    <td><img src="docs/screenshots/now-playing.png" width="260" alt="CloudWalk now playing"></td>
  </tr>
  <tr>
    <td align="center"><b>Queue</b></td>
    <td align="center"><b>Library</b></td>
    <td></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/queue.png" width="260" alt="CloudWalk queue"></td>
    <td><img src="docs/screenshots/library.png" width="260" alt="CloudWalk library"></td>
    <td></td>
  </tr>
</table>

> Screenshots above are from the real app running on an Android 9 emulator at a small-phone resolution.

## Build

```sh
./gradlew assembleDebug
```

Release builds use R8/resource shrinking:

```sh
./gradlew assembleRelease
```

## Notes

CloudWalk is experimental and is **not an official SoundCloud app**. Public-track search/playback currently follows SoundCloud's public web client behavior, so SoundCloud-side changes can temporarily break it.

The session cache is temporary and is cleared when CloudWalk closes.
