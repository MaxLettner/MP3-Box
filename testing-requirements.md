# MP3Box — Testing Requirements

**Companion to:** `mp3box-spec.md` (what to build) and `mp3box-implementation-plan.md` (how to build it)
**Target:** DokeOS 4.0 / Android 13 (API 33), plus an API 33 AVD in this container
**Date:** 2026-11-06
**Status:** Requirements — nothing here is implemented yet (the app sources do not exist)

---

## 0. Read this first — a spec conflict to settle

The spec says, twice, that there is no test suite:

- §13 Out of scope: *"Instrumented/UI tests, CI workflow…"*
- §14 Decisions log: *"Tests / docs | App only — no test suite, no README"*

This document exists because you asked what is needed to test everything. It is written in **two
tracks** so the decision stays yours:

| Track | Adds | Covers | Cost |
|---|---|---|---|
| **A — Verification only** | no test dependencies; emulator + manual checklists + APK inspection | build correctness, install/launch, every user flow by hand, DokeOS behaviour | ~2–3 h of device/emulator time, no regression safety later |
| **B — Automated suite (recommended)** | JVM unit tests + Room/SAF/Media3 instrumented tests + Compose UI tests | everything in Track A, repeatably, plus logic that is impractical to test by hand (scan diff, shuffle restore, repeat cycle, resume) | ~1.5–2 days of work, ~250 MB deps, one API 33 AVD |

**Recommendation:** Track B for the data/playback logic (cheap, catches the bugs that matter), Track A
for DokeOS-specific behaviour (cannot be automated here). Sections 1, 4, 8–13 apply either way;
sections 2, 3, 5–7 are Track B only.

If you want to keep the spec's "no test suite" rule, delete sections 2, 3, 5–7 and the
`testImplementation` blocks in §3 — the rest still works as a manual verification plan.

---

## 1. What "testing everything" means here

Five layers, all needed; none substitutes for another.

| Layer | Tool | Where it runs | What it proves |
|---|---|---|---|
| L0 Build & package | Gradle, `aapt2`, `apkanalyzer` | this container | compiles, manifest/permissions/SDK levels correct, APK installable |
| L1 Pure logic | JUnit 4 + Truth (`testDebugUnitTest`) | JVM here | title derivation, formatting, queue/restore/repeat plans, scan diff, dedupe, reorder, stats, filter/sort |
| L2 Android integration | Robolectric + `androidx.test` + Room in-memory | JVM here | DAO SQL, Flow behaviour, folder-state machine, debounce, cover downscale |
| L3 UI + real SAF + player | Compose UI test, UiAutomator, `MediaController` (`connectedDebugAndroidTest`) | API 33 AVD | screens, missing-track behaviour, real tree URIs, real `MediaExtractor` durations, media session/queue |
| L4 Device-only behaviour | manual + `adb` shell assertions | DokeOS device | DokeOS battery killer, notification/lockscreen card, vendor pickers, audio routing, Bluetooth/headset |

**The honest global answer:** audio output, headphone-unplug handling, real Bluetooth/AVRCP, the
DokeOS media card and the DokeOS battery manager cannot be tested anywhere except the phone. Anything
that claims otherwise is guessing.

---

## 2. Environment prerequisites (measured 2026-11-06)

### 2.1 Already present

| Requirement | State |
|---|---|
| JDK 21 (Gradle) | Temurin **21.0.13** at `~/.local/jdk` (system default is JDK 27 — must not be used) |
| Gradle | **9.6.0** at `~/.local/gradle/gradle-9.6.0` |
| Android SDK | `~/android-sdk`: platform-tools **37.0.1**, `platforms;android-36`, `platforms;android-37.0`, `build-tools;36.0.0`, `build-tools;37.0.0`, cmdline-tools |
| Media fixture tooling | `ffmpeg` **9.0.2** with `libmp3lame`, `lame` **4.0**, `ffprobe` |
| Virtualisation | `/dev/kvm` present (rw), 16 cores, x86_64 |
| Network | available (Maven Central + Google Maven reachable) |

### 2.2 Still to install

| Requirement | How | Size |
|---|---|---|
| Android emulator | `sdkmanager "emulator"` | ~400 MB |
| API 33 system image | `sdkmanager "system-images;android-33;google_apis;x86_64"` | ~1.5 GB |
| AVD | `avdmanager create avd -n mp3box33 -d pixel_6 -k …` | ~2 GB |
| Test fixtures | `tools/gen-test-media.sh` (below) | ~40 MB (dominated by the 62-min file) |

### 2.3 Resource budget — the real constraint

- **RAM: 3.4 GB available** of 14 GB. Emulator (`-memory 2048`, headless) + Gradle test JVM (~1.5 GB)
  is at the edge. Rules: `--no-daemon`, `-no-window`, run unit tests *or* instrumented tests, not
  both, and `adb emu kill` when done. If OOM-killed, drop the AVD to `-memory 1536`.
- **Disk: 30 GB free.** AVD + image ≈ 3.5 GB, test deps ≈ 250 MB, fixtures ≈ 40 MB, build ≈ 1 GB.
- **Time:** unit suite < 30 s; instrumented suite 2–5 min after boot; emulator cold boot 60–120 s.

### 2.4 The phone (user side)

- USB debugging enabled, cable attached, `adb devices` shows it and the RSA prompt is accepted.
- Only needed for Track L4; everything else works without it.
- `adb` in this container is **37.0.1**, fine for API 33.

---

## 3. Gradle wiring (Track B)

`app/build.gradle.kts` additions (versions verified against Google Maven / Maven Central today):

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true      // Robolectric
            // deliberately NO isReturnDefaultValues = true: that hides Android calls
            // instead of forcing the seams in §5.
        }
    }
}

dependencies {
    // L1 — pure logic
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("app.cash.turbine:turbine:1.2.1")            // optional, Flow assertions

    // L2 — Android classes on the JVM
    testImplementation("org.robolectric:robolectric:4.17")        // @Config(sdk = [33])
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.room:room-testing:2.8.5")
    testImplementation("io.mockk:mockk:1.14.11")                  // optional; fakes preferred

    // L3 — device/emulator
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.1")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("androidx.media3:media3-test-utils:1.11.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

Notes:

- Compose UI test versions come from the BOM already pinned in the plan — do not add literal versions.
- `media3-test-utils` must match the player version (`1.11.1`), not the newest alpha.
- Robolectric is used at `sdk = 33` on purpose: the phone is API 33 and that is the runtime being
  judged. Do not let a test pass only because it ran on a newer/older shadow.
- `room-testing` is only strictly needed for `MigrationTestHelper`; v1 has one schema version, so the
  in-memory builders in `androidx.room:room-runtime` are enough. Keep the dependency out if it causes
  duplicate-class errors across source sets.

---

## 4. Test media fixtures (both tracks)

Generated, never committed binaries. Script: `tools/gen-test-media.sh` → output `tools/test-media/`.
`ffmpeg 9.0.2` + `libmp3lame` and the native AAC encoder are available, so this is reproducible offline.

### 4.1 Library folder (`tools/test-media/library/`)

| # | File | Made with | Purpose |
|---|---|---|---|
| 1 | `01_My_Song_final.mp3` | 3 s 440 Hz sine, 128 kbps | spec §4.2 headline example; queue/default play |
| 2 | `Short_Track.mp3` | 1 s silence, 96 kbps | `0:01` label, fast repeat-one check |
| 3 | `Long_62min.mp3` | 3725 s sine, 64 kbps (~30 MB) | `1 h 02 min` playlist stat formatting |
| 4 | `UPPER.MP3` | 2 s sine, 128 kbps | case-insensitive `.MP3` extension |
| 5 | `Empty_File.mp3` | `: > file` | duration `0` → `--:--`, must not crash scan |
| 6 | `Corrupt.mp3` | 4 KB of `/dev/urandom` | extractor failure → `durationMs = 0`, row still listed |
| 7 | `Café_déjà_vu.mp3` | 2 s sine | UTF-8 filename, title derivation |
| 8 | `.mp3` (hidden) | 2 s sine | blank base name → fallback to raw file name (spec §4.2.4) |
| 9 | `subfolder/nested.mp3` | 2 s sine | **must not be indexed** (top level only, spec §5.1) |
| 10 | `M4A_Track.m4a` | 2 s AAC | M4A is indexed, gets a duration and plays (spec §5.1) |
| 11 | `cover.jpg`, `notes.txt` | ffmpeg / `echo` | non-audio ignored |

Exact commands:

```bash
set -euo pipefail
mkdir -p tools/test-media/library/subfolder tools/test-media/covers
L=tools/test-media/library

ffmpeg -hide_banner -loglevel error -f lavfi -i "sine=frequency=440:duration=3"  -c:a libmp3lame -b:a 128k "$L/01_My_Song_final.mp3"
ffmpeg -hide_banner -loglevel error -f lavfi -i "anullsrc=r=44100:cl=mono" -t 1 -c:a libmp3lame -b:a 96k "$L/Short_Track.mp3"
ffmpeg -hide_banner -loglevel error -f lavfi -i "sine=frequency=220:duration=3725" -c:a libmp3lame -b:a 64k "$L/Long_62min.mp3"
ffmpeg -hide_banner -loglevel error -f lavfi -i "sine=frequency=330:duration=2"  -c:a libmp3lame -b:a 128k "$L/UPPER.MP3"
: > "$L/Empty_File.mp3"
head -c 4096 /dev/urandom > "$L/Corrupt.mp3"
ffmpeg -hide_banner -loglevel error -f lavfi -i "sine=frequency=550:duration=2"  -c:a libmp3lame -b:a 128k "$L/Café_déjà_vu.mp3"
ffmpeg -hide_banner -loglevel error -f lavfi -i "sine=frequency=660:duration=2"  -c:a libmp3lame -b:a 128k "$L/.mp3"
ffmpeg -hide_banner -loglevel error -f lavfi -i "sine=frequency=770:duration=2"  -c:a libmp3lame -b:a 128k "$L/subfolder/nested.mp3"
ffmpeg -hide_banner -loglevel error -f lavfi -i "sine=frequency=880:duration=2"  -c:a aac "$L/M4A_Track.m4a"
ffmpeg -hide_banner -loglevel error -f lavfi -i "color=c=red:s=1200x1200:d=1" -frames:v 1 "$L/cover.jpg"
echo "not audio" > "$L/notes.txt"
```

| # | Cover fixture | Purpose |
|---|---|---|
| 12 | `covers/cover_1200.jpg` (1200 × 1200) | downscale to ≤ 640 px, JPEG output |
| 13 | `covers/cover_200.jpg` | no upscaling below 640 |
| 14 | `covers/broken.jpg` (text bytes) | import failure leaves the old cover intact |

Optional stress fixture (only for scan timing, not correctness): 500 one-second MP3s named
`Stress_001.mp3`…`Stress_500.mp3` (`for i in $(seq -w 1 500); do ffmpeg … "$L/Stress_$i.mp3"; done`).
Under spec §4.2 every one of them is titled `Stress`, since the trailing numbers are ignored; that is
expected and harmless for a timing fixture.
Scanning must stay off the main thread and show progress; there is no hard time budget on the target device,
but a visible UI stall on 500 files is a failure of spec §5.1/§8.3.

### 4.2 Pushing fixtures

```bash
export ANDROID_HOME=$HOME/android-sdk
ADB=$ANDROID_HOME/platform-tools/adb

$ADB shell mkdir -p /sdcard/Download/MP3BoxTest
$ADB push tools/test-media/library/. /sdcard/Download/MP3BoxTest/
$ADB shell mkdir -p /sdcard/Pictures/MP3BoxCover
$ADB push tools/test-media/covers/. /sdcard/Pictures/MP3BoxCover/

# trigger a media scan so DocumentsUI sees the files
$ADB shell cmd media_scanner scan /sdcard/Download/MP3BoxTest 2>/dev/null || true
```

Expected index after a scan: **9 tracks** (files 1–8 plus the M4A; the file named `.mp3` may be hidden
by some providers — if it is not listed, that is acceptable, assert 8–9 and cover the blank-name rule in
unit tests). `nested.mp3`, `cover.jpg` and `notes.txt` must never appear; `M4A_Track.m4a` must.

---

## 5. Production-code seams required to test "everything"

These are not test code; they are small shape changes to the planned classes. Without them, the
important logic can only be tested by hand. All are cheap (a few lines each) and improve the design.

| # | Seam | Why |
|---|---|---|
| S1 | `util/DisplayName.kt` exposes `titleFromFileName(fileName: String): String` as a pure function | spec §4.2 table tests; no Android |
| S2 | `util/Duration.kt` exposes `formatClock(ms)` and `formatPlaylistLength(ms)` as pure functions | `--:--`, `0:42`, `1 h 05 min` are exact, testable strings |
| S3 | `MediaScanner` takes collaborators: `interface DocumentLister { suspend fun list(treeUri: Uri): List<FoundFile> }` and `interface DurationReader { fun read(uri: Uri): Long }` | scan diff logic testable with a fake lister and fake durations — no SAF, no decoder |
| S4 | `MediaScanner` takes a `clock: () -> Long` (or `Clock`) | `addedAt`/`lastSeenAt` assertions without sleeping |
| S5 | `MusicFolderRepository` takes `interface PermissionChecker { fun hasTreeGrant(uri: Uri): Boolean }` and a `FolderLister` | `NoFolder` / `Ok` / `Revoked` transitions are testable and can be forced |
| S6 | `QueueFactory` pure: `fun mediaItems(entries: List<PlaylistEntryRow>): List<MediaItem>` and `fun startIndex(items, trackId): Int` | queue order, missing filtering, tapped-track index |
| S7 | `playback/PlaybackPlanner.kt` pure: `nextRepeat(mode)`, `planRestore(snapshot, queue)`, `planPlaylistStart(...)` | repeat ALL↔ONE, resume at 0:00 paused, shuffle reset on new playlist |
| S8 | `PlaybackStateRepository` takes a `CoroutineScope`/`CoroutineDispatcher` | debounce (≤ 1 write/s) verified with `runTest` virtual time |
| S9 | `CoverArtStore` takes a `decode: (InputStream) -> Bitmap?` lambda (default `BitmapFactory`) | downscale/JPEG/old-file-deletion tests without a real photo picker |
| S10 | `AppContainer` is an interface (or has a `@VisibleForTesting` swappable instance) and `Mp3BoxApp.container` is replaceable | `androidTest` can run against an in-memory DB and fake player instead of the real one |
| S11 | Notification-channel and battery-prompt state behind `SettingsStore` keys (`notif_perm_asked`, `battery_opt_asked`) | "prompt at most once" is assertable |
| S12 | Tile colour derivation pure: `tileColors(seed: String): Pair<Color, Color>` shared by Compose and `TileArtwork` | determinism across restarts, shared by the notification bitmap |

Do **not** add `isReturnDefaultValues = true` to dodge S3/S5. A test that silently gets `null` from
`ContentResolver` proves nothing.

---

## 6. Test catalogue — L1/L2, JVM (`./gradlew :app:testDebugUnitTest`)

Expected values are written out so the tests are not "whatever the code does" tests.

### 6.1 Title derivation (spec §4.2)

| Input | Expected |
|---|---|
| `01_My_Song_final.mp3` | `01 My Song final` |
| `UPPER.MP3` | `UPPER` |
| `two__underscores.mp3` | `two underscores` |
| `Track   name.mp3` | `Track name` |
| ` spaced  name .mp3` | `spaced name` |
| `a.b.c.mp3` | `a.b.c` |
| `song` (no extension) | `song` |
| `.mp3` | `.mp3` (fallback) |
| `_.mp3` | `_.mp3` (all-underscore → blank → fallback) |
| `Café_déjà_vu.mp3` | `Café déjà vu` |
| `01.mp3` | `01` (no title-casing, no artist split) |
| `Track.m4a` | `Track` (extension handling is format-agnostic) |
| `Test.m4a` | `Test` |
| `Test2.m4a` | `Test` (trailing number dropped) |
| `Test3Test.m4a` | `Test3Test` (digits are not trailing) |
| `7_Years.m4a` | `7 Years` (leading number kept) |
| `7_years2.m4a` | `7 years` |
| `1234.mp3` | `1234` (all-digit name falls back to the name) |

Also: never `titleCase`, never split `Artist - Title`, never strip leading numbers; only a run of
digits at the very end of the name is dropped.

### 6.2 Duration formatting

| Input | Expected |
|---|---|
| player `0` | `--:--` |
| player `42_000` | `0:42` |
| player `195_000` | `3:15` |
| player `3_725_000` | `1:02:05` |
| playlist total `2_520_000` (42 min) | `42 min` |
| playlist total `3_725_000` | `1 h 02 min` |
| playlist total `3_600_000` (exactly 60 min) | `60 min` (spec says *above* 60 min uses h/min) |

### 6.3 Data layer / scan (spec §5.2)

| ID | Test | Pass criterion |
|---|---|---|
| U-SCAN-1 | New listing with 3 files into an empty DB | 3 rows, `isMissing = false`, durations from `DurationReader`, titles derived |
| U-SCAN-2 | Same listing scanned twice | second scan: `added = 0`, `updated = 0`, `missing = 0`; no duplicate rows (unique URI) |
| U-SCAN-3 | File renamed (URI changes, same size) | old row deleted when no playlist references it, new row inserted, playlist tables untouched; with a playlist entry the old row stays `isMissing = true` so the playlist keeps its stub |
| U-SCAN-4 | File renamed back to its original name | original row's `isMissing` cleared; the interim row is gone; no duplicate rows |
| U-SCAN-5 | Listing becomes empty | unreferenced rows deleted; playlist-referenced rows `isMissing = true`; nothing missing remains in the library; `markAllMissing()` branch (empty `IN ()` guard) |
| U-SCAN-6 | Size/mtime changed | `durationMs` re-read, `fileName`/`title` updated, `lastSeenAt` bumped |
| U-SCAN-7 | Size/mtime unchanged and duration already non-zero | duration **not** re-read (assert fake reader call count) |
| U-SCAN-7b | Size/mtime unchanged but the stored title differs from `titleFromFileName(fileName)` (rule change after an app update) | title rewritten to the derived value, duration **still** not re-read |
| U-SCAN-8 | Durations unknown (fake returns 0) | rows still inserted, `durationMs = 0`, scan succeeds |
| U-SCAN-9 | Playlist tables during any scan | item count/positions unchanged |
| U-SCAN-10 | 500-file listing | completes, no crash, all rows present (timing logged, not asserted) |
| U-SCAN-11 | Missing file not in any playlist | row deleted; `ScanResult.missing = 1` on that scan only, next scan reports `0` |
| U-SCAN-12 | Missing file still in a playlist | row survives `isMissing = true`, joined row still yields title + missing flag; second scan reports `missing = 0` |

### 6.4 Playlists / Room (spec §7)

| ID | Test | Pass criterion |
|---|---|---|
| U-PL-1 | Add same track twice in one batch | second insert ignored (`-1`), `skipped = 1`, one row |
| U-PL-2 | Add tracks already present, mixed with new | new ones inserted at dense positions, duplicates reported |
| U-PL-3 | Reorder 5 items | positions become 0..4 in one transaction; order query matches |
| U-PL-4 | Delete playlist | items cascade; available tracks remain, missing tracks left unreferenced are deleted |
| U-PL-5 | Playlist stats | `songCount`, `durationMs` (available only), `missingCount` correct with 2 missing of 5 |
| U-PL-6 | Playlist order on the grid | `updatedAt DESC` (latest edited first) |
| U-PL-7 | Duplicate playlist | name `X (copy)`, same item order, independent rows, cover file copied |
| U-PL-8 | Rename validation | blank/whitespace rejected; trimmed name stored |
| U-PL-9 | "Remove unavailable tracks" | only rows whose track `isMissing` are removed; others keep order; the now-unreferenced missing track rows are deleted |

### 6.5 Playback logic (spec §6)

| ID | Test | Pass criterion |
|---|---|---|
| U-PB-1 | `nextRepeat(REPEAT_MODE_ALL)` | `REPEAT_MODE_ONE` |
| U-PB-2 | `nextRepeat(REPEAT_MODE_ONE)` | `REPEAT_MODE_ALL` |
| U-PB-3 | Any other/repeat-off value | coerced to `REPEAT_MODE_ONE` then `ALL`; never an "off" state |
| U-PB-4 | Playlist queue construction | saved order preserved, missing filtered, `mediaId = trackId`, metadata title only |
| U-PB-5 | Library queue construction | all available tracks A–Z, tapped track at its alphabetical index |
| U-PB-6 | Tapping a missing track | not added to queue; snackbar count reported |
| U-PB-7 | `planRestore` with saved track present | queue rebuilt, index of saved track, position 0, **paused** |
| U-PB-8 | `planRestore` with saved track missing | playlist only, player empty, snapshot cleared, no error |
| U-PB-9 | New playlist start | repeat forced `ALL`, shuffle forced off (spec §6.3/§6.4) |
| U-PB-10 | Playlist becomes empty after removals | pause at end, no crash |
| U-PB-11 | Queue skip guard | consecutive `onPlayerError` cannot loop forever (bounded skip counter) |

### 6.6 UI state without Android UI (ViewModels on fakes)

| ID | Test | Pass criterion |
|---|---|---|
| U-VM-1 | Library search | case-insensitive substring, trims, empty query restores all |
| U-VM-2 | Sort | A–Z case-insensitive natural order, numbered titles before letters |
| U-VM-3 | Library header | `N songs · total time` counts available tracks only |
| U-VM-4 | Missing + folder revoked | library lists no missing rows (playlist stubs still rendered), playback commands become no-ops + snackbar |
| U-VM-5 | Add-songs screen | already-present tracks flagged `Added` and not selectable; count label `Add N songs` |
| U-VM-6 | Snapshot debounce | 6 rapid state changes → 1 DB write per virtual second |

### 6.7 Robolectric (L2, still JVM)

| ID | Test | Pass criterion |
|---|---|---|
| R-1 | Duration reader on fixture MP3 and M4A | MP3 3000 ms ±100, M4A 2000 ms ±100; empty/corrupt → 0 |
| R-2 | SAF listing parse with a fake `ContentResolver`/cursor | only top-level `.mp3`/`.MP3`/`.m4a`/`.M4A`, folders skipped, null MIME skipped |
| R-3 | Folder state | grant present → `Ok`; grant absent → `Revoked`; no URI → `NoFolder`; no DB writes in the last two |
| R-4 | Cover import 1200 px | output ≤ 640 px on the long edge, JPEG, file exists, previous file deleted |
| R-5 | Cover import broken image | throws/returns false, old cover file still on disk |
| R-6 | Persisted grant check | `persistedUriPermissions` empty after process restart simulation → `Revoked`, DB intact |

---

## 7. Test catalogue — L3, API 33 emulator (`./gradlew :app:connectedDebugAndroidTest`)

Prerequisite: AVD `mp3box33` booted, fixtures pushed (§4.2), and the folder granted **once**. The
system picker cannot be reliably driven from Compose tests; pick one of:

1. **UiAutomator** (`uiautomator:2.4.1`) script that opens the picker and taps the test folder —
   works on AOSP DocumentsUI; may not match DokeOS (that is fine, L4 covers it).
2. **One manual grant per AVD** before the suite, tests read
   `contentResolver.persistedUriPermissions` and fail fast with a clear message if it is missing.
3. **Test-only folder grant screen** in a debug build (`BuildConfig.DEBUG`) that lists
   `persistedUriPermissions` and lets the test pick the existing tree — no new permission logic.

Do **not** try to fabricate a persisted URI grant from instrumentation: the grant must come from the
system picker or `adb`. A test that skips when the grant is missing is acceptable; a test that passes
without one is not.

| ID | Test | Pass criterion |
|---|---|---|
| I-1 | Room in-memory database | DAOs compile and run against real SQLite; unique `(playlistId, trackId)` violation ignored, `position` not unique |
| I-2 | Real SAF scan of `/sdcard/Download/MP3BoxTest` | exactly the top-level MP3s and the M4A indexed (8–9), `nested.mp3` absent, `.jpg`/`.txt` absent |
| I-3 | Real `MediaExtractor` durations | fixtures 1–4 and `M4A_Track.m4a` within ±100 ms; empty/corrupt → 0, scan still completes |
| I-4 | Rescan idempotence | second scan reports 0 changes; `adb shell mv` a file → rescan reports 1 missing, the row leaves Songs, and the next scan reports 0 missing |
| I-5 | Missing track is unplayable | tapping the row does not start playback; queue excludes it; snackbar text present |
| I-6 | MediaController against `PlaybackService` | controller connects via `SessionToken`, queue size matches available tracks, current `mediaId` = tapped track |
| I-7 | Shuffle via controller | enabled: current item unchanged, remaining order differs (statistically, with ≥ 5 items); disabled: original order restored, same current item, position continuity within 250 ms |
| I-8 | Repeat via controller | cycles ALL → ONE → ALL only; `player.repeatMode` matches |
| I-9 | Resume snapshot | kill service, reconnect controller → queue restored, index of saved track, position 0, `playWhenReady = false`, saved shuffle/repeat re-applied |
| I-10 | Playlist delete while playing | playback stops, mini-player state clears, no crash |
| I-11 | Reorder persists | drag/reorder through repository → relaunch activity → order identical |
| I-12 | Folder revoked | remove the persisted grant (`adb shell cmd appops`/uninstall-free reset or fake) → banner shown, playlists intact, play shows snackbar |
| I-13 | Notification permission denied | `adb shell pm revoke com.local.mp3box android.permission.POST_NOTIFICATIONS` → playback still works, hint shown once |
| I-14 | Notification channel | `dumpsys notification --noredact` contains the media notification with title and artwork size > 0 |

Emulator stability settings before the suite:

```bash
ADB=$ANDROID_HOME/platform-tools/adb
$ADB shell settings put global window_animation_scale 0
$ADB shell settings put global transition_animation_scale 0
$ADB shell settings put global animator_duration_scale 0
$ADB shell pm grant com.local.mp3box android.permission.POST_NOTIFICATIONS   # when the test needs it
```

### 7.1 Compose UI tests

| ID | Test | Pass criterion |
|---|---|---|
| C-1 | First run, no folder | onboarding copy + `Choose music folder` button visible; transport absent |
| C-2 | Empty folder | `No MP3 or M4A files in this folder` and rescan available |
| C-3 | Search no hits | `No songs match 'query'` |
| C-4 | Playlist missing stub | 38 % alpha, inline `File missing`, `assertHasNoClickAction`; the same track is absent from the library list |
| C-5 | Mini-player | hidden with no track; after play: title, `Playing`, progress ≥ 0, tap opens Now Playing |
| C-6 | Slider drag | position label updates locally; `seekTo` called only on `onValueChangeFinished` (fake player repo records calls) |
| C-7 | Shuffle/repeat semantics | content descriptions `Shuffle on/off`, `Repeat all/one`; repeat-one shows `1` badge |
| C-8 | Reorder blocked while shuffled | overflow/edit mode shows `Turn off shuffle to reorder` |
| C-9 | Playlist detail missing header | `N of M tracks available` and `Remove unavailable tracks` action |
| C-10 | Add-songs count | selecting 3 shows `Add 3 songs`; already-present rows disabled with `Added` |

---

## 8. Traceability to the spec's acceptance criteria (§12)

| Spec §12 item | Covered by |
|---|---|
| 1. `assembleDebug` + APK exists | L0 |
| 2. `aapt2` package/minSdk/launcher | L0 |
| 3. zero compile errors, lint documented | L0 (`lintDebug` report) |
| 4a. title derivation | U 6.1 |
| 4b. scan drops lost files from the library but keeps playlist stubs | U-SCAN-3/5/9/11/12 |
| 4c. shuffle off restores order, keeps track | I-7 (+ I-8) |
| 4d. repeat ALL → ONE → ALL | U-PB-1/2/3, I-8 |
| 4e. duplicate insert blocked by unique index | U-PL-1, I-1 |
| 4f. state restores at 0:00 paused | U-PB-7/8, I-9 |
| 5. manifest matches §10 | L0 (`aapt2 dump permissions`, `xmltree`) |
| 6. nothing written outside the project | manual check of `~/.gradle`, `~/android-sdk` |
| 7. DokeOS device checks | L4 (§9) |
| 8. no API 34+ reliance | L3 on API 33, plus L0 `minSdk`/API scan (`apkanalyzer`, lint `NewApi`) |
| 9. whitelist instructions reachable | C/L4 |

---

## 9. L4 — DokeOS 4.0 checklist (manual, cannot be automated here)

Run in this order; each line needs an observable result, not "looks fine". `ADB` = platform-tools path.

| # | Check | How to observe |
|---|---|---|
| D-1 | Installs and launches | `adb install -r app-debug.apk`; `adb shell am start -n com.local.mp3box/.MainActivity`; no `AndroidRuntime` in logcat |
| D-2 | Edge-to-edge, both nav modes | switch gesture ↔ 3-button; mini-player and transport clear of the nav bar, no content under status bar |
| D-3 | Folder picker + grant survives reboot | pick `/sdcard/Download/MP3BoxTest`; reboot; relaunch → library still populated, no banner |
| D-4 | Vendor picker variant | if the DokeOS picker returns an unusable tree, the fallback opens and the app does not wipe data |
| D-5 | System Manager cleanup | run "cleanup"; relaunch → grant still valid, covers still present |
| D-6 | Scan correctness | exactly the top-level MP3s and M4A; subfolder absent; `cover.jpg`/`notes.txt` absent |
| D-7 | Audio output | 3 s tone audible, volume keys work, headphone jack |
| D-8 | Unplug | play, unplug headphones → pauses; **does not** auto-resume on replug |
| D-9 | Audio focus | notification sound → duck (not pause); incoming call → pause, resumes after call |
| D-10 | Media card | shade + lock screen show title + generated artwork, prev/play/next work |
| D-11 | Bluetooth/AVRCP | pair, play/pause/next from headset; title on car/headset display |
| D-12 | Lockscreen while playing | controls present, artwork not blank |
| D-13 | **30-min screen-off** (the one that matters) | play repeat-all, screen off, wait; `adb shell dumpsys activity services com.local.mp3box` still lists the FGS; track advanced; notification present |
| D-14 | Recents swipe | swipe away → playback continues, service alive; pause first → swipe → service stops |
| D-15 | Battery exemption | prompt appears once; `adb shell dumpsys deviceidle whitelist \| grep mp3box` lists it after "Don't optimise"; denying does not break foreground playback |
| D-16 | Whitelist card | Library overflow → `Background playback` lists the DokeOS paths verbatim |
| D-17 | Playlist loop / repeat-one | last track wraps to first; repeat-one restarts the same track; manual next still moves |
| D-18 | Shuffle restore | toggle on/off mid-track: current song keeps playing and position; playlist saved order unchanged after restart |
| D-19 | Drag reorder | lift, auto-scroll, drop; order survives restart; blocked while shuffle is on |
| D-20 | Covers | pick from DokeOS gallery, downscaled, survives restart and a forced stop |
| D-21 | Missing file | rename via `adb shell mv`; rescan → once `+1 missing`, row gone from Songs, playlist shows `File missing` stub; play skips it with snackbar; removing the stub (or "Remove unavailable tracks") deletes the entry and the row |
| D-22 | Folder lost | revoke/delete folder → banner, playlists intact, `Fix` restores playback |
| D-23 | Resume | kill from Recents after pausing; relaunch → saved track shown paused at 0:00 |
| D-24 | Notification permission denied | revoke, play → audio works, only the notification is missing, hint shown once |
| D-25 | API 33 only | `adb shell getprop ro.build.version.sdk` = 33; no missing-method crashes (`NoSuchMethodError`) in logcat |

Useful assertions:

```bash
$ADB shell getprop ro.build.version.sdk                 # 33
$ADB shell dumpsys media_session | grep -A20 com.local.mp3box
$ADB shell dumpsys notification --noredact | grep -i mp3box
$ADB shell dumpsys activity services com.local.mp3box | head -40
$ADB shell dumpsys audio | grep -i "player"            # active audio stream
$ADB logcat -d -s AndroidRuntime:E MP3Box:* MediaSessionService:* ExoPlayerImpl:*
```

---

## 10. Commands cheat sheet

```bash
export JAVA_HOME=$HOME/.local/jdk
export ANDROID_HOME=$HOME/android-sdk
ADB=$ANDROID_HOME/platform-tools/adb

# L0 — build + inspect
./gradlew --no-daemon :app:assembleDebug
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump xmltree app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml
./gradlew --no-daemon :app:lintDebug            # read app/build/reports/lint-results-debug.html

# L1/L2 — JVM
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.local.mp3box.util.DisplayNameTest'
# report: app/build/reports/tests/testDebugUnitTest/index.html

# L3 — emulator (headless; RAM is tight)
$ANDROID_HOME/emulator/emulator -avd mp3box33 -no-window -no-snapshot -no-boot-anim \
  -gpu swiftshader_indirect -no-audio -memory 2048 &
$ADB wait-for-device
./gradlew --no-daemon :app:connectedDebugAndroidTest
# report: app/build/reports/androidTests/connected/…/index.html

# single instrumented class
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.local.mp3box.ScanInstrumentedTest

# screenshots for visual review
$ADB exec-out screencap -p > /tmp/mp3box-screen.png

# stop everything afterwards
$ADB emu kill
./gradlew --stop
```

Suggested layout:

```
app/src/test/java/com/local/mp3box/util/DisplayNameTest.kt
app/src/test/java/com/local/mp3box/util/DurationTest.kt
app/src/test/java/com/local/mp3box/data/ScanDiffTest.kt
app/src/test/java/com/local/mp3box/data/PlaylistLogicTest.kt
app/src/test/java/com/local/mp3box/playback/PlaybackPlannerTest.kt
app/src/test/java/com/local/mp3box/playback/QueueFactoryTest.kt
app/src/test/java/com/local/mp3box/robolectric/…
app/src/androidTest/java/com/local/mp3box/db/RoomInstrumentedTest.kt
app/src/androidTest/java/com/local/mp3box/data/SafScanInstrumentedTest.kt
app/src/androidTest/java/com/local/mp3box/playback/MediaSessionInstrumentedTest.kt
app/src/androidTest/java/com/local/mp3box/ui/LibraryUiTest.kt
tools/gen-test-media.sh
tools/run-checks.sh        # L0+L1+L2, non-zero exit on failure; L3 only when an AVD is up
```

`tools/run-checks.sh` should `set -euo pipefail` and echo each command's exit status; do not pipe
Gradle output through `head`/`tail` without `pipefail`, or a failure will look like a pass.

---

## 11. What still cannot be tested, even with all of the above

1. **Audio fidelity / actual sound** — emulator runs `-no-audio`; the phone check is human.
2. **Bluetooth/AVRCP, headset buttons, car head units** — needs real hardware.
3. **DokeOS SystemUI rendering** (media card layout, lockscreen style) — vendor code, phone only.
4. **DokeOS battery manager and DuraSpeed** — not emulatable; 30-minute real-time test.
5. **Vendor picker UI** — UiAutomator matches AOSP, not DokeOS's, so D-3/D-4/D-20 are manual.
6. **Doze/standby over hours** — 30 minutes is a proxy, not proof.
7. **Photographic fidelity of the dark theme** — visual review.
8. **Play Store policy** for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — irrelevant while sideloaded.
9. **500-file scan performance on the target device** — proxy only via the emulator; measure on the phone
   if it feels slow, and report a number rather than "fast".

---

## 12. Decisions needed before implementing tests

1. **Track A or Track B?** (Section 0.) Track B contradicts spec §13/§14; confirm the spec should be
   amended rather than silently violated.
2. **Are test fixtures allowed in the repo?** They are generated by `tools/gen-test-media.sh`; no
   binaries need committing. Confirm `tools/` may exist (the spec says app-only).
3. **Is the phone available over `adb` during the build?** If yes, L4 can be run by the agent; if no,
   it stays a handover checklist.
4. **JUnit 4** (Android default) rather than JUnit 5 — acceptable? JUnit 5 on Android instrumented tests
   is possible but adds a plugin and friction for no gain here.
5. **One extra dependency for UI tests?** `ui-test-junit4` is debug-only in the test APK and does not
   ship in the release APK; confirm that is acceptable given the "small app" goal.

### 12.1 Spec/plan conflicts — resolved 2026-11-06

All six were settled in the source documents; spec and plan now agree.

| # | Conflict | Resolution (as written in the docs) |
|---|---|---|
| 1 | Playlist grid order | `playlists.updatedAt` added; every mutation bumps it in the same transaction; grid query is `ORDER BY p.updatedAt DESC, p.id DESC` (spec §7.3, plan §5.1/§5.2). U-PL-6 stands. |
| 2 | Shuffle/repeat persistence | Both persisted in `playback_state` (`shuffleEnabled`, `repeatMode`) and re-applied on restore. `savedQueue` was **dropped**: Media3 keeps the unshuffled queue and restores order itself on toggle-off (spec §4.1/§6.3, plan §7.4/§7.5). |
| 3 | Natural sort | New pure `util/NaturalOrder.kt`, used by the Library list **and** the library queue; SQL is only a stable base order (spec §8.3, plan §3/§5.2). U-VM-2 stands. |
| 4 | `positionMs` | Column kept and written with the debounced snapshot, never restored in v1 (resume is always 0:00). The every-5-s write was removed from spec §6.6. |
| 5 | Field names | Plan's names won: `uri`, `title`, `fileName`, `lastSeenAt`; spec §4.1/§4.2/§5.2/§8.3 updated, and plan §6.4 now actually maintains `lastSeenAt`. |
| 6 | `playback_state.id` | Fixed at `1` via `PLAYBACK_STATE_ID`; entity default changed from `0`. |

Decisions 1–3 in §12 are still open; until they are answered, only Track A (build inspection +
manual/emulator checklist) is guaranteed to be executable.

---

## 13. Minimum viable "tested" claim

If time is short, the smallest set that still supports the word "tested" honestly:

1. `assembleDebug` + `aapt2 dump badging` + `aapt2 dump permissions` (L0).
2. Unit tests for title derivation, duration formatting, repeat cycle, duplicate insert, scan diff
   (L1 — about 20 tests, < 1 h of work).
3. API 33 emulator: install, first-run, folder grant, play/pause, create playlist, background/return
   (L3, manual or 4 Compose tests).
4. Phone: D-1, D-3, D-6, D-13, D-14, D-15, D-17, D-18, D-21, D-23 (the ten that catch real failures).

Everything beyond that is regression insurance, not a prerequisite for shipping.
