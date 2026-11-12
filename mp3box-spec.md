# MP3Box — Specification

**Status:** Implemented through plan phase 5 (library, playback, playlists, Now Playing); the
remaining plan phases (DokeOS hardening/polish, packaging) are pending
**Date:** 2026-11-06 (updated 2026-11-08)
**Platform:** Android (APK, sideloaded)
**Language / UI:** Kotlin + Jetpack Compose

---

## 1. Summary

MP3Box is an offline, single-folder music player for Android. The user grants access to one
folder, the app lists the supported audio files (`.mp3` and `.m4a`) directly inside it, and the
user builds playlists from that library. Playlists loop by default, support a shuffle toggle that does not destroy saved
order, and play through a proper media session so playback survives a locked screen,
headphones, Bluetooth and the app being backgrounded.

The visual language is "modern music streaming app" — large artwork-style tiles, a mini-player
docked above the navigation bar, a full-screen Now Playing sheet with a scrubber.
Spotify and YouTube Music are referenced only as *level of polish*; no logo, brand colour,
iconography, layout clone, or any other identifiable element from either product is copied.

There is **no** artist/album/tag parsing and **no** cover art extraction in v1. Track titles are
derived from filenames.

---

## 2. Environment constraints (important)

This project is being built in a Linux container, **not** on a developer machine with Android Studio:

| Item | State (re-checked 2026-11-06) |
|---|---|
| Project folder | this spec + the implementation plan; no app sources, no git repo |
| Java | OpenJDK **27.0.1** is the system default; Temurin **21.0.13** installed at `~/.local/jdk` for Gradle |
| Gradle | **9.6.0** unpacked at `~/.local/gradle/gradle-9.6.0`; wrapper not generated yet |
| Kotlin compiler | not standalone — supplied by Gradle/KGP |
| Android SDK | `~/android-sdk` with cmdline-tools, platform-tools 37.0.1, `platforms;android-36` + `android-37.0`, `build-tools;36.0.0` + `37.0.0`; **no emulator or system image yet** |
| Disk | 30 GB free, 91 % used |
| CPU / RAM / KVM | 16 cores, 14 GB RAM (~3.8 GB available), `/dev/kvm` present (rw) |
| Network | available (verified via package/URL fetches) |
| Chrome | installed (could render screenshots of the UI if requested, not required) |

### 2.1 Target platform — DokeOS 4.0 (first-class, not an afterthought)

| Item | Value |
|---|---|
| OS | **DokeOS 4.0 (P4.0), based on Android 13 — API 33** |
| Navigation | gesture navigation by default, 3-button user-selectable |
| Services | DokeOS ships with GMS, but nothing in the app may depend on Play Services |

What this changes in the build:

1. **API 33 is the only runtime that matters in practice.** `minSdk` stays 31, but the app must be
   correct at 33 and must not assume any API 34–36 runtime behaviour. Concretely: `POST_NOTIFICATIONS`
   is a real runtime permission on this phone; the native photo picker is available; the
   `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission is declared for newer devices but not enforced
   here; edge-to-edge is *not* forced by the OS, so the app must apply its own insets (§8.1) or it
   will only look right on newer devices.
2. **DokeOS kills background processes aggressively.** DuraSpeed, System Manager's auto-clean,
   auto-start management and “Intelligent power saving in sleep mode” can stop a foreground media
   service. A media app that neither asks for an exemption nor is whitelisted will stop playing when
   the screen is off. §6.7 makes this explicit; playback surviving 30 minutes of screen-off is an
   acceptance criterion (§12).
3. **DokeOS ships a vendor DocumentsUI and photo picker.** SAF results must be validated, not
   trusted: check `DocumentsContract.isTreeUri`, and if a query against the returned tree fails, fall
   back to the stock picker intent. The folder picker and the cover picker are the two places this
   matters.
4. **SystemUI draws the media notification and the lock-screen card.** The app cannot theme those; it
   only supplies `MediaMetadata`. Artwork must be supplied as `artworkData` (a generated tile bitmap)
   or DokeOS shows an empty grey square.
5. **Do not “fix” playback by adding `READ_MEDIA_AUDIO`.** It is unnecessary for SAF and would trigger
   a permission prompt the design deliberately avoids (§10).
6. **The phone is not attached to the build container.** The APK is produced here and installed by the
   user; the DokeOS-specific checks in §12 are manual on the device unless `adb devices` shows it.

Consequences that shape the plan:

1. The Android SDK (cmdline-tools, `platforms;android-37.0`, `build-tools;37.0.0`) and a Gradle
   distribution must be installed locally to produce the APK.
2. **JDK 27 is newer than what AGP validates.** AGP's documented minimum and default is JDK 17
   (AGP 9.0 – 9.4 release notes all state JDK 17). Plan: install a JDK 21 (or 17) toolchain
   locally for Gradle, and pin `compileOptions` / `kotlin { jvmToolchain(17) }` so the build never
   depends on the container's default JVM. If JDK 27 turns out to work, note it, but do not rely on it.
3. Gradle wrapper must be committed and the build must run with `--no-daemon` initially to keep
   memory use in check (14 GB RAM total, 10 GB available).
4. No physical device is attached, but `/dev/kvm` and the API 33 system images are available, so an
   **API 33 AVD** can be installed to match the target runtime. Verification is therefore
   **`assembleDebug` + `aapt2` inspection + an API 33 emulator smoke test**; DokeOS-specific
   behaviour (battery manager, SystemUI media card, vendor pickers) still cannot be verified here
   and must be reported as such rather than claimed as tested.

---

## 3. Technology stack

Versions confirmed as current stable at time of writing; the implementation should re-check and
pin exactly what resolves, recording the final numbers in the README/commit.

| Concern | Choice | Version / note |
|---|---|---|
| Build system | Gradle + AGP | AGP 9.4.x (max API 37), Gradle 9.6.x — AGP 9 requires Gradle ≥ 9.6 for 9.4 |
| Kotlin | AGP 9 built-in Kotlin 2.4.10 (no standalone KGP; the Compose compiler plugin 2.4.10 is still applied) |
| Compose compiler | `org.jetbrains.kotlin.plugin.compose` | same version as Kotlin (2.4.10) |
| UI | Jetpack Compose + Material 3 | Compose BOM **2026.09.00**; `androidx.compose.material3`, `material-icons-extended` |
| Playback | **AndroidX Media3 ExoPlayer + MediaSession** | **1.11.1** (`media3-exoplayer`, `media3-session`, `media3-common`) |
| Persistence | **Room** (KSP) | tracks, playlists, playlist items, playback state |
| DI | manual (no Hilt) | app is small; a hand-written `AppContainer` keeps the build simple |
| Async | Kotlin Coroutines + Flow | single source of truth = Room `Flow`s collected as Compose state |
| Settings/prefs | DataStore Preferences (or SharedPreferences) | folder URI, theme accent, first-run flag |
| compileSdk / targetSdk | 37 / 36 | The 2026 AndroidX/Compose artefacts require compiling against SDK 37; `targetSdk` stays 36. The target platform runs it on API 33, so no API 34+ runtime behaviour may be assumed |
| **minSdk** | **31 (Android 12)** | per user decision; the only target phone is API 33, so 31 is a floor, not a tested configuration |
| Languages | English only | hard-coded strings in `res/values/strings.xml` anyway so localization stays possible |

Deliberate non-choices: no Hilt, no Retrofit/network layer, no tag-reading library
(jaudiotagger etc.), no MediaStore indexing, no ExoPlayer-extension modules, no
Cast, no equalizer, no widgets.

---

## 4. Domain model

### 4.1 Entities (Room)

**`Track`**
- `id: Long` (PK, autoincrement)
- `uri: String` (SAF `content://` URI — **unique index**; this is the identity key)
- `title: String` — derived title (see 4.2); stored `COLLATE NOCASE`
- `fileName: String` — original file name incl. extension
- `sizeBytes: Long`
- `lastModified: Long` (from `DocumentsContract` metadata; 0 if unavailable)
- `durationMs: Long` — read lazily via Media3/`MediaMetadataRetriever` during indexing, 0 while unknown
- `isMissing: Boolean` — set when a scan can no longer find the document
- `lastSeenAt: Long` — last scan that found it (bookkeeping; no "date added" in v1)

**`Playlist`**
- `id: Long` (PK)
- `name: String` (not blank, trimmed, duplicates allowed but discouraged by UI copy)
- `coverPath: String?` — absolute path to the app-private copy under `filesDir/covers/`
- `createdAt: Long`, `updatedAt: Long` — `updatedAt` is bumped by every mutation (add/remove/
  reorder/rename/cover) and is the playlist grid's sort key (7.3)
- derived (query, not stored): `trackCount`, `totalDurationMs`

**`PlaylistItem`**
- `id: Long` (PK)
- `playlistId: Long` (FK → Playlist, `ON DELETE CASCADE`, indexed)
- `trackId: Long` (FK → Track, indexed)
- `position: Int` — dense 0..n-1 per playlist, rewritten on reorder
- unique index on `(playlistId, trackId)` — enforces "no duplicates in a playlist"

**`PlaybackState`** (single row, fixed `id = 1`, no other rows ever written)
- `playlistId: Long?`, `trackId: Long?`, `positionMs: Long` (written, not restored in v1 — see 6.6),
  `shuffleEnabled: Boolean`, `repeatMode: Int` (ALL / ONE), `shuffleOrder: String?` (comma-separated
  track ids in shuffled play order, see 6.3), `updatedAt: Long`

### 4.2 Title derivation rules (explicit, testable)

`title` is computed from the file name:

1. Strip the extension (`.mp3` or `.m4a`, case-insensitive).
2. Ignore a number at the very end of the name — the usual duplicate suffix: remove the trailing
   run of digits. `Test.m4a` and `Test2.m4a` both show as `Test`; `Test3Test.m4a` keeps its digits
   because they are not at the end.
3. Replace every `_` with a single space.
4. Collapse runs of whitespace to one space and `trim()`.
5. If the result is blank after stripping (a file named only with digits, or no base name at all),
   fall back to the name without its extension, and to the raw file name if that is blank too.
6. Do **not** try to split "Artist - Title"; do not strip **leading** numbers; do not title-case.
   A file `01_My_Song_final.mp3` shows as `01 My Song final`, and `7_years2.m4a` shows as
   `7 years`.

Tag reading, `MediaMetadataRetriever` album/artist extraction and embedded artwork are explicitly
out of scope. Album art is therefore always a generated placeholder (see 7.4).

---

## 5. Folder access, indexing and re-scanning

### 5.1 Granting access
- First launch (or when no persisted grant exists) shows an **empty state screen**: explanatory
  copy plus a `Choose music folder` button.
- Button launches `ActivityResultContracts.OpenDocumentTree` (SAF). On success:
  - `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`
  - persist the URI string in DataStore
  - run a full index
- Only `.mp3` and `.m4a` files are indexed (case-insensitive extension check on the display name).
  **M4A** means AAC audio in an MP4 container; ALAC/Lossless M4A is not playable (see §13).
- **Top level only**: subfolders inside the chosen folder are ignored entirely (no recursion).
  The folder's own file listing is enumerated via `DocumentsContract.buildChildDocumentsUriUsingTree`
  and paginated by the cursor (no `DocumentFile.listFiles()` eager walk, to keep indexing fast
  on large folders).
- Unsupported files (`.flac`, `.wav`, `.ogg`, `.jpg`, `.txt`, etc.) are not listed; the spec does not
  attempt to explain them in the UI beyond a one-line hint in settings/empty state that only MP3 and
  M4A are supported.

### 5.2 Scan algorithm (idempotent, cheap)
1. Read all direct children of the tree URI, build a set of `uri → metadata`.
2. Upsert every found document (insert new, update `fileName`, `sizeBytes`, `lastModified` and
   re-derive `title` if the name changed).
3. Mark every previously-indexed track **not** in the set as `isMissing = true`.
4. Delete the missing rows **no playlist points at**. A row a playlist entry still references
   survives to render that playlist's `File missing` stub; the library never lists missing rows.
5. Clear `isMissing` for tracks that reappear.
6. Read `durationMs` only when it is still `0` **and** the file is reachable; failures leave it 0
   and the UI renders `--:--`.
7. Never touch playlist tables during a scan.

### 5.3 When scanning happens
- **Automatically on app launch** (cold start, Library tab visible) — list-only, fast.
- **Pull-to-refresh** on the Library screen triggers an explicit rescan with a spinner. Playlist
  screens have no pull-to-refresh; they show the same scan results.
- After returning from the folder picker / re-grant flow.
- No background workers, no `ContentObserver`, no periodic job.

### 5.4 Folder lost / permission revoked
Detect by (a) persisted URI absent, (b) `persistedUriPermissions` empty, or (c) listing throws
`SecurityException` / returns null.

Response (user's choice — "banner + re-pick, cache kept"):
- Do **not** wipe the database.
- Show a persistent, dismissible-looking-but-not-dismissible **banner** at the top of the affected
  screens (and on Now Playing): "Music folder is not available — pick it again" + `Fix` button.
- Library/playlist lists stay visible; tracks not currently readable are shown as unavailable and
  will not play; pressing play shows a snackbar "Folder not available — re-select it to play".
- Tapping `Fix` reopens the picker; success re-enables playback and triggers a scan.

### 5.5 Missing files (deleted / renamed / SD card removed)
User's choice: **gone from the library, a stub in playlists**.
- The scan that can no longer find a file drops it from the library: `isMissing = true` rows are
  never listed, and a row no playlist references is deleted from the database.
- A track that is still in a playlist keeps its row, so the playlist shows a stub with the derived
  title and an inline `File missing` label (≈38 % content alpha), not tappable for playback.
- That scan reports the loss once (`+N missing`); a track that was already missing is not counted
  again, so later scans do not repeat the same notice.
- Playback **skips** unavailable tracks automatically (both when the user taps a song in the
  middle of a playlist and when the queue advances onto one) and shows a snackbar:
  "Skipped N unavailable track(s)".
- Playlist header shows `N of M tracks available` when any are missing, plus a small
  "Remove unavailable tracks" action. It deletes those entries, and with them the last reference
  to each missing track, so the row goes too. Removing a stub individually does the same: it is
  gone for good.
- Because a referenced row survives, re-mounting an SD card restores the playlist entry after a
  rescan; a track whose row was already deleted is simply indexed again as a new one.

---

## 6. Playback engine

### 6.1 Architecture
- **`PlaybackService : MediaSessionService`** owns the single `ExoPlayer` instance and a
  `MediaSession`. Manifest registers it with the `androidx.media3.session.MediaSessionService`
  intent filter, `foregroundServiceType="mediaPlayback"`, and `android:exported="true"`.
- UI talks to the service via a **`MediaController`** (built with `SessionToken`), wrapped in a
  repository that exposes the player state as Compose-observable state:
  `nowPlaying: Track?`, `isPlaying`, `positionMs`, `durationMs`, `shuffle`, `repeatMode`.
- Player state is polled via a ~250–500 ms ticker only while the UI is visible (Compose
  `LaunchedEffect` driving a `withFrameMillis`/delay loop) — no permanent 60 fps global ticker.
- Foreground service + `MediaSession` gives: notification with artwork placeholder, play/pause,
  prev/next, seek, lockscreen and Bluetooth/AVRCP controls, headset button play/pause,
  seek-forward/back.

### 6.2 Queue construction
- Playing a playlist builds a `MediaItem` list from the playlist's ordered, *available* tracks,
  starting at the tapped track. Missing tracks are excluded from the player queue but remain visible
  in the playlist.
- Tapping a single library song plays **the library list as the queue** (all available tracks,
  in the same natural A–Z order as the Library screen, starting at the tapped one) so "next"
  always does something. This is stated explicitly because it is easy to get wrong.
- `MediaItem.mediaId` = `Track.id`; `MediaMetadata` filled with the derived title only (no artist;
  artist field deliberately omitted rather than showing "Unknown artist").

### 6.3 Shuffle semantics (user's choice: "shuffle queue, restore order when off")
- Toggling **shuffle ON**: `player.shuffleModeEnabled = true`. Media3 keeps the *currently playing*
  track at the head, shuffles the remainder, and retains the unshuffled queue internally.
- Toggling **shuffle OFF**: `player.shuffleModeEnabled = false`. Media3 restores the real order and
  playback continues from the same track at the same position — no restart, no skip.
- The playlist's stored song order is **never** mutated by shuffle. Reordering by drag-and-drop is
  the only thing that changes saved order.
- Shuffle resets (to OFF) when a different playlist is started; the toggle state is written to
  `PlaybackState.shuffleEnabled` and re-applied when the saved queue is restored (6.6).
- The shuffled play order is saved too (`PlaybackState.shuffleOrder`, track ids), so restarting the
  app resumes the same order instead of shuffling again. Only a restart replays a saved order:
  switching shuffle OFF and back ON inside a session deals a **new** order.

### 6.4 Repeat / loop
- Two states only, cycled by one button: **`REPEAT_ALL`** (default for every playlist start) and
  **`REPEAT_ONE`**.
- `REPEAT_ALL` ⇒ `Player.REPEAT_MODE_ALL`: endless loop through the playlist, wraparound from last
  to first.
- `REPEAT_ONE` ⇒ `Player.REPEAT_MODE_ONE`: current track restarts forever; next/prev still move
  within the playlist when pressed manually (explicit button press overrides repeat-one for that
  transition, then repeat-one resumes).
- There is **no "off"/stop-at-end state** — playlists always loop. Icon: repeat-all arrow loop,
  repeat-one turns the same icon into a "1".
- Long-press nothing / no menus needed: single tap cycles.
- Playing a playlist for the first time always starts in `REPEAT_ALL` regardless of previous mode.
- Persisted in `PlaybackState.repeatMode`, but reset to `REPEAT_ALL` on a fresh playlist start.

### 6.5 Audio events (user's choice: "standard")
- **Becoming noisy** (`ACTION_AUDIO_BECOMING_NOISY`): pause. Do not auto-resume on reconnect.
- **Audio focus**: request focus on play; on loss → pause; on transient loss (notification, nav
  prompt) → duck (`setVolume(0.2f)`) and restore on gain; on `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`
  duck rather than pause. On call interruptions, pause and auto-resume when focus returns
  (`PlaybackException` / focus gain with `resumeWhenFocusGained` semantics), matching the user's
  "resume after call".
- **Headset button**: single click = play/pause (Media3 default), double click / next handled by
  Media3's default `MediaSession` command mapping — no custom handling required in v1.
- **Pause on task removal?** No: playback continues when the app is swiped away (foreground
  service), and the notification remains. Explicit stop only via the notification's stop affordance
  or by clearing it.

### 6.6 Resume behaviour (user's choice)
- Persist `playlistId`, `trackId`, `shuffleEnabled` and `repeatMode` on every state change
  (debounced ~1 s) and on pause/background; `positionMs` travels in the same write.
- On app reopen: restore the playlist and current track into the player **queued but paused at
  0:00**, and show the mini-player with the restored track. Nothing auto-plays; the track always
  starts from the beginning when the user presses play.
- If the persisted track is missing/unavailable, restore the playlist only and leave the player
  empty (no crash, no snackbar spam).
- `positionMs` is stored but **not** restored in v1: resume always starts at 0:00. It is kept for a
  future "resume where you left off" setting.
- The restored queue keeps its saved order; shuffle and repeat are re-applied from `PlaybackState`,
  and a shuffled queue is restored in its saved shuffled order rather than being re-randomised.
  A fresh playlist start still resets shuffle OFF and repeat ALL (6.3, 6.4).

### 6.7 Background survival on DokeOS 4.0

DokeOS is one of the more aggressive OEM skins: it can freeze or kill an app even while a
`mediaPlayback` foreground service is running, most often after the screen has been off for a while.
The foreground service is necessary but not sufficient. v1 therefore does all of the following:

- **Battery-optimisation exemption.** After the folder is granted (not at first launch, so the
  first-run flow stays short), the app asks once, through a single explanatory dialog, to be excluded
  from battery optimisation using `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; the manifest declares
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for this. If the OEM resolves nothing, fall back to
  `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. The answer is remembered in DataStore; the
  Library overflow entry `Background playback` re-opens the same flow.
- **DokeOS whitelist guidance.** A short, dismissible card (also reachable from the Library overflow
  → `Background playback`) names the exact DokeOS paths, because they are not the AOSP ones:
  - Settings → Apps & notifications → MP3Box → Advanced → Battery → Battery optimisation →
    All applications → **Don't optimise**
  - Overview (Recents) → screwdriver/wrench icon → **lock MP3Box**
  - System Manager → App Management → **Auto Start Management** → allow MP3Box
  - System Manager → App Management → **Auto Clean** → protect MP3Box
  - Settings → Smart Assist → **DuraSpeed** → exclude MP3Box
  - System Manager → Settings → disable **Intelligent power saving in sleep mode**
- **No reliance on being restarted.** The service keeps playing when the task is removed (§6.5) and is
  never stopped by the app itself; playback ends only on an explicit stop or when the session is
  cleared.
- **Behaviour when DokeOS still kills it.** On next launch, if the snapshot says playback was active
  and the process died without a stop, show the whitelist card instead of silently resuming nothing.
  The app never claims “playback in background” works until this has been observed on the phone.
- **Test gate.** 30 minutes of playback with the screen off, Wi-Fi off, battery saver on and off, and
  the app swiped from Recents.

---

## 7. Playlist management

### 7.1 Creation (user's choice: "both ways")
- **Flow A — create then pick:** Playlists tab → FAB `+` → dialog for the name (`Create`) → the
  "Add songs" screen opens with the full library as a checklist → `Add N songs` confirm (disabled
  while nothing is selected) → the app returns to the Playlists grid. The new playlist is not
  auto-opened; backing out of the picker leaves it empty on the grid.
- **Flow B — add later:** long-press / overflow on any song row (library, playlist detail, Now
  Playing queue) → `Add to playlist` → bottom sheet listing playlists + `New playlist…`.

### 7.2 Editing (all confirmed)
- **Rename** — via playlist detail overflow → dialog with current name pre-filled; trims, rejects
  blank with inline error.
- **Delete** — overflow → confirmation dialog ("Delete playlist 'X'? Songs stay in your library.").
  Cascades playlist items; available tracks are untouched, while a missing track left without any
  playlist reference is deleted with its stub (5.5). If the playlist is currently playing, playback
  stops and the mini-player clears.
- **Add / remove songs** — `Add songs` screen with multi-select and a live "N selected" count;
  remove via row overflow (`Remove from playlist`) and via an edit mode with multi-select removal.
  Removal of the currently playing track from the queue must not crash: continue with the next
  track (or pause at end if the queue becomes empty).
- **Reorder by drag-and-drop** — drag handle at the trailing edge of each row in the playlist's
  edit mode. Rows are a fixed 56 dp, so the target index is computed from the drag distance; there
  is no long-press-to-lift and no auto-scroll while dragging. Positions are renumbered densely and
  persisted on drop. Reordering is disabled while shuffle is ON (message: "Turn off shuffle to
  reorder") to avoid two competing orders.
- **Custom cover image** — playlist detail / create screen → `Change cover` → system photo picker
  (`PickVisualMedia`, image only) → copy the bytes into app-private storage
  (`filesDir/covers/<playlistId>-<timestamp>.jpg`, downscaled to ≤ 640 px, JPEG q88) → store the
  absolute path → old copy deleted. `Remove cover` restores the generated placeholder. Covers survive the
  original gallery image being deleted. Pick requires no storage permission (photo picker).
  On API 33 the native picker is used; if DokeOS returns a URI that cannot be opened,
  fall back to `ACTION_OPEN_DOCUMENT` with `image/*` and copy the bytes immediately — the vendor URI
  grant is short-lived either way.
- **Duplicate a playlist** — overflow → `Duplicate`; copies name (`X (copy)`) + items + cover
  reference (new file copy), `createdAt = updatedAt = now`.

### 7.3 Guards and stats
- Duplicate songs in one playlist are **prevented** (`(playlistId, trackId)` unique index at the DB
  level; UI shows "Already in this playlist" when this happens through the add flow).
- Playlist row/card shows `N tracks · 42 min` (duration sum of available tracks, formatted
  `1 h 05 min` above 60 min).
- Playlist ordering: most recently updated first (`updatedAt DESC, id DESC`); no manual playlist
  sorting. Every mutation touches `updatedAt`.

### 7.4 Placeholder artwork (no real album art)
Every track row and the Now Playing screen uses a **generated tile**:
- a rounded square with a two-stop vertical gradient,
- colour pair deterministically derived from a hash of the track title (stable across restarts),
- centred monogram: first alphanumeric character of the title (or a music-note icon if blank),
- constraint: keep the palette muted/desaturated so it reads as "placeholder", not as a claimed
  album cover. Playlists use the same generator keyed on the playlist name when no cover is set.

---

## 8. UI / UX specification

### 8.1 Theme
- **Always dark** (user's choice), independent of the system light/dark setting.
- Material 3 (`MaterialTheme` with a hand-built dark `ColorScheme`): near-black background
  (`#0B0B0D`-ish), elevated surface (`#16161A`), on-surface high-contrast text, one accent colour
  used for the active tab, play button, selected rows and the seek bar. Accent choice: a single
  fixed accent (NOT Spotify green, NOT YT Music red) — pick a distinct hue and document it in
  `ui/theme/Color.kt`. Secondary text at 70 % alpha.
- Status bar / nav bar transparent with edge-to-edge (`enableEdgeToEdge`), content drawn under the
  bars with proper insets.
- Typography: Material 3 defaults, slightly tightened tracking for headings; title row truncates
  with ellipsis to one line, track rows never wrap.
- Dynamic color explicitly **not** used (consistent look everywhere on API 31+).
- Motion: standard Material transitions; Now Playing expands bottom-sheet-style from the
  mini-player (shared-element-ish scale/translate), ~300 ms. No custom physics experiments.

### 8.2 Navigation
- `NavHost` with **bottom navigation bar** of two tabs: **Library** (`Songs` icon) and
  **Playlists** (`Queue music` icon). Selected tab = accent tint + filled icon; unselected = muted.
- **Mini-player** docked directly above the navigation bar, present on both tabs and on playlist
  detail: 
  - 64 dp tall, elevated surface, flat (no rounded corners),
  - generated tile (40 dp) + title (1 line),
  - play/pause button and next button at the trailing edge,
  - a 2 dp **progress line** across the very top of the mini-player that reflects position,
  - tapping anywhere else expands into **Now Playing**,
  - swipe-left-to-dismiss is **not** implemented (avoid accidental dismissal; stop via notification),
  - on routes without the tab bar (playlist detail, add songs) the bar pads for the navigation-bar
    inset itself, so it never sits on top of 3-button navigation.
- Routes: `library`, `playlists`, `playlist/{id}`, `playlist/{id}/add-songs`, `now-playing`
  (full-screen destination, not a modal, so back returns to the previous tab).

### 8.3 Library (Songs) screen
- Top app bar: title `Songs`, and a trailing `Rescan` icon button (mirrors pull-to-refresh).
- Below: **search/filter bar** (`OutlinedTextField`-style, magnifier icon, clear button) filtering
  the list live by title substring, case-insensitive. Search filters only the visible list; it does
  not change playback order.
- Ordering used by the list and by the library queue is the same `NaturalOrder` comparator; there is
  no second definition of "alphabetical" anywhere in the app.
- **Sort:** alphabetical A–Z by `title`, case-insensitive, **natural ordering**: digit runs compare
  as numbers (`Track 2` before `Track 10`) and titles starting with digits come before titles
  starting with letters. Implemented in `util/NaturalOrder.kt` (SQLite cannot do numeric-aware
  ordering on its own). Only one sort option in v1; no sort menu.
- List rows: 56 dp height, generated tile (40 dp), title (1 line), duration at trailing
  (`--:--` when unknown). The library lists playable files only; a missing file appears solely as
  a playlist stub (5.5). No artist/album line, since
  there is no such metadata — the second line shows the **file name** in muted small type only if
  the title differs from it; otherwise nothing.
- Tap = play with library queue (6.2). Long-press = context sheet (`Play`, `Add to playlist`).
- Header line above the list: `N songs · total time` (available only).
- Pull-to-refresh (`PullToRefreshBox`) triggers rescan; brief "Library updated" snackbar with
  counts (`+3 new, 1 missing`). `missing` covers files lost by that scan only, so a deleted file
  is announced once and the following scan stays quiet.
- **Empty states:** no folder yet → button-led onboarding; folder empty → "No MP3 or M4A files in
  this folder" (only MP3 and M4A supported); search with no hits → "No songs match 'query'".

### 8.4 Playlists screen
- Top app bar `Playlists`, no sort/search in v1.
- FAB `+` → create flow (7.1).
- Content: **2-column grid** of cover cards (square tile with rounded corners, gradient placeholder
  or custom cover) + playlist name (1–2 lines) + `N tracks · duration`. Fixed, non-resizable grid.
- Long-press a card → `Rename`, `Duplicate`, `Delete` (also reachable from the detail screen's
  overflow).
- Empty state: "No playlists yet" + `New playlist` button.

### 8.5 Playlist detail screen
- Header inside the scrolling list: cover (140 dp, rounded, tap to change), playlist name (2 lines
  max), `N tracks · duration` and `M unavailable` when applicable.
- Action row: big accent **Play** button (plays from the top), **Shuffle** icon button (turns shuffle
  on and starts playing), **Add songs** button, overflow (`Rename`, `Change cover`, `Remove cover`,
  `Duplicate`, `Remove unavailable tracks`, `Delete`).
- Track rows numbered by position (`1`, `2`, …) unless shuffle is on, in which case show the shuffle
  glyph in that slot; the currently playing row is accent-tinted with an animated `Playing` bar.
- Row overflow: `Play`, `Remove from playlist`.
- Edit mode (pencil in the top bar): checkboxes for multi-select with a `Remove N` bar, plus a drag
  handle at the trailing edge of every row.
- Empty playlist: "This playlist is empty" + `Add songs`.

### 8.6 Now Playing screen
Full-screen, top-to-bottom:
1. Top bar: `Collapse` chevron, silent playlist name (tappable → playlist detail), overflow
   (e.g. `Go to playlist`).
2. Large generated tile / cover, square, ~70 % width, rounded ~16 dp, centred, subtle shadow.
3. Title (2 lines max, ellipsis) + micro-label with track index of the queue (`Track 3 of 12`).
4. **Seek bar**: Material 3 `Slider` with position/duration labels (`0:42`, `3:15`); drag updates
   a local value, `onValueChangeFinished` seeks the player (no seek spam while dragging). Tapping
   the bar area also seeks.
5. Transport row, centred: **Shuffle** (accent when on) · **Previous** · **Play/Pause** (64 dp
   filled circle, accent) · **Next** · **Repeat** (repeat-all / repeat-one icon, accent when
   repeat-one is active — repeat-all is the default so it stays muted to signal "normal").
   Previous semantics: if position > 3 s → restart current track, else previous track.
6. Bottom inset for the system navigation bar; no mini-player on this screen.

### 8.7 Accessibility and touch targets
- Every icon button ≥ 48 dp; content descriptions on all icon-only controls (shuffle, repeat, next,
  play/pause, cover, drag handle).
- Dynamic font scaling respected; rows use `maxLines` + ellipsis rather than fixed text heights.
- The target screen is tall and supports both gesture and 3-button navigation. The
  mini-player and the Now Playing transport row must sit above the navigation-bar inset in both
  modes, and nothing may be drawn under the status bar. Both modes are checked once on the phone.
- Colour is never the only signal: shuffle/repeat also announce state via description
  ("Shuffle on"), missing tracks carry the text label.

### 8.8 Colour/asset notes (anti-cloning)
- App icon: adaptive icon, monochrome-friendly, a simple abstract note/box glyph in the accent hue.
- No use of Spotify/YouTube names, logos, their exact green/red, their fonts, their tab icon sets,
  or their "Home / Search / Your Library" structure.

---

## 9. Startup, lifecycle and persistence details

- `MP3BoxApp : Application` builds the manual `AppContainer` (database, settings, scanner, player
  repository).
- First launch order: read folder URI → if absent show onboarding, else start service, restore
  `PlaybackState`, kick off a background (IO-dispatched) scan.
- The scan runs in `Dispatchers.IO`; DB writes are transactional so the UI never observes a
  half-updated library.
- All Compose screens collect `StateFlow` from ViewModels (one per screen: `LibraryViewModel`,
  `PlaylistsViewModel`, `PlaylistDetailViewModel`, `PlayerViewModel`, `AddSongsViewModel`).
- Service binding: `PlayerRepository` builds one `MediaController` at app start (`AppContainer`) and
  keeps it for the process lifetime; the resume snapshot is flushed when the app leaves the
  foreground (`AppRoot` observes `ON_STOP`).

---

## 10. Permissions and manifest

- **No** `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` — SAF persisted URI permission covers folder
  reads. This is intentional and should be documented so nobody "fixes" it later.
- The **photo picker** for playlist covers needs no permission on API 31+.
- Manifest:
  ```xml
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
  <!-- Sideload-only build: lets the app ask once to be excluded from DokeOS battery optimisation.
       This would need a policy review for Play Store distribution; this app is not published. -->
  <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
  <service
      android:name=".playback.PlaybackService"
      android:exported="true"
      android:foregroundServiceType="mediaPlayback">
      <intent-filter>
          <action android:name="androidx.media3.session.MediaSessionService" />
      </intent-filter>
  </service>
  ```
- `POST_NOTIFICATIONS` runtime request on first play only (Android 13+; the target platform is API 33, so
  this prompt is real). Denial must not break playback — the session still works, only the
  notification is hidden; show a one-time hint.
- Battery optimisation is requested once, after the folder grant or on the first play, whichever
  comes first — never before the app has explained itself. On DokeOS the OEM may replace the dialog;
  if `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` resolves to nothing, open
  `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. This is a convenience, not a permission the app
  needs: denial only risks DokeOS killing playback when the screen is off.
- Media3's notification supplies the media card. `MediaMetadata.artworkData` must carry the generated
  tile bitmap (JPEG, ≤ 512 px) because DokeOS's SystemUI renders an empty square otherwise.
- Activity: `singleTask`, `configChanges` not used (Compose handles config), `android:exported="true"`.

---

## 11. Build & APK delivery plan

Goal: produce an installable **debug APK** in this environment and give the user its path.

Steps (in order, all local to the project folder / user cache):

1. Install a Gradle-compatible JDK (21 preferred, 17 acceptable) without touching the system JDK;
   point Gradle at it via `org.gradle.java.home` in `gradle.properties` or `JAVA_HOME` for the
   build command. Never rely on the container's JDK 27.
2. Download Android **cmdline-tools**, install `platform-tools`, `platforms;android-37.0`,
   `build-tools;37.0.0`; set `ANDROID_HOME`/`sdk.dir` in `local.properties` (gitignored).
3. Generate the Gradle wrapper (Gradle 9.6.x) committed to the repo.
4. Project layout:
   ```
   settings.gradle.kts
   build.gradle.kts
   gradle.properties
   gradle/libs.versions.toml
   gradle/wrapper/...
   local.properties            (gitignored)
   app/build.gradle.kts
   app/proguard-rules.pro
   app/src/main/AndroidManifest.xml
   app/src/main/java/com/local/mp3box/...  (ui/, data/, playback/, di/)
   app/src/main/res/...        (values/, drawable/, mipmap-anydpi/)
   ```
5. `./gradlew :app:assembleDebug --no-daemon` → verify
   `app/build/outputs/apk/debug/app-debug.apk` exists, then inspect with `aapt2 dump badging`
   (package `com.local.mp3box`, minSdk 31, targetSdk 36, launcher activity present).
6. Run the API 33 emulator smoke test (install, launch, folder pick, play/pause, playlist CRUD,
   rotate/background) and record what was actually observed. Then report the APK path and size.
   If a device is attached (`adb devices`), also `adb install -r` and run the DokeOS checks in
   §12 item 7; otherwise state plainly which behaviours were not run.
7. Both variants must install. `release` is signed with the machine's own debug keystore (nothing
   committed; the app is sideload-only), so `app-release.apk` and `app-debug.apk` install and update
   each other via `adb install -r` or sideloading. A published build would need a real keystore.

Risks / mitigations:
- JDK 27 default → pin toolchain, install JDK 21 locally.
- Disk at 90 % (30 GB free) → SDK + Gradle caches + API 33 AVD ~8–10 GB; keep an eye on it, clean
  `~/.gradle` only if needed and say so.
- Version drift (AGP/Kotlin/Compose BOM/Media3 move fast) → if a pinned version fails to resolve,
  step to the nearest stable and record the actual resolved version.
- DokeOS kills the media service with the screen off → battery exemption, in-app whitelist card,
  30-minute test gate (§6.7, §12).
- Vendor DocumentsUI returns a tree URI that cannot be queried → validate `isTreeUri`, fall back to
  the stock picker, and never wipe cached data on failure (§5.4).
- The API 33 emulator has no DokeOS battery manager or vendor picker → treat it as a functional
  smoke test only; the phone remains the authority for notification/lockscreen/battery behaviour.

---

## 12. Acceptance criteria

A build is "done" when **all** of the following hold:

1. `./gradlew :app:assembleDebug` completes and `app-debug.apk` exists.
2. `aapt2 dump badging` shows package `com.local.mp3box`, minSdk 31, and a launcher activity.
3. The app compiles with zero errors; lint warnings documented (no blanket suppression to hide real
   problems).
4. Code-level checks of the agreed behaviours:
   - filename → title derivation implements §4.2 exactly,
   - scan marks missing tracks instead of deleting (§5.2),
   - shuffle off restores pre-shuffle order and keeps the current track (§6.3),
   - repeat cycles only ALL → ONE → ALL (§6.4),
   - duplicate insert into a playlist is blocked by the unique index (§7.3),
   - `PlaybackState` persists and restores the track at 0:00, paused (§6.6).
5. Manifest matches §10 (no storage permissions, media service declared correctly).
6. No files created outside the project folder except toolchain/SDK installs under the user's cache.
7. DokeOS 4.0 / API 33 on a device:
   - installs from the APK and launches; dark theme is edge-to-edge with no content under the bars
     in both gesture and 3-button navigation;
   - the folder picker returns a usable tree URI and the grant survives a reboot and a System
     Manager cleanup;
   - the notification and lock-screen media card show the title and the generated artwork;
   - playback runs for 30 minutes with the screen off and the app removed from Recents;
   - app-private playlist covers survive a restart.
8. No API 34–36 behaviour is relied on: the same APK is correct on the API 33 emulator, not only on
   a newer runtime.
9. The DokeOS whitelist instructions are reachable from inside the app, and the battery-exemption
   prompt appears at most once.

Not verifiable from this container unless the phone is attached: actual audio output, SAF picker UX,
notification and lockscreen controls, drag-and-drop feel, dark-visual fidelity, and DokeOS battery
behaviour. The API 33 emulator covers everything except the DokeOS-specific items above; those must
be run on a DokeOS device by the user, or by the agent if `adb devices` shows it.

---

## 13. Out of scope (v1)

- ID3 tag parsing, artist/album/album-art, bitrate display.
- MediaStore scanning, multiple folders, recursive subfolders, watch folders, audio formats other
  than MP3 and M4A (FLAC, WAV, OGG, ALAC-in-M4A, …).
- Gapless playback, crossfade, equalizer, playback speed, sleep timer, skip ±10 s, "recently played",
  favourites, search across playlists, playback history.
- Widgets, Wear OS, Android Auto/CarPlay-style browse trees, Cast, cloud sync, backup/restore export,
  sharing a playlist. Media3's default media session does give basic browsable notification controls;
  car-specific browse trees are not built.
- Localisation beyond English, light theme, dynamic colour, tablet/foldable two-pane layout,
  landscape-specific layouts.
- Instrumented/UI tests, CI workflow, signed release artifact, Play Store publishing.
- Git initialisation (explicitly declined by the user).

---

## 14. Decisions log (from interview)

| Topic | Decision |
|---|---|
| Library source | One folder chosen once via SAF folder picker; persisted read permission |
| Subfolders | **Ignored** — top-level files only |
| Track naming | Filename minus its extension, `_` → space, whitespace collapsed |
| Audio formats | MP3 + M4A (AAC in an MP4 container) indexed and playable; FLAC/WAV/OGG/ALAC ignored; platform AAC decoder, no extra dependency |
| Metadata | No tags, no artist/album, no embedded art |
| Playlist editing | Add/remove, rename, delete, drag-reorder, custom cover (gallery), duplicate |
| Playlist duplicates | Prevented (unique index) |
| Playlist creation | Both: create-then-pick, and "Add to playlist" from any track |
| Playback outside app | Full media session (foreground service, notification, lockscreen, BT) |
| Shuffle | Reorders live queue, restores saved order when turned off, current track preserved |
| Repeat | Cycle repeat-all ↔ repeat-one only (no off) |
| Now Playing | Seek bar, transport, shuffle/repeat |
| Mini-player | Docked above bottom tabs, expands to Now Playing |
| Missing files | Dropped from the library; playlist stub with `File missing`, skipped during playback |
| Folder lost | Banner + re-pick, cached library/playlists preserved |
| Rescan | Auto on launch + pull-to-refresh (+ button) |
| Resume | Restore playlist/track, paused at 0:00 |
| Audio events | Standard: pause on unplug, duck, resume after call |
| Appearance | Always dark, fixed accent, fixed 2-column playlist grid |
| Language | English only |
| minSdk / targetSdk | 31 / 36 |
| App identity | Name "MP3Box", package `com.local.mp3box` |
| Playlist grid order | `updatedAt DESC`; every mutation bumps `updatedAt` in the same transaction |
| Shuffle/repeat state | Persisted in `playback_state`; re-applied on resume, reset on a fresh playlist start |
| Shuffle implementation | Media3 native `shuffleModeEnabled` (no `savedQueue` snapshot) |
| Library sort | Natural A–Z (`util/NaturalOrder.kt`): digit runs numeric, numbers before letters |
| `positionMs` | Stored with the snapshot; **not** restored in v1 (resume always 0:00) |
| Track fields | `uri`, `title`, `fileName`, `lastSeenAt` — aligned with the implementation plan |
| APK production | Built by the agent in this environment (debug + debug-signed release) |
| Target platform | DokeOS 4.0 (Android 13 / API 33) — the only platform in scope |
| Runtime floor | minSdk stays 31, but correctness is judged at API 33; no API 34–36 runtime assumptions |
| Background survival | one-time battery-optimisation exemption + in-app DokeOS whitelist guidance |
| Emulator | API 33 `google_apis` x86_64 AVD for smoke tests (KVM available) |
| Tests / docs | App only — no test suite, no README (comment-level docs only) |
| Git | Not initialised |
