package com.local.mp3box.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.mp3box.R
import com.local.mp3box.data.model.PlaylistStatsRow
import com.local.mp3box.ui.appContainer
import com.local.mp3box.ui.components.ConfirmDialog
import com.local.mp3box.ui.components.EmptyState
import com.local.mp3box.ui.components.NameDialog
import com.local.mp3box.ui.components.PlaylistCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    onCreatePlaylist: (Long) -> Unit,
    onPlaylistClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val container = appContainer()
    val viewModel = viewModel {
        PlaylistsViewModel(container.playlistRepository, container.playerRepository)
    }
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                PlaylistsEvent.Duplicated -> R.string.playlist_duplicated
                PlaylistsEvent.DuplicateFailed -> R.string.playlist_duplicate_failed
            }
            snackbarHostState.showSnackbar(context.getString(message))
        }
    }

    var creating by rememberSaveable { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<PlaylistStatsRow?>(null) }
    var renaming by remember { mutableStateOf<PlaylistStatsRow?>(null) }
    var deleting by remember { mutableStateOf<PlaylistStatsRow?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.playlists_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_playlist))
            }
        }
    ) { innerPadding ->
        val loaded = playlists
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                loaded == null -> Unit
                loaded.isEmpty() -> EmptyState(
                    title = stringResource(R.string.empty_playlists),
                    body = stringResource(R.string.empty_playlists_body)
                ) {
                    Button(onClick = { creating = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.new_playlist))
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(loaded, key = { it.id }) { playlist ->
                        PlaylistCard(
                            stats = playlist,
                            onClick = { onPlaylistClick(playlist.id) },
                            onLongClick = { actionsFor = playlist }
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.new_playlist),
            confirmLabel = stringResource(R.string.create),
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                viewModel.create(name) { id -> onCreatePlaylist(id) }
            }
        )
    }

    renaming?.let { playlist ->
        NameDialog(
            title = stringResource(R.string.rename_playlist),
            confirmLabel = stringResource(R.string.rename),
            initial = playlist.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                viewModel.rename(playlist.id, name)
                renaming = null
            }
        )
    }

    deleting?.let { playlist ->
        ConfirmDialog(
            title = stringResource(R.string.delete_playlist_title),
            text = stringResource(R.string.delete_playlist_body, playlist.name),
            confirmLabel = stringResource(R.string.delete),
            onDismiss = { deleting = null },
            onConfirm = {
                viewModel.delete(playlist.id)
                deleting = null
            }
        )
    }

    actionsFor?.let { playlist ->
        ModalBottomSheet(
            onDismissRequest = { actionsFor = null },
            sheetState = rememberModalBottomSheetState()
        ) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(8.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.rename)) },
                modifier = Modifier.clickable {
                    renaming = playlist
                    actionsFor = null
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.duplicate)) },
                modifier = Modifier.clickable {
                    viewModel.duplicate(playlist.id)
                    actionsFor = null
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.delete)) },
                modifier = Modifier.clickable {
                    deleting = playlist
                    actionsFor = null
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
