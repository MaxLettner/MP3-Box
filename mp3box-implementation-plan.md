# MP3Box — Implementation Plan

**Companion to:** `mp3box-spec.md` (source of truth for *what* to build)
**This document:** *how* to build it, in what order, with which files, versions and commands
**Target:** a working `app-debug.apk` for a Kotlin + Jetpack Compose MP3/M4A player, built in this container
**Build strategy:** no physical device is attached, but an **API 33 emulator** can be installed here (KVM present) to match the target runtime. "Done" means *compiles, packages, passes static verification and an API 33 smoke test*; DokeOS-specific behaviour stays a phone-side checklist. Runtime behaviour is listed in §10.2 for the user to check on a phone.

---

## 1. Verified toolchain

Every version below was checked against Google Maven / Maven Central on 2026-11-06, not taken from memory.

| Component | Pin | Latest available | Why this pin |
|---|---|---|---|
| Gradle | **9.6.0** | 9.8.2 | AGP 9.4's validated Gradle pairing; newer Gradle buys nothing here |
| Android Gradle Plugin | **9.4.1** | 9.5.1 | Stable, compiles against SDK 37, ships built-in Kotlin support (the standalone Kotlin Android plugin is rejected) |
| JDK (build) | **Temurin 21** | 27 present system-wide | AGP is validated on 17/21; JDK 27 is newer than the tested range |
| Kotlin + Compose compiler plugin | **2.4.10** | 2.4.21 | The version documented with Compose BOM 2026.09.00 |
| Compose BOM | **2026.09.00** | same | Resolves Material 3 **1.4.0**, Foundation/UI matching |
| Media3 (ExoPlayer + Session) | **1.11.1** | 1.12.0-alpha02 | Latest stable |
| Room | **2.8.5** | 2.8.5 | Latest stable |
| KSP | **2.3.13** | 2.3.13 | KSP is versioned independently of Kotlin now; 2.3.13 is the newest |
| Navigation Compose | **2.10.2** | 2.10.2 | Stable |
| Lifecycle | **2.11.0** | 2.12.0-alpha05 | Newest *stable* |
| Activity Compose | **1.13.0** | 1.14.0-alpha03 | Newest stable |
| DataStore Preferences | **1.3.0** | 1.3.0 | Stable |
| Core KTX | **1.19.1** | 1.19.1 | Stable |
| Coroutines | **1.11.0** | 1.11.0 | Stable |
| Coil 3 (cover images) | **3.6.3** | 3.6.3 | Async load of picked cover files without hand-rolling bitmap plumbing |
| Emulator for smoke tests | **android-33;google_apis;x86_64** | 33 rev 17 | Matches the API 33 target runtime; `google_apis` (not `default`) so DocumentsUI and the photo picker behave |
| Target platform | **DokeOS 4.0 / Android 13** | — | The platform this must run on; see §2.6 |
| compileSdk / targetSdk / minSdk | **37 / 36 / 31** | — | 2026 AndroidX requires compileSdk 37; `targetSdk` stays 36 per spec, API 33 is the real runtime, 31 is only a floor |

### Deliberate deviations from the spec's stack table

1. **Coil 3 added.** The spec only mentioned Room, DataStore and Media3. Covers are JPEG files in app storage; Coil gives async decode, memory caching and downsampling for ~10 lines of UI code. Alternative (manual `ImageDecoder` + bitmap cache) costs ~120 lines and risks leaking bitmaps. If the user prefers zero extra dependencies, this is the one to drop.
2. **Shuffle uses ExoPlayer's native shuffle mode** instead of hand-reordering the queue. ExoPlayer keeps the current item playing, shuffles the rest, and restores the original order when shuffle is switched off — exactly the spec's requirement (§playback) — and never touches the database. This removes a whole class of state bugs.
3. **Gradle/AGP pinned one notch behind latest** (9.4.1 / 9.6.0 instead of 9.5.1 / 9.8.2) because that pairing is the one AGP documents; upgrade only if the build misbehaves.

---

## 2. Environment bootstrap (one-time, ~15 min, ~3 GB disk)

The container currently has **JDK 27 only, no Gradle, no Android SDK, no adb**. Nothing else in this plan works until this section is done.

```bash
set -euo pipefail
mkdir -p ~/.local/jdk ~/.local/gradle ~/android-sdk

# 2.1 JDK 21 (local, never touches the system JVM)
curl -L -o /tmp/jdk21.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar -xzf /tmp/jdk21.tar.gz -C ~/.local/jdk --strip-components=1
~/.local/jdk/bin/java -version          # expect: openjdk 21.x

# 2.2 Gradle 9.6.0 (bootstrap binary, then generate the project wrapper)
curl -L -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-9.6.0-bin.zip
unzip -q /tmp/gradle.zip -d ~/.local/gradle

# 2.3 Android command-line tools
curl -L -o /tmp/clt.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q /tmp/clt.zip -d /tmp/clt
mkdir -p ~/android-sdk/cmdline-tools
mv /tmp/clt/cmdline-tools ~/android-sdk/cmdline-tools/latest

# 2.4 SDK packages + licences
export ANDROID_HOME=$HOME/android-sdk
export JAVA_HOME=$HOME/.local/jdk
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
```

Then, inside the project:

```bash
export JAVA_HOME=$HOME/.local/jdk
~/.local/gradle/gradle-9.6.0/bin/gradle wrapper --gradle-version 9.6.0 --distribution-type bin
```

`local.properties` (gitignored, absolute path required):

```properties
sdk.dir=/home/<user>/android-sdk
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx3072m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8
org.gradle.java.home=/home/<user>/.local/jdk
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
```

**Gate 0:** `./gradlew -v` reports Gradle 9.6.0 + JVM 21, and `sdkmanager --list_installed` shows `platforms;android-37.0` and `build-tools;37.0.0`.

> Disk: ~30 GB free. SDK ≈ 1.5 GB, Gradle home ≈ 2 GB after first build, project build dir ≈ 0.5 GB. Keep `build/` out of any future backup.

### 2.5 Emulator for API 33 smoke tests

The target platform is **DokeOS 4.0 / Android 13 (API 33)**. `/dev/kvm` exists
and the API 33 images are available, so an AVD is the cheapest way to exercise runtime paths that
`assembleDebug` cannot:

```bash
export ANDROID_HOME=$HOME/android-sdk
export JAVA_HOME=$HOME/.local/jdk

$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root="$ANDROID_HOME" \
  "emulator" "system-images;android-33;google_apis;x86_64"

$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd \
  -n mp3box33 -k "system-images;android-33;google_apis;x86_64" -d pixel_6

# ~2 GB RAM; -no-window when only logcat/dumpsys matters
$ANDROID_HOME/emulator/emulator -avd mp3box33 -no-snapshot -no-boot-anim \
  -gpu swiftshader_indirect -no-audio -memory 2048 &
$ANDROID_HOME/platform-tools/adb wait-for-device
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `google_apis`, not `default`: the folder picker (DocumentsUI) and photo picker must be the
system ones. The image is ≈ 1.5 GB and the AVD another ≈ 2 GB. The emulator is **not** a substitute
for the phone — DokeOS's battery manager, SystemUI media card and vendor pickers only exist on the
device. It covers: first-run flow, SAF grant, scan, playback state machine, playlist CRUD, insets,
notification permission prompt, process death and resume.

When the phone is attached over USB:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.local.mp3box/.MainActivity
adb logcat -s MP3Box:* AndroidRuntime:E MediaSessionService:* ExoPlayerImpl:*
adb shell dumpsys media_session          # session + metadata + playback state
adb shell dumpsys activity services com.local.mp3box   # is the FGS still alive?
```

### 2.6 Target platform: DokeOS 4.0

Android 13 (API 33).

| DokeOS behaviour | Consequence in this project |
|---|---|
| DuraSpeed, System Manager auto-clean, auto-start management, “Intelligent power saving in sleep mode” | One-time battery-optimisation exemption (§7.6) plus an in-app whitelist card. Playback must survive 30 min screen-off (test 13 in §10.2). |
| Vendor DocumentsUI / photo picker | Validate `DocumentsContract.isTreeUri`; fall back to `ACTION_OPEN_DOCUMENT_TREE` / `ACTION_OPEN_DOCUMENT` when a returned URI cannot be queried. Copy cover bytes immediately. |
| SystemUI draws the media notification and lock-screen card | Supply `MediaMetadata.artworkData` (generated tile JPEG, ≤ 512 px) or DokeOS shows an empty grey square. |
| Gesture nav by default, 3-button optional, tall screen | Insets applied manually (targetSdk 36 does not force edge-to-edge on API 33). Check both nav modes once on the phone. |
| Aggressive memory management (“Atomized Memory”) | No `largeHeap`, no long-lived bitmaps; Coil handles decode; the media service must not hold the Activity. |
| DokeOS “Focus Mode” / “No-play Mode” | Can mute notifications and block apps; document as a user-side cause if the media card disappears. Not something the app overrides. |
| API 33 runtime | `POST_NOTIFICATIONS` is a real prompt; `FOREGROUND_SERVICE_MEDIA_PLAYBACK` is declared but not enforced; no API 34+ behaviour may be relied on. |

---

## 3. Project skeleton

```
mp3box/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}
├── local.properties                         (gitignored)
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── drawable/ic_launcher_foreground.xml
        │   ├── mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml
        │   ├── values/{strings.xml, colors.xml}
        │   └── xml/backup_rules.xml
        └── java/com/local/mp3box/
            ├── Mp3BoxApp.kt                  Application; owns AppContainer
            ├── MainActivity.kt               single activity, edge-to-edge, nav host
            ├── di/AppContainer.kt            manual DI: db, repos, player repo
            ├── data/
            │   ├── db/AppDatabase.kt          Room database, v1, exportSchema=false
            │   ├── db/TrackEntity.kt
            │   ├── db/PlaylistEntity.kt
            │   ├── db/PlaylistItemEntity.kt
            │   ├── db/PlaybackStateEntity.kt
            │   ├── db/TrackDao.kt
            │   ├── db/PlaylistDao.kt
            │   ├── model/Models.kt            domain models + row projections + mappers
            │   ├── MusicFolderRepository.kt   SAF grant, permission check, rescan
            │   ├── MediaScanner.kt            top-level .mp3/.m4a listing + duration extraction
            │   ├── PlaylistRepository.kt      CRUD, reorder, duplicate, add/remove
            │   ├── CoverArtStore.kt           import/downscale/delete cover JPEGs
            │   ├── SettingsStore.kt           DataStore: folder uri, battery/notification prompts
            │   └── PlaybackStateRepository.kt resume snapshot (track, position, playlist)
            ├── playback/
            │   ├── PlaybackService.kt         MediaSessionService + ExoPlayer
            │   ├── PlayerRepository.kt        MediaController lifecycle, StateFlows, commands
            │   └── QueueFactory.kt            playlist rows -> List<MediaItem> (missing filtered)
            ├── ui/
            │   ├── theme/{Color.kt, Type.kt, Theme.kt}
            │   ├── components/TrackTile.kt    deterministic gradient artwork
            │   ├── components/TrackRow.kt
            │   ├── components/MiniPlayer.kt
            │   ├── components/PlaylistCard.kt
            │   ├── components/Banners.kt      folder lost / no folder
            │   ├── components/Sheets.kt       track context sheet, add-to-playlist sheet
            │   ├── library/{LibraryScreen.kt, LibraryViewModel.kt}
            │   ├── playlists/{PlaylistsScreen.kt, PlaylistsViewModel.kt}
            │   ├── playlist/{PlaylistDetailScreen.kt, PlaylistDetailViewModel.kt}
            │   ├── playlist/{AddSongsScreen.kt, AddSongsViewModel.kt}
            │   ├── nowplaying/NowPlayingScreen.kt
            │   ├── player/PlayerViewModel.kt
            │   ├── navigation/{Mp3BoxNavHost.kt, Routes.kt}
            │   └── AppRoot.kt                 scaffold: nav host + mini-player + bottom bar
            └── util/{DisplayName.kt, Duration.kt, TileArtwork.kt, NaturalOrder.kt}
```

**47 Kotlin files.** No test sources (per spec), no README.

---

## 4. Gradle configuration

`gradle/libs.versions.toml` mirrors §1 exactly. Keep literal versions in the catalog; no dynamic versions, no `+`.

`app/build.gradle.kts` essentials:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
// AGP 9 ships built-in Kotlin: applying org.jetbrains.kotlin.android fails the build.

android {
    namespace = "com.local.mp3box"
    compileSdk = 37          // 2026 AndroidX requires 37; targetSdk stays 36

    defaultConfig {
        applicationId = "com.local.mp3box"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false      // no R8: avoids reflection surprises in Room/Media3
            isShrinkResources = false
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
```

Dependencies: Compose BOM (ui, ui-graphics, ui-tooling-preview, material3, material-icons-extended), activity-compose, navigation-compose, lifecycle-runtime-compose + lifecycle-viewmodel-compose, Room runtime/ktx + `ksp(room-compiler)`, DataStore preferences, Media3 exoplayer + session, Coil 3 compose, coroutines-android. `debugImplementation(ui-tooling)` is optional and adds build time; include it only if previews are wanted later.

`AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<!-- Sideload-only: ask once to be excluded from DokeOS battery optimisation. Not Play-policy safe,
     which is fine because this app is never published. -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
<!-- deliberately no READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE: SAF grant covers it -->

<application android:name=".Mp3BoxApp" android:label="MP3Box"
             android:theme="@style/Theme.Mp3Box" android:allowBackup="false"
             android:icon="@mipmap/ic_launcher" android:roundIcon="@mipmap/ic_launcher_round">

    <activity android:name=".MainActivity" android:exported="true"
              android:launchMode="singleTop" android:theme="@style/Theme.Mp3Box">
        <intent-filter>
            <action android:name="android.intent.action.MAIN"/>
            <category android:name="android.intent.category.LAUNCHER"/>
        </intent-filter>
    </activity>

    <service android:name=".playback.PlaybackService" android:exported="true"
             android:foregroundServiceType="mediaPlayback">
        <intent-filter>
            <action android:name="androidx.media3.session.MediaSessionService"/>
        </intent-filter>
    </service>
</application>
```

`Theme.Mp3Box` is a `Theme.Material3.Dark.NoActionBar` parent with `windowBackground` = app background so the cold-start frame is already dark. `values-night` is unnecessary because the app is always dark.

---

## 5. Data layer

### 5.1 Schema (Room v1, `exportSchema = false`)

```kotlin
@Entity(tableName = "tracks", indices = [Index(value = ["uri"], unique = true)])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,                 // document URI, stable key for re-scans
    val fileName: String,            // e.g. "01_Intro_V2.mp3"
    @ColumnInfo(collate = ColumnInfo.NOCASE) val title: String,  // derived, per spec §5
    val durationMs: Long,
    val sizeBytes: Long,
    val lastModified: Long,
    val isMissing: Boolean = false,
    val lastSeenAt: Long
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coverPath: String? = null,   // absolute path under filesDir/covers/
    val createdAt: Long,
    val updatedAt: Long              // bumped by add/remove/reorder/rename/cover; grid sort key
)

@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = CASCADE),
        ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = CASCADE)
    ],
    indices = [
        Index("playlistId"), Index("trackId"),
        Index(value = ["playlistId", "trackId"], unique = true),   // spec: no duplicates
        Index(value = ["playlistId", "position"])                  // deliberately NOT unique
    ]
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val trackId: Long,
    val position: Int
)

@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val id: Int = 1,     // single row, always PLAYBACK_STATE_ID (see below)
    val playlistId: Long?,
    val trackId: Long?,
    val positionMs: Long,            // written with the snapshot; not restored in v1 (§7.5)
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_ALL,   // androidx.media3.common.Player
    val updatedAt: Long
)

const val PLAYBACK_STATE_ID = 1      // read with WHERE id = :PLAYBACK_STATE_ID
```

`position` must **not** carry a unique index: every reorder rewrites the whole column in one transaction, and immediate unique constraints would reject intermediate states. Ordering is `ORDER BY position ASC, id ASC`.

### 5.2 DAO queries worth writing out

```kotlin
// Library: stable base order only, playable files only (spec §5.5). SQLite cannot compare digit
// runs numerically, so the Library ViewModel re-sorts with NaturalOrder.compare (§8.3).
@Query("SELECT * FROM tracks WHERE isMissing = 0 ORDER BY title ASC")
fun observeTracks(): Flow<List<TrackEntity>>

// Playlist content, ordered, with track rows joined in
@Query("""
    SELECT t.*, pi.position AS position, pi.id AS itemId
    FROM playlist_items pi JOIN tracks t ON t.id = pi.trackId
    WHERE pi.playlistId = :playlistId
    ORDER BY pi.position ASC, pi.id ASC
""")
fun observePlaylistEntries(playlistId: Long): Flow<List<PlaylistEntryRow>>

// Grid stats: count, playable duration, missing count
@Query("""
    SELECT p.*,
           (SELECT COUNT(*) FROM playlist_items pi WHERE pi.playlistId = p.id) AS songCount,
           (SELECT COALESCE(SUM(t.durationMs), 0) FROM playlist_items pi
              JOIN tracks t ON t.id = pi.trackId
              WHERE pi.playlistId = p.id AND t.isMissing = 0) AS durationMs,
           (SELECT COUNT(*) FROM playlist_items pi JOIN tracks t ON t.id = pi.trackId
              WHERE pi.playlistId = p.id AND t.isMissing = 1) AS missingCount
    FROM playlists p ORDER BY p.updatedAt DESC, p.id DESC
""")
fun observePlaylistsWithStats(): Flow<List<PlaylistStatsRow>>

// Insert, dedupe via the unique index without aborting the batch
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertItems(items: List<PlaylistItemEntity>): List<Long>   // -1 = duplicate, report skipped

@Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlistId = :playlistId")
suspend fun nextPosition(playlistId: Long): Int

// Call in the same transaction as every playlist mutation (add/remove/reorder/rename/cover).
@Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
suspend fun touchPlaylist(id: Long, now: Long)

@Query("UPDATE tracks SET isMissing = 1 WHERE id NOT IN (:seenIds)")
suspend fun markMissingExcept(seenIds: List<Long>)

@Query("UPDATE tracks SET isMissing = 0 WHERE uri IN (:uris)")
suspend fun markPresent(uris: List<String>)

@Query("UPDATE tracks SET isMissing = 1")
suspend fun markAllMissing()

// Present files this listing no longer contains; counted before marking so a loss is reported
// by the scan that finds it and never again.
@Query("SELECT COUNT(*) FROM tracks WHERE isMissing = 0 AND uri NOT IN (:seenUris)")
suspend fun countNewlyMissing(seenUris: List<String>): Int

@Query("SELECT COUNT(*) FROM tracks WHERE isMissing = 0")
suspend fun countAvailable(): Int

// Spec §5.5: a missing row survives only while a playlist still points at it (the stub);
// the same statement runs in PlaylistDao when a removal or delete leaves a stub unreferenced.
@Query("DELETE FROM tracks WHERE isMissing = 1 AND id NOT IN (SELECT trackId FROM playlist_items)")
suspend fun deleteOrphanedMissing()
```

Empty `IN ()` lists need guarding (`if (seenIds.isEmpty()) markAllMissing() else markMissingExcept(...)`) — Room expands lists at runtime and SQLite rejects empty `IN`.

### 5.3 Repositories

- **`MusicFolderRepository`** — owns the SAF tree URI (DataStore), checks `contentResolver.persistedUriPermissions`, runs scans, exposes `Flow<List<Track>>`, `StateFlow<FolderState>` (`NoFolder` / `Ok` / `Revoked`), and `ScanResult(added, updated, missing, total, failures)`.
- **`PlaylistRepository`** — create/rename/delete/duplicate/reorder/add/remove, cover assignment, stats flow. Every mutation sets `updatedAt = now` via `touchPlaylist` in the same transaction; create/duplicate set `updatedAt = createdAt`. Duplicate = copy rows + items in positions order + copy cover JPEG to a new file. Removing entries (individually, in bulk, via "Remove unavailable tracks", or by deleting the playlist) also deletes missing track rows left without any playlist reference, in the same transaction.
- **`CoverArtStore`** — `import(playlistId, sourceUri)` copies the picked image to `filesDir/covers/<playlistId>-<timestamp>.jpg`: decode bounds → `inSampleSize` to ≤ 640 px → JPEG 88 → delete the old file. Picking is the caller's job (photo picker URI is only valid briefly, so copy immediately).
- **`PlaybackStateRepository`** — single-row upsert, debounced (~1 s) from the player repository, read once on controller connect.
- **`SettingsStore`** — DataStore keys: `folder_uri`, `battery_opt_asked`, `notif_perm_asked`. Shuffle and repeat are **not** here: they live in `playback_state` and are re-applied on restore (§7.5). A fresh playlist start still forces shuffle OFF and repeat ALL.

---

## 6. Folder access and scanning

### 6.1 Grant

First launch, no folder: Library shows a "Choose your music folder" empty state with a button launching `ActivityResultContracts.OpenDocumentTree()`. On result:

```kotlin
contentResolver.takePersistableUriPermission(
    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
)
settings.setFolderUri(uri)
library.scan()
```

Spec: one folder, top-level files only, subfolders ignored.

DokeOS ships its own DocumentsUI, so validate before persisting. A vendor picker can return a URI
that is a plain document or a tree whose children query throws:

```kotlin
if (!DocumentsContract.isTreeUri(uri)) return@launch   // ignore, re-show the CTA
val reachable = runCatching {
    contentResolver.query(
        DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)),
        arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null
    )?.use { it.moveToFirst() } ?: false
}.getOrDefault(false)
if (!reachable) { /* fall back to ACTION_OPEN_DOCUMENT_TREE once, then show the folder-lost banner */ }
```

Never wipe library data when the vendor picker misbehaves — the folder-lost path (§6.6) already keeps
the cache.

### 6.2 Listing (top-level only)

`DocumentFile` walks children lazily and is slow; query the children URI directly:

```kotlin
suspend fun listTopLevelAudio(treeUri: Uri): List<FoundFile> = withContext(Dispatchers.IO) {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri)
    )
    val out = mutableListOf<FoundFile>()
    resolver.query(
        children,
        arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        ), null, null, null
    )?.use { c ->
        while (c.moveToNext()) {
            val mime = c.getString(2) ?: continue
            if (mime == Document.MIME_TYPE_DIR) continue          // subfolders ignored
            val name = c.getString(1) ?: continue
            if (name.substringAfterLast('.', "").lowercase() !in setOf("mp3", "m4a")) continue
            out += FoundFile(
                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)),
                fileName = name,
                size = c.getLong(3),
                lastModified = c.getLong(4)
            )
        }
    }
    out
}
```

The extension check is the only format gate: `.mp3` and `.m4a` are indexed (case-insensitive);
everything else is skipped. M4A is AAC in an MP4 container and `MediaExtractor` reads its duration
header the same way it reads MP3.

### 6.3 Duration (cheap, no decode)

`MediaMetadataRetriever` costs 50–200 ms per file. `MediaExtractor` only parses headers:

```kotlin
fun durationMs(resolver: ContentResolver, uri: Uri): Long = runCatching {
    resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
        MediaExtractor().use { ex ->
            ex.setDataSource(pfd.fileDescriptor, 0, pfd.statSize)
            (0 until ex.trackCount)
                .map { ex.getTrackFormat(it) }
                .firstOrNull { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)
                ?.div(1000) ?: 0L
        }
    }
}.getOrDefault(0L)
```

### 6.4 Scan algorithm

```text
1. folderUri == null                -> state NoFolder, keep DB untouched
2. persisted permission missing     -> state Revoked, keep DB untouched (playback disabled)
3. list top-level .mp3/.m4a files  -> seen: List<FoundFile>
4. for each file (sequential, IO dispatcher):
     row = trackDao.findByUri(uri)
     if row == null                    -> insert new (duration, title, size, lastModified, lastSeenAt = now)
     else if size/lastModified changed  -> update duration/title + isMissing = false, lastSeenAt = now
     else if derived title differs      -> update title + isMissing = false, lastSeenAt = now
                                           (keeps stored titles in step with §4.2 changes)
     else                              -> isMissing = false, lastSeenAt = now
5. lost = if seen.isEmpty() countAvailable() else countNewlyMissing(seen uris)   (before marking)
6. if seen.isEmpty() -> markAllMissing()   else markMissingExcept(seen ids)
7. deleteOrphanedMissing()  // playlist-referenced rows stay as stubs; every other lost row goes
8. emit ScanResult(missing = lost, total = countAvailable(); update lastScanAt
```

Concurrency: a `Mutex` around scanning; launching a second scan (pull-to-refresh during auto-scan) awaits the first. Progress is exposed as `StateFlow<ScanProgress?>` so pull-to-refresh has something to show even with 500 tracks.

Re-scan triggers per spec: app launch, pull-to-refresh, toolbar button. No content observer.

### 6.5 Title derivation (spec §4.2)

```kotlin
private val TRAILING_NUMBER = Regex("\\d+$")

fun titleFromFileName(fileName: String): String {
    val base = fileName.substringBeforeLast('.', fileName).trimEnd()
    val spaced = base.replace(TRAILING_NUMBER, "").replace('_', ' ')
    val collapsed = spaced.replace(Regex("\\s+"), " ").trim()
    return collapsed.ifEmpty {
        base.replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifEmpty { fileName }
    }
}
```

A trailing digit run is the duplicate suffix, so `Test2.m4a` joins `Test.m4a` as `Test` while
`Test3Test.m4a` keeps its digits; leading numbers are untouched (`7_years2.m4a` → `7 years`).

No ID3 reading anywhere: no artist, album, embedded art.

### 6.6 Folder-lost handling

`FolderState` drives a banner at the top of both tabs:

- `Revoked` → "Folder access lost." + **Fix** button (re-run `OpenDocumentTree`, compare tree ID — if a different folder is picked, treat as a folder switch: rescan and keep old tracks flagged missing).
- `NoFolder` → first-run empty state, transport disabled.
- Playback commands from mini-player / Now Playing are no-ops with a snackbar while not `Ok`.

---

## 7. Playback

### 7.1 Service

```kotlin
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val attrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ true)  // duck/resume per spec
            .setHandleAudioBecomingNoisy(true)                          // pause on unplug
            .setSeekBackIncrementMs(5_000)
            .setSeekForwardIncrementMs(15_000)
            .build()
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // spec: playback continues when the task is swiped away while playing
        if (session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
```

Media3's default notification provider supplies the media notification, lock-screen controls and Bluetooth transport controls. `POST_NOTIFICATIONS` is requested on first play.

DokeOS's SystemUI renders the media card from `MediaMetadata`, so every `MediaItem` also carries
`setArtworkData(tileJpeg(seed = title), MediaMetadata.PICTURE_TYPE_FRONT_COVER)`. `tileJpeg()` lives
in `util/TileArtwork.kt`: the same deterministic hue pair as the Compose `TrackTile`, drawn into a
512 × 512 `Bitmap` with `LinearGradient` (HSV → `Color.HSVToColor`) and JPEG-encoded at q85. Encode
once per title and cache the bytes in a small LRU; `artworkData` crosses a binder, so keep it under
~100 KB per item.

### 7.2 Controller plumbing (`PlayerRepository`)

```kotlin
val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
controllerFuture = MediaController.Builder(context, token).buildAsync()
controllerFuture.addListener({
    controller = controllerFuture.get()
    controller.addListener(listener)
    restoreIfNeeded()
}, MoreExecutors.directExecutor())
```

Expose one immutable state object to the UI:

```kotlin
data class PlayerUiState(
    val connected: Boolean = false,
    val currentTrackId: Long? = null,
    val playlistId: Long? = null,
    val isPlaying: Boolean = false,
    val queueSize: Int = 0,      // playable item count, for "Track i of N"
    val currentIndex: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_ALL,
    val durationMs: Long = 0,
    val errorEvent: String? = null
)
```

Rebuilt from `Player.Listener.onEvents` (`onMediaItemTransition`, `onIsPlayingChanged`, `onPlaybackStateChanged`, `onRepeatModeChanged`, `onShuffleModeEnabledChanged`, `onPlayerError`). Position is *not* in this flow — the Now Playing screen polls `controller.currentPosition` every 500 ms while visible, which avoids a state update per frame.

Commands: `playPlaylist(playlistId, startTrackId?)`, `playLibrary(trackId)`, `togglePlayPause`, `next`, `previous`, `seekTo`, `toggleShuffle`, `cycleRepeat`.

### 7.3 Queue construction

```kotlin
fun buildQueue(entries: List<PlaylistEntryRow>): List<MediaItem> =
    entries.filterNot { it.isMissing }.map { e ->
        MediaItem.Builder()
            .setMediaId(e.trackId.toString())
            .setUri(e.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(e.title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }
```

Missing tracks never enter the queue — that satisfies "skipped during playback" for the queue-creation case. If a file vanishes *mid-session*, `onPlayerError` triggers one `seekToNextMediaItem()` (guarded against a tight loop) and reports a snackbar string through `errorEvent`.

When a playlist has zero playable tracks: snackbar "No playable songs in this playlist", no queue change.

MIME types are explicit: SAF document URIs have no extension ExoPlayer can sniff, so each item sets
`MimeTypes.AUDIO_MP4` for `.m4a` and `MimeTypes.AUDIO_MPEG` for `.mp3`. AAC is decoded by the
platform MediaCodec on API 31+, so no extra extractor or decoder dependency is needed.

### 7.4 Shuffle and repeat

- Shuffle button: `controller.shuffleModeEnabled = !shuffleModeEnabled`. Native ExoPlayer keeps the current item playing and restores original order on toggle-off. **Never** writes to `playlist_items` — the database order is the saved order. There is no `savedQueue` column: the unshuffled queue lives in the player, and across restarts the DB order plus the saved track is enough to rebuild it.
- Both toggles are written to `playback_state` with the rest of the snapshot (`shuffleEnabled`, `repeatMode`) and re-applied after restore (§7.5).
- Repeat button cycles only two states (spec: no "off"):

```kotlin
fun nextRepeatMode(current: Int) =
    if (current == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_ONE
```

Default at queue creation: `REPEAT_MODE_ALL`.

### 7.5 Resume

After the controller connects and the DB is read:

1. read `PlaybackStateEntity`; if `trackId`/`playlistId` null → nothing.
2. build the queue for that playlist in saved order (respecting missing flags).
3. index = position of saved track in that queue; if absent → clear the snapshot.
4. `setMediaItems(queue, index, 0)` — **do not play** (spec: paused at 0:00); `prepare()` happens in step 6 once the modes are applied.
5. seek to 0: the stored `positionMs` is written with the snapshot but deliberately **not** restored in v1 (reserved for a future "resume where you left off" setting).
6. apply the saved modes: `setShuffleModeEnabled(state.shuffleEnabled)`, `setRepeatMode(state.repeatMode)` (defaults shuffle off / `REPEAT_MODE_ALL`), then `prepare()` and stay paused. A fresh playlist start still resets both (§7.4).

Snapshot writes happen on `onMediaItemTransition`, on `pause`, and on app background (`onStop`) — debounced 1 s, at most one DB write per second.

### 7.6 Battery-optimisation prompt (DokeOS)

DokeOS can stop the foreground service when the screen is off, so the app asks once to be exempted.
Trigger: the first folder grant *after* the first successful scan, or the first play, whichever is
later than onboarding — never on a cold start that has no folder.

```kotlin
val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    Uri.parse("package:$packageName"))
val resolved = intent.resolveActivity(packageManager)
if (resolved != null) launcher.launch(intent)          // DokeOS may replace this dialog
else startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
```

Prompt copy must state that it is about playback continuing with the screen off, and that it can be
changed later. Store `battery_opt_asked = true` regardless of the answer. The Library overflow entry
`Background playback` re-opens the flow and lists the DokeOS paths: System Manager → App Management →
Auto Start Management, Auto Clean exception; Smart Assist → DuraSpeed exclusion; Settings → Apps →
MP3Box → Battery → Don't optimise; Recents → lock MP3Box. This card is the only place these
instructions live — there is no separate settings tab.

---

## 8. UI implementation notes

### 8.1 Theme (`ui/theme`)

Always dark, no dynamic colour, fixed accent (indigo-violet `#6C5CE7` family — deliberately not Spotify green, not YT red).
`background #0E0E10`, `surface #17171A`, `surfaceVariant #232327`, `onBackground #F2F2F5`, `onSurfaceVariant #9A9AA5`, `primary = accent`, `secondary = desaturated accent`.

`Type.kt`: default `Typography` with minor adjustments (track titles `titleSmall`, headings `titleLarge`, stats/labels `labelSmall`). Material 3's default font is fine — a bundled custom font costs APK size and adds a licence file.

### 8.2 Deterministic gradient artwork (`TrackTile`)

Every track/playlist without a cover gets a gradient derived from a stable seed, so the grid does not flicker or reshuffle between recompositions:

```kotlin
fun tileBrush(seed: String): Brush {
    val h = seed.hashCode()
    val hue = ((h ushr 8) and 0xFF) / 255f * 360f
    return Brush.linearGradient(listOf(
        Color.hsl(hue, 0.30f, 0.22f),
        Color.hsl((hue + 28f) % 360f, 0.36f, 0.38f)
    ))
}
```

Tile content: music-note icon (Material icons extended) or the first letter(s) of the title. Playlists with a cover use `AsyncImage(model = File(coverPath))`; otherwise same tile logic.

### 8.3 Screens

| Screen | Implementation notes |
|---|---|
| **AppRoot** | `Scaffold(bottomBar = { Column { MiniPlayer; NavigationBar } })`; `NavHost` inside `Modifier.padding(innerPadding)` except Now Playing, which is a full-screen destination placed *outside* the pillarbox so it can cover the tabs. Outside the tabs the mini-player adds `navigationBarsPadding()` itself, because no `NavigationBar` is there to absorb the navigation-bar inset |
| **Library** | `TopAppBar` "Songs" + search field (inline `TextField` or `SearchBar`, filter on `title` contains, case/space-insensitive) + rescan icon. `PullToRefreshBox` (Material 3, `@OptIn(ExperimentalMaterial3Api::class)`) wraps the `LazyColumn` of `TrackRow`s. Empty states: no folder → picker CTA; folder with no supported files → "No MP3 or M4A files in this folder" + rescan. List order: `NaturalOrder.compare` on `title` (spec §8.3) |
| **TrackRow** | 56 dp row with a 40 dp tile, title (single line, ellipsis), duration right-aligned, missing → 38 % alpha, caption "File missing", not clickable. Long-press opens the context sheet (Play, Add to playlist). Current track shows accent-coloured title + a small equaliser icon |
| **Playlists** | `LazyVerticalGrid(GridCells.Fixed(2))` (fixed 2 columns per spec), `PlaylistCard` with 1:1 artwork, name (2 lines max), `"12 tracks · 43 min"` (`Duration.formatPlaylistLength`, spec §7.3), missing suffix `· 2 missing`. FAB `+` → create dialog (name field, Create disabled when blank/duplicate). Empty state with explanation |
| **Playlist detail** | Header: 140 dp cover (tap to open photo picker), name, stats, Play + Shuffle + Add songs buttons, overflow (Rename, Change cover, Remove cover, Duplicate, Delete). Numbered rows (shuffle glyph when shuffle is on), current row tinted; per-row overflow = Play, Remove from playlist. Edit mode (top-bar pencil): checkboxes for multi-select with a `Remove N` bar plus a drag handle per row; rows are a fixed 56 dp, so reorder targets come from drag distance rather than lazy-layout offsets. "Remove unavailable tracks" appears in the overflow only when `missingCount > 0`, behind a confirm dialog |
| **Add songs** | Full-screen route with search + `LazyColumn` of all tracks, checkbox multi-select, already-present tracks checked and disabled with "Already in playlist"; confirm button `"Add 3 songs"` inserts in the order shown and reports skipped duplicates |
| **Now Playing** | Full-screen: close chevron, 240 dp generated tile, title, `Track i of N`, seek bar (`Slider` with local drag state so the position poll cannot fight the thumb), time labels, transport row (shuffle, previous, play/pause 64 dp, next, repeat with "1" badge) |
| **MiniPlayer** | 64 dp `Surface`, 40 dp tile, title (single line, no `Playing/Paused` label), play/pause, next, 2 dp `LinearProgressIndicator` at the top; tap → Now Playing; hidden when `currentTrackId == null`; pads for the navigation bar on routes without the tab bar |
| **Sheets** | `ModalBottomSheet` for the track context sheet and playlist overflow; `AlertDialog` for delete/rename; snackbar host at app root (`SnackbarHostState` shared via `AppRoot`) |
| **Navigation** | Routes: `library`, `playlists`, `playlist/{id}`, `playlist/{id}/add`, `nowplaying`. Bottom bar has two items; detail screens hide the bar (or keep mini-player only). Back from Now Playing returns to the previous screen |

Edge-to-edge: `enableEdgeToEdge()`, `WindowInsets` handled by `Scaffold`, `NavigationBar` gets navigation-bar insets automatically, Now Playing handles status/nav bars via `Modifier.windowInsetsPadding`.

### 8.4 Drag-and-drop reorder (as built)

No third-party reorder library. The implementation lives in `PlaylistDetailScreen.kt` and relies on
the rows being a fixed **56 dp** tall:

- Edit mode (pencil in the top bar) reveals a trailing drag handle per row; the handle gets
  `detectDragGestures`, the rest of the row stays a selection checkbox target.
- `ReorderState` remembers the dragged item id, its `baseIndex` and the accumulated `dy`. The target
  index is `baseIndex + round(totalDy / rowHeight)`, clamped to the list, so no `LazyListState`
  offset lookups are needed. The dragged row follows the finger via `graphicsLayer.translationY`.
- The ViewModel holds an optimistic `orderOverride` so the list re-sorts immediately; on drag end the
  order is persisted with one `@Transaction` that rewrites dense `position` values.
- There is **no** long-press-to-lift and **no** auto-scroll while dragging: scroll the list before
  starting a drag. Reordering is refused while shuffle is ON ("Turn off shuffle to reorder") and the
  drag handles are hidden outside edit mode.

### 8.5 Permissions and first-run flow

1. Launch → Library, no folder → picker CTA.
2. Folder picked → scan → snackbar `"128 songs found"`.
3. First Play → if `POST_NOTIFICATIONS` not granted, request it; denial is non-blocking (audio still plays, no notification visible). On API 33 this prompt is real.
4. After the first successful scan (or first play), ask once about battery optimisation (§7.6) and show the DokeOS whitelist card once.
5. Mini-player appears, Now Playing reachable.

---

## 9. Build order (phases and gates)

Each phase ends with `./gradlew :app:assembleDebug` green. Do not start the next phase with a red build.

| Phase | Work | Files | Gate |
|---|---|---|---|
| **0. Bootstrap** | §2 environment, wrapper, empty module | settings/build/gradle.properties/manifest | `assembleDebug` produces a blank APK |
| **1. Shell + theme** | Theme, `MainActivity`, `AppRoot`, nav host with placeholder screens, bottom bar, strings | 9 | APK installs conceptually: `aapt2 dump badging` shows package `com.local.mp3box`, label MP3Box, minSdk 31, targetSdk 36 |
| **2. Data layer** | Entities, DAOs, database, SettingsStore, domain models, AppContainer, stub repos | 14 | KSP generates DAOs without warnings; build green |
| **3. Library & scanning** | Picker, permission persistence, scanner, Library screen with search/rescan/refresh/banners | 7 | Build green; scan logic reviewed against §6.4 |
| **4. Playback** | PlaybackService, PlayerRepository, notification config, play from Library | 4 | Merged manifest contains the `MediaSessionService` intent filter and `foregroundServiceType="mediaPlayback"` (`aapt2 dump xmltree … AndroidManifest.xml`) |
| **5. Playlists** | Grid, create/rename/delete/duplicate, detail, add/remove, drag reorder, covers | 9 | Build green |
| **6. Now Playing** | Now Playing, mini-player, shuffle/repeat | 3 | Build green |
| **7. Resilience** | Missing tracks, folder revoked, resume snapshot, snackbars, notification permission | 4 | Build green |
| **7.5 DokeOS hardening** | Battery exemption + whitelist card, vendor picker validation, `artworkData` tiles, insets in both nav modes | 4 | Build green; API 33 emulator smoke test passes; phone checklist from §10.2 ready |
| **8. Polish** | Spacing/typography pass, empty states, insets, icon set, app icon | 3 | Build green |
| **9. Package** | Release build type, `proguard-rules.pro` placeholder, final debug + release APKs | 1 | See §10 |

Rough effort: phases 0–1 ≈ 15 %, 2 ≈ 10 %, 3 ≈ 15 %, 4 ≈ 20 %, 5 ≈ 20 %, 6–8 ≈ 20 %.

---

## 10. Verification

### 10.1 What can be verified here

```bash
export JAVA_HOME=$HOME/.local/jdk
export ANDROID_HOME=$HOME/android-sdk

./gradlew --no-daemon :app:assembleDebug          # exit 0
ls -l app/build/outputs/apk/debug/app-debug.apk    # exists, non-trivial size

"$ANDROID_HOME/build-tools/37.0.0/aapt2" dump badging \
  app/build/outputs/apk/debug/app-debug.apk | head -12
# package name com.local.mp3box, versionName 1.0, sdkVersion 31, targetSdkVersion 36,
# application-label MP3Box, launchable-activity MainActivity

"$ANDROID_HOME/build-tools/37.0.0/aapt2" dump permissions \
  app/build/outputs/apk/debug/app-debug.apk
# FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS only

./gradlew --no-daemon :app:assembleRelease        # release APK builds, signed with the debug key

# API 33 emulator (matches the target runtime): install + drive
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/platform-tools/adb shell am start -n com.local.mp3box/.MainActivity
$ANDROID_HOME/platform-tools/adb logcat -s AndroidRuntime:E MP3Box:* ExoPlayerImpl:*

# Phone, when attached: same plus DokeOS checks
$ANDROID_HOME/platform-tools/adb shell getprop ro.build.version.sdk          # expect 33
$ANDROID_HOME/platform-tools/adb shell dumpsys package com.local.mp3box | grep -A3 grantPermissions
```

Optional static checks (cheap, do them once at phase 9): `./gradlew :app:lintDebug` and read the report; ignore performance/typography nits, fix errors.

### 10.2 What cannot be verified here (report as untested)

No physical device is attached to the container and there is no audio hardware. The API 33 emulator
covers the functional items below (except audio output, unplug detection and real SystemUI); the
device-only items are 13–18. These must be checked on a DokeOS device by the user, or by the agent if
`adb devices` shows it:

1. Folder picker grants access; permission survives app restart.
2. Scan finds exactly the top-level `.mp3` and `.m4a` files, subfolders ignored.
3. Playback audio, volume, and pause-on-headphone-unplug.
4. Notification shows artwork/title/transport; lock-screen and Bluetooth controls work.
5. Playlist loops (repeat-all), repeat-one repeats the same track.
6. Shuffle keeps the current song and restores order when toggled off.
7. Drag-and-drop reorder feel and persistence across restart.
8. Renaming a file externally: the scan reports `+1 missing` once, the row leaves the library, a
   playlist keeps a `File missing` stub, and playback skips it.
9. Revoked folder (delete the folder / clear grant) → banner + Fix flow keeps playlists.
10. Resume: relaunch shows the saved track paused at 0:00.
11. Cover picking from the gallery, and covers surviving app restart.
12. Notification permission denied → playback still works.
13. **DokeOS background survival:** play a playlist, turn the screen off, wait 30 minutes, then check
    the notification/lock screen still shows the session and the track advanced (repeat-all on).
    Repeat with battery saver on and with the app swept from Recents.
14. **DokeOS battery exemption flow:** prompt appears once, “Don't optimise” sticks, and Library
    overflow → `Background playback` re-opens it. Denying it must not break foreground playback.
15. **DokeOS pickers:** folder tree from the vendor DocumentsUI is usable; the grant survives a reboot
    and a System Manager cleanup. `Change cover` opens a picker that returns a readable image.
16. **DokeOS media card:** title and generated artwork appear in the shade and on the lock screen in
    both gesture and 3-button navigation, with nothing clipped by the bars.
17. **DokeOS cleanup:** playlist cover JPEGs survive System Manager “cleanup” and a forced stop.
18. **API 33 parity:** the same APK behaves identically on the API 33 emulator — no API 34+ path is
    required for playback, pickers or notifications.

---

## 11. Risks and mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| System JDK 27 picked up by Gradle, AGP rejects it | High if unmanaged | `org.gradle.java.home` in `gradle.properties`; `JAVA_HOME` exported in every build command |
| KSP 2.3.13 incompatible with Kotlin 2.4.10 | Low–medium | KSP is decoupled now; if it fails, fall back to the KSP release built for Kotlin 2.4.10 (`2.4.10-x`), or last resort Kotlin 2.4.20 |
| Compose BOM APIs drifted (PullToRefresh, Slider, SearchBar) | Medium | Compile early in phase 1; adjust to current signatures rather than pinning older BOM |
| Room + KSP annotation errors block phase 2 | Low | Keep schema minimal; `exportSchema = false`; verify DAO SQL by compiling after each DAO |
| SAF cursor slow / huge folder | Medium | Scan on IO dispatcher, progress state, `Mutex`, no per-file `DocumentFile` calls |
| Duplicate insert aborts a batch | Medium | `OnConflictStrategy.IGNORE` returns `-1`; count and report skipped |
| Empty `IN ()` crash on first scan with no files | Medium | Explicit branch to `markAllMissing()` |
| Reorder position writes collide | Low | No unique index on `position`; single transaction |
| Media3 notification denied on Android 13+ | Low | Request permission on first play, non-blocking |
| Disk fills during dependency download | Medium | ~30 GB free; API 33 AVD adds ~3.5 GB. Clean `~/.gradle/caches` if needed and say so |
| DokeOS kills the FGS when the screen is off | High without mitigation | Battery exemption + whitelist card + 30 min test gate; never claim background playback works until observed |
| Vendor DocumentsUI returns an unusable tree / loses the grant | Medium | Validate `isTreeUri`, fall back to the stock picker, keep the cached library and show the §6.6 banner |
| Vendor photo picker missing or returns an unreadable URI | Low–medium | `PickVisualMedia` fallback to `ACTION_OPEN_DOCUMENT`; copy bytes immediately |
| Emulator has no GMS → photo picker behaves differently | Low | Use `google_apis` (not `default`); the phone is the authority |
| Emulator cannot show DokeOS battery/notification behaviour | Certain | Emulator is a functional smoke test only; §10.2 items 13–18 are phone-only |
| Runtime behaviour unverifiable | Certain without `adb` | Explicit untested list in §10.2 and in the final report |

---

## 12. Out of scope (unchanged from the spec)

ID3 tags, artist/album browsing, embedded artwork, multiple folders, subfolder recursion, background folder observer, audio "off" repeat mode, gapless/crossfade, equaliser, sleep timer, lyrics, casting, widgets, Android Auto, tablet/foldable layouts, localisation, light theme, dynamic colour, playlist import/export, cloud sync, tests, README, git repository.

---

## 13. Definition of done

1. `app-debug.apk` exists at `app/build/outputs/apk/debug/app-debug.apk` and `aapt2 dump badging` matches §10.1.
2. `app-release.apk` also builds and installs (signed with the machine's debug key, nothing committed).
3. Every screen in §8.3 exists and is reachable; no placeholder/TODO composables remain.
4. Spec §12 acceptance criteria are covered except the device-only items, which are reported as untested.
5. The final report states the exact commands run, their results, and the untested list from §10.2.
6. API 33 emulator smoke test: install, launch, folder grant, scan, play/pause, playlist create/add, background/return, notification prompt — all observed, not assumed.
7. DokeOS checklist from §10.2 (items 13–18) is either run on the phone or explicitly reported as not run, with the APK path handed over.
8. No code path requires API 34+ behaviour; the APK is correct on API 33.

---

## 14. Command cheat sheet

```bash
# build debug
JAVA_HOME=$HOME/.local/jdk ./gradlew --no-daemon :app:assembleDebug

# inspect APK
$HOME/android-sdk/build-tools/37.0.0/aapt2 dump badging \
  app/build/outputs/apk/debug/app-debug.apk

# merged manifest check
$HOME/android-sdk/build-tools/37.0.0/aapt2 dump xmltree \
  app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml | less

# clean rebuild after version changes
./gradlew --no-daemon clean :app:assembleDebug

# stop daemons before reporting (keeps memory for the container)
./gradlew --stop
```
