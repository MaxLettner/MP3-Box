package com.local.mp3box.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.local.mp3box.data.PlaybackStateRepository
import com.local.mp3box.data.db.PlaylistDao
import com.local.mp3box.data.db.PlaybackStateEntity
import com.local.mp3box.data.db.TrackDao
import com.local.mp3box.data.db.TrackEntity
import com.local.mp3box.data.model.toDomain
import com.local.mp3box.util.NaturalOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val connected: Boolean = false,
    val currentTrackId: Long? = null,
    val currentTitle: String? = null,
    val playlistId: Long? = null,
    val isPlaying: Boolean = false,
    /** Number of playable items in the player, used for "Track i of N". */
    val queueSize: Int = 0,
    val currentIndex: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_ALL,
    val durationMs: Long = 0L
)

sealed interface PlaybackEvent {
    data object NothingPlayable : PlaybackEvent
    data object SongUnavailable : PlaybackEvent
    data object PlaybackFailed : PlaybackEvent
}

/**
 * UI-facing wrapper around the service's [MediaController] (spec §6.1): one immutable
 * [PlayerUiState], transport commands and the debounced resume snapshot.
 */
class PlayerRepository(
    context: Context,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val stateRepository: PlaybackStateRepository
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val controllerFuture: ListenableFuture<MediaController> = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
    ).buildAsync()

    private var controller: MediaController? = null
    private var currentPlaylistId: Long? = null
    private var restored = false
    private var lastErrorMediaId: String? = null

    /**
     * Track ids of the queue in shuffled play order as last applied. Kept while shuffle is off so
     * a fresh toggle can be forced to differ from it; only written to the snapshot when shuffle is on.
     */
    private var shuffleOrderIds: List<Long> = emptyList()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _events = Channel<PlaybackEvent>(Channel.BUFFERED)
    val events: Flow<PlaybackEvent> = _events.receiveAsFlow()

    private val snapshotSignal = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncState()
            snapshotSignal.tryEmit(Unit)
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackError()
        }
    }

    init {
        controllerFuture.addListener({
            val connected = runCatching { controllerFuture.get() }.getOrNull()
            if (connected != null) {
                controller = connected
                connected.addListener(listener)
                syncState()
                scope.launch { restoreSnapshot() }
            }
        }, ContextCompat.getMainExecutor(appContext))

        // At most one snapshot write per second, however chatty the player events are.
        scope.launch {
            snapshotSignal.collect {
                delay(1_000)
                writeSnapshot()
            }
        }
    }

    /** Tapping a row in Library plays the library itself as the queue (spec §6.2). */
    fun playLibrary(trackId: Long) {
        scope.launch {
            val player = controller ?: return@launch
            val queue = withContext(Dispatchers.IO) { libraryQueue() }
            if (queue.isEmpty()) {
                _events.send(PlaybackEvent.NothingPlayable)
                return@launch
            }
            val index = queue.indexOfFirst { it.mediaId == trackId.toString() }
            if (index < 0) {
                _events.send(PlaybackEvent.SongUnavailable)
                return@launch
            }
            currentPlaylistId = null
            startQueue(player, queue, index)
        }
    }

    /** Spec §6.2: playlist playback starts at [startTrackId] (or the top), shuffle off by default. */
    fun playPlaylist(playlistId: Long, startTrackId: Long? = null, shuffle: Boolean = false) {
        scope.launch {
            val player = controller ?: return@launch
            val queue = withContext(Dispatchers.IO) {
                QueueFactory.forPlaylist(playlistDao.getPlaylistEntries(playlistId))
            }
            if (queue.isEmpty()) {
                _events.send(PlaybackEvent.NothingPlayable)
                return@launch
            }
            val index = startTrackId
                ?.let { id -> queue.indexOfFirst { it.mediaId == id.toString() } }
                ?.takeIf { it >= 0 } ?: 0
            currentPlaylistId = playlistId
            startQueue(player, queue, index)
            if (shuffle) {
                // Spec §8.5: the Shuffle button turns shuffle on and starts playing.
                enableShuffle(player, index)
                syncState()
            }
        }
    }

    /** Deleting the playlist that is playing clears the player (spec §7.2). */
    fun stopPlaylistIfCurrent(playlistId: Long) {
        if (currentPlaylistId != playlistId) return
        val player = controller ?: return
        player.pause()
        player.clearMediaItems()
        shuffleOrderIds = emptyList()
        currentPlaylistId = null
        syncState()
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying || player.playWhenReady) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
        syncState()
    }

    fun next() {
        val player = controller ?: return
        if (player.mediaItemCount > 0) player.seekToNextMediaItem()
    }

    /** Past 3 s, Previous restarts the track instead of stepping back. */
    fun previous() {
        val player = controller ?: return
        if (player.mediaItemCount == 0) return
        if (player.currentPosition > 3_000) player.seekTo(0) else player.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        val player = controller ?: return
        player.seekTo(positionMs.coerceAtLeast(0L))
        syncState()
    }

    fun toggleShuffle() {
        val player = controller ?: return
        if (player.shuffleModeEnabled) {
            player.shuffleModeEnabled = false
        } else {
            // A fresh toggle always deals a new order; only a restore replays a saved one.
            enableShuffle(player, player.currentMediaItemIndex)
        }
        syncState()
    }

    /** Spec §6.4: only two states, no "off". */
    fun cycleRepeat() {
        val player = controller ?: return
        player.repeatMode =
            if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL
            else Player.REPEAT_MODE_ONE
    }

    val currentPositionMs: Long
        get() = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L

    /** Writes the snapshot immediately; called when the app goes to the background. */
    fun flushSnapshot() {
        scope.launch { writeSnapshot() }
    }

    private fun startQueue(player: MediaController, queue: List<MediaItem>, index: Int) {
        lastErrorMediaId = null
        shuffleOrderIds = emptyList()
        player.setMediaItems(queue, index, 0L)
        player.repeatMode = Player.REPEAT_MODE_ALL   // fresh start resets both modes (spec §6.3/§6.4)
        player.shuffleModeEnabled = false
        player.prepare()
        player.play()
        syncState()
    }

    private suspend fun restoreSnapshot() {
        if (restored) return
        restored = true
        val player = controller ?: return
        if (player.mediaItemCount > 0) return
        val snapshot = withContext(Dispatchers.IO) { stateRepository.load() } ?: return
        val trackId = snapshot.trackId ?: return
        val queue = withContext(Dispatchers.IO) { queueFor(snapshot.playlistId) }
        val index = queue.indexOfFirst { it.mediaId == trackId.toString() }
        // Spec §6.6: a vanished track leaves the player empty, quietly.
        if (index < 0) return

        currentPlaylistId = snapshot.playlistId
        player.setMediaItems(queue, index, 0L)   // paused at 0:00, never auto-plays
        player.repeatMode =
            if (snapshot.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ONE
            else Player.REPEAT_MODE_ALL
        if (snapshot.shuffleEnabled) {
            // Spec §6.3: resume the exact order from the last session instead of re-shuffling.
            val queueIds = queue.mapNotNull { it.mediaId.toLongOrNull() }.toSet()
            val savedOrder = snapshot.shuffleOrder.filter { it in queueIds }.distinct()
            if (savedOrder.size == queueIds.size) {
                sendShuffleOrder(player, savedOrder)
            } else {
                enableShuffle(player, index)   // playlist changed since the snapshot; shuffle anew
            }
        }
        player.shuffleModeEnabled = snapshot.shuffleEnabled
        player.prepare()
        syncState()
    }

    private suspend fun libraryQueue(): List<MediaItem> = QueueFactory.forTracks(
        trackDao.getTracks()
            .map(TrackEntity::toDomain)
            .sortedWith(compareBy(NaturalOrder) { it.title })
    )

    private suspend fun queueFor(playlistId: Long?): List<MediaItem> =
        if (playlistId != null) {
            QueueFactory.forPlaylist(playlistDao.getPlaylistEntries(playlistId))
        } else {
            libraryQueue()
        }

    private fun handlePlaybackError() {
        _events.trySend(PlaybackEvent.PlaybackFailed)
        val player = controller ?: return
        val failedId = player.currentMediaItem?.mediaId ?: return
        if (failedId == lastErrorMediaId) return   // never loop on the same broken file
        lastErrorMediaId = failedId
        if (player.mediaItemCount > 1 && player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.play()
        }
    }

    private suspend fun writeSnapshot() {
        if (!restored) return
        val player = controller ?: return
        val trackId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val entity = PlaybackStateEntity(
            playlistId = currentPlaylistId,
            trackId = trackId,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            shuffleOrder = if (player.shuffleModeEnabled) shuffleOrderIds.joinToString(",") else null,
            updatedAt = System.currentTimeMillis()
        )
        withContext(Dispatchers.IO) { stateRepository.save(entity) }
    }

    /**
     * Spec §6.3: keeps the current item playing, shuffles the rest, and remembers the order.
     * Turning shuffle on always deals a new order; only [restoreSnapshot] replays a saved one.
     */
    private fun enableShuffle(player: MediaController, currentIndex: Int) {
        val queueIds = player.queueTrackIds()
        if (queueIds != null && queueIds.isNotEmpty()) {
            val current = currentIndex.coerceIn(0, queueIds.lastIndex)
            val rest = queueIds.indices.filter { it != current }.map { queueIds[it] }
            var order = listOf(queueIds[current]) + rest.shuffled()
            // A toggle should visibly differ from the order just left; re-draw a few times if not.
            var attempts = 0
            while (order == shuffleOrderIds && attempts < 5) {
                order = listOf(queueIds[current]) + rest.shuffled()
                attempts++
            }
            sendShuffleOrder(player, order)
        }
        player.shuffleModeEnabled = true
    }

    /**
     * Track ids of the queue the player actually holds, or null if any item is not one of ours.
     * Read from the controller rather than a remembered copy so a toggle can never be a silent
     * no-op (e.g. after a restore into an already-playing session).
     */
    private fun MediaController.queueTrackIds(): List<Long>? {
        val ids = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId.toLongOrNull() }
        return if (ids.any { it == null }) null else ids.filterNotNull()
    }

    /** Applies [orderIds] to the service's ExoPlayer; the controller cannot do this itself. */
    private fun sendShuffleOrder(player: MediaController, orderIds: List<Long>) {
        if (!player.isSessionCommandAvailable(ShuffleOrderCommand.COMMAND)) return
        val queueIds = player.queueTrackIds() ?: return
        val indexById = queueIds.mapIndexed { index, id -> id.toString() to index }.toMap()
        val indices = orderIds.mapNotNull { indexById[it.toString()] }
        if (indices.size != queueIds.size) return
        shuffleOrderIds = orderIds
        player.sendCustomCommand(
            ShuffleOrderCommand.COMMAND,
            Bundle().apply {
                putIntArray(ShuffleOrderCommand.KEY_INDICES, indices.toIntArray())
                putLong(ShuffleOrderCommand.KEY_SEED, System.nanoTime())
            }
        )
    }

    private fun syncState() {
        val player = controller
        if (player == null) {
            _state.value = PlayerUiState()
            return
        }
        val item = player.currentMediaItem
        _state.value = PlayerUiState(
            connected = true,
            currentTrackId = item?.mediaId?.toLongOrNull(),
            currentTitle = item?.mediaMetadata?.title?.toString(),
            playlistId = currentPlaylistId,
            isPlaying = player.isPlaying,
            queueSize = player.mediaItemCount,
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            durationMs = if (player.duration == C.TIME_UNSET) 0L else player.duration.coerceAtLeast(0L)
        )
    }
}
