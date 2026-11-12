# MP3Box

An offline music player for Android that plays the MP3 and M4A files in **one folder** you pick.
Kotlin + Jetpack Compose, Media3/ExoPlayer, Room. No network, no accounts, no ads, no analytics.

Built for DokeOS 4.0 (Android 13), but it runs on any Android 12+
device. The APK is sideloaded; there is no Play Store release.

---

## Features

### Library
- One music folder picked through the system file picker (SAF) — the app declares **no storage
  permissions**. Only files directly inside the folder are listed; subfolders are ignored.
- `.mp3` and `.m4a` only. Other formats are not indexed.
- Titles come from file names: underscores become spaces and a trailing number run is treated as a
  duplicate suffix (`My_Song2.mp3` → `My Song`; `Test3Test.m4a` is unchanged).
- Natural A–Z ordering, so `Track 2` sorts before `Track 10`.
- Live search by title, header with song count and total playable time.
- Rescan on launch, by pull-to-refresh, or from the toolbar. The snackbar reports what changed
  (`+3 new, 1 missing`); a file is reported as missing only on the scan that loses it.
- Folder permission lost or folder deleted → banner with a `Fix` button; the cached library and
  playlists are kept.
- Missing files leave the Songs list. A track that is still in a playlist stays as a dimmed
  `File missing` stub there, is skipped during playback, and is deleted for good once its last
  playlist entry is removed.

### Playlists
- Create from the Playlists tab or straight from a song's `Add to playlist` sheet.
- Add and remove songs, reorder by drag (blocked while shuffle is on), rename, duplicate, delete.
- Custom cover image from the gallery (downscaled and copied into app storage); generated
  artwork-style tiles otherwise.
- Duplicates are prevented by a unique `(playlistId, trackId)` index.
- The grid orders playlists by last edit; each card shows song count and playable duration.

### Playback
- ExoPlayer behind a `MediaSessionService`: playback survives the screen locking, the app being
  backgrounded, headphone unplug handling, Bluetooth and the system media notification/lock screen.
- Playlist playback loops by default. Repeat cycles **all ↔ one** (no off).
- Shuffle reorders the live queue without touching the saved playlist order, and turning it off
  restores that order with the current track still playing. The shuffled order survives an app
  restart; switching shuffle off and back on deals a fresh order.
- Mini-player docked above the bottom navigation, expanding into a full Now Playing screen with
  artwork, scrubber and transport controls.
- Launch restores the last queue at the saved track, paused at 0:00 with shuffle/repeat reapplied.

---

## Requirements

| Item | Value |
|---|---|
| Android | 12+ (`minSdk 31`); developed and checked against API 33 |
| SDK levels | `compileSdk 37`, `targetSdk 36` |
| JDK | 17 or newer for Gradle (verified with Temurin 21) |
| Android SDK | platform `android-37`, build-tools `37.0.0` |
| Gradle | wrapper included, 9.6.0 |

## Build

Point Gradle at a JDK and the Android SDK, then build the debug APK:

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install it over USB debugging:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`ANDROID_HOME` may be replaced by a `local.properties` file containing `sdk.dir=/path/to/android-sdk`
(git-ignored, not portable).

The release variant (`./gradlew :app:assembleRelease`) is deliberately signed with the machine's
debug keystore so debug and release builds update each other on the same device. No keystore is
committed; this app is sideload-only.

## Permissions

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | keep audio playing in the background |
| `POST_NOTIFICATIONS` | media notification and lock-screen controls (runtime prompt on API 33) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | optional prompt, since DokeOS/DuraSpeed suspends background services |

No `READ_MEDIA_AUDIO` or `READ_EXTERNAL_STORAGE`: the SAF folder grant covers every file the app
touches.

## Tech stack

| Concern | Choice |
|---|---|
| UI | Jetpack Compose, Material 3, Compose BOM 2026.09.00 |
| Language | Kotlin 2.4.10 (built-in Kotlin via AGP 9.4.1) |
| Build | Gradle 9.6.0, AGP 9.4.1, KSP 2.3.13 |
| Playback | AndroidX Media3 ExoPlayer + MediaSession 1.11.1 |
| Persistence | Room 2.8.5 (`tracks`, `playlists`, `playlist_items`, `playback_state`) |
| Preferences | DataStore Preferences 1.3.0 |
| Images | Coil 3.6.3 |
| Navigation | Navigation Compose 2.10.2 |
| DI | Manual `AppContainer` (no Hilt) |

## Project structure

```text
app/src/main/java/com/local/mp3box/
├── MainActivity.kt, Mp3BoxApp.kt     single activity, application setup
├── di/AppContainer.kt                manual dependency graph
├── data/
│   ├── MusicFolderRepository.kt      SAF grant, scans, ScanResult, FolderState
│   ├── MediaScanner.kt, SettingsStore.kt
│   ├── PlaylistRepository.kt, CoverArtStore.kt, PlaybackStateRepository.kt
│   └── db/                           Room entities, DAOs, AppDatabase
├── playback/                         PlaybackService, PlayerRepository, QueueFactory
├── ui/
│   ├── library/, playlists/, playlist/, nowplaying/, player/
│   ├── components/                   TrackRow/Tile, MiniPlayer, PlaylistCard, sheets, banners
│   ├── navigation/, theme/
├── util/                             title derivation, natural sort, duration formatting, artwork
app/src/main/res/                     strings, theme, launcher icon
```

## Documentation

- [`mp3box-spec.md`](mp3box-spec.md) — product and technical specification.
- [`mp3box-implementation-plan.md`](mp3box-implementation-plan.md) — architecture, DAO/scan
  pseudocode and build phases.
- [`testing-requirements.md`](testing-requirements.md) — test matrix per layer and the manual
  device checks.

The app currently has no test sources; the testing document is the plan for them.

## Out of scope for v1

Tag/metadata parsing (no artist, album, track numbers), embedded cover extraction, formats other
than MP3/M4A, recursive folder scanning, streaming or network features, playlist import/export,
and translations. The UI is English-only, always dark, with a fixed accent colour.
