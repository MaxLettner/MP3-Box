package com.local.mp3box.ui.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.mp3box.R
import com.local.mp3box.data.FolderState
import com.local.mp3box.data.model.Track
import com.local.mp3box.playback.PlaybackEvent
import com.local.mp3box.ui.appContainer
import com.local.mp3box.ui.components.AddToPlaylistSheet
import com.local.mp3box.ui.components.EmptyState
import com.local.mp3box.ui.components.FolderLostBanner
import com.local.mp3box.ui.components.NameDialog
import com.local.mp3box.ui.components.SearchField
import com.local.mp3box.ui.components.TrackContextSheet
import com.local.mp3box.ui.components.TrackRow
import com.local.mp3box.util.Duration
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = appContainer()
    val viewModel = viewModel {
        LibraryViewModel(
            container.musicFolderRepository,
            container.playerRepository,
            container.playlistRepository
        )
    }

    val folderState by viewModel.folderState.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val visibleTracks by viewModel.visibleTracks.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val currentTrackId by viewModel.currentTrackId.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    var addToTrack by remember { mutableStateOf<Track?>(null) }
    var newPlaylistTrack by remember { mutableStateOf<Track?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.pickFolder(uri)
    }

    // Spec §8.5: asked on the first play; a denial only means no media notification.
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* non-blocking either way */ }
    val playTrack: (Track) -> Unit = { track ->
        if (!askedForNotifications &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            askedForNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.play(track.id)
    }

    // A configuration change recreates the activity and this effect with it, so the strings
    // resolved here always match the current configuration. The newest message replaces the
    // one on screen rather than queueing behind it.
    LaunchedEffect(viewModel) {
        merge(
            viewModel.events.map { context.messageFor(it) },
            viewModel.playbackEvents.map { context.messageFor(it) }
        ).collectLatest { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(
                        onClick = viewModel::rescan,
                        enabled = folderState is FolderState.Ok && !isScanning
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.rescan)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (folderState is FolderState.Revoked) {
                FolderLostBanner(onFix = { folderPicker.launch(null) })
            }

            val loadedTracks = tracks
            val loadedVisible = visibleTracks
            when {
                folderState is FolderState.Loading || loadedTracks == null || loadedVisible == null -> Unit

                folderState is FolderState.NoFolder -> EmptyState(
                    title = stringResource(R.string.choose_folder_title),
                    body = stringResource(R.string.choose_folder_body),
                    icon = Icons.Filled.LibraryMusic
                ) {
                    Button(onClick = { folderPicker.launch(null) }) {
                        Text(stringResource(R.string.choose_folder))
                    }
                }

                loadedTracks.isEmpty() -> EmptyState(
                    title = stringResource(R.string.empty_library),
                    body = stringResource(R.string.empty_library_body)
                ) {
                    Button(onClick = viewModel::rescan, enabled = folderState is FolderState.Ok) {
                        Text(stringResource(R.string.rescan))
                    }
                }

                else -> {
                    SearchField(query = query, onQueryChange = viewModel::onQueryChange)
                    if (loadedVisible.isEmpty()) {
                        NoResults(
                            query = query,
                            onClearSearch = { viewModel.onQueryChange("") }
                        )
                    } else {
                        TrackList(
                            allTracks = loadedTracks,
                            visibleTracks = loadedVisible,
                            isScanning = isScanning,
                            currentTrackId = currentTrackId,
                            onRefresh = viewModel::rescan,
                            onPlay = playTrack,
                            onLongPress = { sheetTrack = it }
                        )
                    }
                }
            }
        }
    }

    sheetTrack?.let { track ->
        TrackContextSheet(
            track = track,
            onDismiss = { sheetTrack = null },
            onPlay = {
                sheetTrack = null
                playTrack(track)
            },
            onAddToPlaylist = {
                sheetTrack = null
                addToTrack = track
            }
        )
    }

    addToTrack?.let { track ->
        AddToPlaylistSheet(
            playlists = playlists,
            onDismiss = { addToTrack = null },
            onPick = { playlistId ->
                viewModel.addToPlaylist(playlistId, track.id)
                addToTrack = null
            },
            onNewPlaylist = {
                addToTrack = null
                newPlaylistTrack = track
            }
        )
    }

    newPlaylistTrack?.let { track ->
        NameDialog(
            title = stringResource(R.string.new_playlist),
            confirmLabel = stringResource(R.string.create),
            onDismiss = { newPlaylistTrack = null },
            onConfirm = { name ->
                viewModel.createPlaylistWithTrack(name, track.id)
                newPlaylistTrack = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackList(
    allTracks: List<Track>,
    visibleTracks: List<Track>,
    isScanning: Boolean,
    currentTrackId: Long?,
    onRefresh: () -> Unit,
    onPlay: (Track) -> Unit,
    onLongPress: (Track) -> Unit
) {
    PullToRefreshBox(
        isRefreshing = isScanning,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            item(key = "header") {
                // The library only lists playable files, so every track counts here.
                Text(
                    text = pluralStringResource(
                        R.plurals.library_header,
                        allTracks.size,
                        allTracks.size,
                        Duration.formatPlaylistLength(allTracks.sumOf { it.durationMs })
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(visibleTracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    isCurrent = track.id == currentTrackId,
                    onClick = { onPlay(track) },
                    onLongClick = { onLongPress(track) }
                )
            }
        }
    }
}

@Composable
private fun NoResults(query: String, onClearSearch: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.no_results, query.trim()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        TextButton(onClick = onClearSearch) {
            Text(stringResource(R.string.clear_search))
        }
    }
}

private fun Context.messageFor(event: LibraryEvent): String = when (event) {
    is LibraryEvent.SongsFound ->
        resources.getQuantityString(R.plurals.songs_found, event.count, event.count)
    is LibraryEvent.ScanFinished -> if (event.added > 0 || event.missing > 0) {
        getString(R.string.library_updated_counts, event.added, event.missing)
    } else {
        getString(R.string.library_updated)
    }
    LibraryEvent.FolderUnusable -> getString(R.string.folder_unusable)
    is LibraryEvent.AddedToPlaylist -> getString(R.string.added_to_playlist, event.name)
    LibraryEvent.AlreadyInPlaylist -> getString(R.string.already_in_playlist)
    is LibraryEvent.CreatedPlaylist -> getString(R.string.created_playlist, event.name)
}

private fun Context.messageFor(event: PlaybackEvent): String = getString(
    when (event) {
        PlaybackEvent.NothingPlayable -> R.string.nothing_playable
        PlaybackEvent.SongUnavailable -> R.string.song_unavailable
        PlaybackEvent.PlaybackFailed -> R.string.playback_failed
    }
)
