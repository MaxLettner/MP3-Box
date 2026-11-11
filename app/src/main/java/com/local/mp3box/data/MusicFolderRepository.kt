package com.local.mp3box.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.local.mp3box.data.db.TrackDao
import com.local.mp3box.data.db.TrackEntity
import com.local.mp3box.data.model.Track
import com.local.mp3box.data.model.toDomain
import com.local.mp3box.util.NaturalOrder
import com.local.mp3box.util.titleFromFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface FolderState {
    /** Before DataStore has answered, so the onboarding CTA does not flash. */
    data object Loading : FolderState
    data object NoFolder : FolderState
    data class Ok(val uri: Uri) : FolderState
    data class Revoked(val uri: Uri) : FolderState
}

/**
 * [missing] counts the files lost by *this* scan, not the running total, so a deleted file is
 * announced once; [total] is what the library still lists as playable afterwards (spec §5.5).
 */
data class ScanResult(
    val added: Int,
    val updated: Int,
    val missing: Int,
    val total: Int,
    val failures: Int
)

/**
 * Owns the single SAF tree grant (spec §5), keeps [FolderState] current and runs
 * the idempotent scan from plan §6.4. Never writes to playlist tables.
 */
class MusicFolderRepository(
    context: Context,
    private val settings: SettingsStore,
    private val trackDao: TrackDao,
    private val scanner: MediaScanner
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    private val resolver = context.contentResolver

    private val _folderState = MutableStateFlow<FolderState>(FolderState.Loading)
    val folderState: StateFlow<FolderState> = _folderState.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _lastResult = MutableStateFlow<ScanResult?>(null)
    val lastResult: StateFlow<ScanResult?> = _lastResult.asStateFlow()

    /** Natural A–Z by title (spec §8.3); SQLite cannot order digit runs numerically. */
    val tracks: Flow<List<Track>> = trackDao.observeTracks().map { rows ->
        rows.map(TrackEntity::toDomain).sortedWith(compareBy(NaturalOrder) { it.title })
    }

    init {
        scope.launch {
            settings.folderUri.collect { stored -> _folderState.value = resolve(stored) }
        }
        // Auto-scan on app launch (spec §5.3), once the stored grant has been resolved.
        scope.launch {
            val state = _folderState.filter { it !is FolderState.Loading }.first()
            if (state is FolderState.Ok) scan()
        }
    }

    suspend fun refreshFolderState(): FolderState {
        val state = resolve(settings.folderUri.first())
        _folderState.value = state
        return state
    }

    /**
     * Persists the picked tree and scans it. Returns false when the vendor picker handed
     * back something unusable, in which case nothing is persisted (plan §6.1).
     */
    suspend fun onFolderPicked(uri: Uri): Boolean {
        if (!DocumentsContract.isTreeUri(uri)) return false
        if (!scanner.isReachable(uri)) return false
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        settings.setFolderUri(uri.toString())
        if (refreshFolderState() !is FolderState.Ok) return false
        scan()
        return true
    }

    /** Null when there is no grant to scan; the DB is left untouched in that case. */
    suspend fun scan(): ScanResult? = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val treeUri = (refreshFolderState() as? FolderState.Ok)?.uri ?: return@withLock null
            _isScanning.value = true
            try {
                val files = runCatching { scanner.listTopLevelAudio(treeUri) }.getOrElse {
                    _folderState.value = FolderState.Revoked(treeUri)
                    return@withLock null
                }
                val now = System.currentTimeMillis()
                val seen = ArrayList<String>(files.size)
                var added = 0
                var updated = 0
                var failures = 0

                files.forEach { file ->
                    runCatching {
                        val uriString = file.uri.toString()
                        val existing = trackDao.findByUri(uriString)
                        val title = titleFromFileName(file.fileName)
                        when {
                            existing == null -> {
                                val duration = scanner.durationMs(file.uri)
                                val id = trackDao.insert(
                                    TrackEntity(
                                        uri = uriString,
                                        fileName = file.fileName,
                                        title = title,
                                        durationMs = duration,
                                        sizeBytes = file.sizeBytes,
                                        lastModified = file.lastModified,
                                        lastSeenAt = now
                                    )
                                )
                                if (id == -1L) {
                                    // Lost an insert race: fall back to updating the winner.
                                    trackDao.findByUri(uriString)?.let { row ->
                                        trackDao.updateFromScan(
                                            row.id, title, duration,
                                            file.sizeBytes, file.lastModified, now
                                        )
                                    }
                                    updated++
                                } else {
                                    added++
                                }
                            }
                            // The title comparison keeps an unchanged file's stored title in sync
                            // with current derivation rules (spec §4.2) after an app update.
                            existing.title != title ||
                                existing.fileName != file.fileName ||
                                existing.sizeBytes != file.sizeBytes ||
                                existing.lastModified != file.lastModified -> {
                                trackDao.updateFromScan(
                                    existing.id,
                                    title,
                                    scanner.durationMs(file.uri),
                                    file.sizeBytes,
                                    file.lastModified,
                                    now
                                )
                                updated++
                            }
                            else -> trackDao.markSeen(existing.id, now)
                        }
                        seen += uriString
                    }.onFailure { failures++ }
                }

                // Count the loss before marking: an already-missing file is not lost twice, so
                // its snackbar line appears on the scan that finds it gone and never again.
                val lost = if (seen.isEmpty()) {
                    trackDao.countAvailable()
                } else {
                    trackDao.countNewlyMissing(seen)
                }
                // Room rejects an empty IN (), so the no-files case needs its own statement.
                if (seen.isEmpty()) trackDao.markAllMissing() else trackDao.markMissingExcept(seen)
                // Spec §5.5: only rows a playlist still references survive as stubs; the rest of
                // the library loses them for good here, so songs never show unavailable files.
                trackDao.deleteOrphanedMissing()
                ScanResult(
                    added = added,
                    updated = updated,
                    missing = lost,
                    total = trackDao.countAvailable(),
                    failures = failures
                ).also { _lastResult.value = it }
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun resolve(stored: String?): FolderState {
        if (stored == null) return FolderState.NoFolder
        val uri = stored.toUri()
        val granted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return if (granted) FolderState.Ok(uri) else FolderState.Revoked(uri)
    }
}
