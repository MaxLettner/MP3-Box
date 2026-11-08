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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.content.ContextCompat
import com.local.mp3box.Mp3BoxApp
import com.local.mp3box.R
import com.local.mp3box.data.FolderState
import com.local.mp3box.data.model.Track
import com.local.mp3box.playback.PlaybackEvent
import com.local.mp3box.ui.components.AddToPlaylistSheet
import com.local.mp3box.ui.components.FolderLostBanner
import com.local.mp3box.ui.components.TrackContextSheet
import com.local.mp3box.ui.components.TrackRow
import com.local.mp3box.util.Duration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val factory = remember(context) { libraryViewModelFactory(context) }
    val viewModel: LibraryViewModel = viewModel(factory = factory)

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
    var newPlaylistName by remember { mutableStateOf("") }

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

    // Events are kept in state and turned into text during composition. Reading strings from
    // LocalContext inside the collector would freeze the text at the configuration that was
    // current when it ran; stringResource re-reads it on a locale or size change.
    var libraryEvent by remember { mutableStateOf<LibraryEvent?>(null) }
    var libraryEventSeq by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            libraryEvent = event
            libraryEventSeq++
        }
    }
    val libraryMessage = libraryEvent?.let { event ->
        when (event) {
            is LibraryEvent.SongsFound -> pluralStringResource(
                R.plurals.songs_found, event.count, event.count
            )
            is LibraryEvent.ScanFinished -> if (event.added > 0 || event.missing > 0) {
                stringResource(R.string.library_updated_counts, event.added, event.missing)
            } else {
                stringResource(R.string.library_updated)
            }
            LibraryEvent.FolderUnusable -> stringResource(R.string.folder_unusable)
            is LibraryEvent.AddedToPlaylist ->
                stringResource(R.string.added_to_playlist, event.name)
            LibraryEvent.AlreadyInPlaylist -> stringResource(R.string.already_in_playlist)
            is LibraryEvent.CreatedPlaylist ->
                stringResource(R.string.created_playlist, event.name)
        }
    }
    LaunchedEffect(libraryEventSeq) {
        libraryMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    var playbackEvent by remember { mutableStateOf<PlaybackEvent?>(null) }
    var playbackEventSeq by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        viewModel.playbackEvents.collect { event ->
            playbackEvent = event
            playbackEventSeq++
        }
    }
    val playbackMessage = playbackEvent?.let { event ->
        when (event) {
            PlaybackEvent.NothingPlayable -> stringResource(R.string.nothing_playable)
            PlaybackEvent.SongUnavailable -> stringResource(R.string.song_unavailable)
            PlaybackEvent.PlaybackFailed -> stringResource(R.string.playback_failed)
        }
    }
    LaunchedEffect(playbackEventSeq) {
        playbackMessage?.let { snackbarHostState.showSnackbar(it) }
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

                folderState is FolderState.NoFolder -> Onboarding(
                    onChooseFolder = { folderPicker.launch(null) }
                )

                loadedTracks.isEmpty() -> EmptyFolder(
                    onRescan = viewModel::rescan,
                    canRescan = folderState is FolderState.Ok
                )

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
                newPlaylistName = ""
                newPlaylistTrack = track
            }
        )
    }

    newPlaylistTrack?.let { track ->
        AlertDialog(
            onDismissRequest = { newPlaylistTrack = null },
            title = { Text(stringResource(R.string.new_playlist)) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.playlist_name)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createPlaylistWithTrack(newPlaylistName, track.id)
                        newPlaylistTrack = null
                    },
                    enabled = newPlaylistName.isNotBlank()
                ) { Text(stringResource(R.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { newPlaylistTrack = null }) {
                    Text(stringResource(R.string.cancel))
                }
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
    val available = allTracks.filterNot { it.isMissing }
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
                Text(
                    text = pluralStringResource(
                        R.plurals.library_header,
                        available.size,
                        available.size,
                        Duration.formatPlaylistLength(available.sumOf { it.durationMs })
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.search_songs)) },
        leadingIcon = {
            Icon(imageVector = Icons.Filled.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.clear_search)
                    )
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

@Composable
private fun Onboarding(onChooseFolder: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.choose_folder_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.choose_folder_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onChooseFolder) {
            Text(stringResource(R.string.choose_folder))
        }
    }
}

@Composable
private fun EmptyFolder(onRescan: () -> Unit, canRescan: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.empty_library),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_library_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRescan, enabled = canRescan) {
            Text(stringResource(R.string.rescan))
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

private fun libraryViewModelFactory(context: Context): ViewModelProvider.Factory {
    val container = (context.applicationContext as Mp3BoxApp).container
    return viewModelFactory {
        initializer {
            LibraryViewModel(
                container.musicFolderRepository,
                container.playerRepository,
                container.playlistRepository
            )
        }
    }
}
