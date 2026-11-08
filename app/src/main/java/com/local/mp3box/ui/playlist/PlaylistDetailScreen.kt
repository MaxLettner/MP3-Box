package com.local.mp3box.ui.playlist

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.local.mp3box.Mp3BoxApp
import com.local.mp3box.R
import com.local.mp3box.data.model.PlaylistEntryRow
import com.local.mp3box.ui.components.PlaylistCover
import com.local.mp3box.ui.components.TrackTile
import com.local.mp3box.util.Duration
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ROW_HEIGHT = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onAddSongs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val factory = remember(context, playlistId) {
        playlistDetailViewModelFactory(context, playlistId)
    }
    val viewModel: PlaylistDetailViewModel = viewModel(factory = factory)

    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var editMode by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingRemoveUnavailable by remember { mutableStateOf(false) }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.setCover(uri)
    }
    val pickCover: () -> Unit = {
        coverPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    val coverFailedMessage = stringResource(R.string.cover_failed)
    val duplicatedMessage = stringResource(R.string.playlist_duplicated)
    val duplicateFailedMessage = stringResource(R.string.playlist_duplicate_failed)
    val shuffleMessage = stringResource(R.string.turn_off_shuffle_to_reorder)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                PlaylistDetailEvent.CoverFailed -> coverFailedMessage
                PlaylistDetailEvent.Duplicated -> duplicatedMessage
                PlaylistDetailEvent.DuplicateFailed -> duplicateFailedMessage
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    val available = entries.filterNot { it.isMissing }
    val missingCount = entries.size - available.size
    val durationMs = available.sumOf { it.durationMs }
    val currentTrackId = playerState.currentTrackId
    val shuffleOn = playerState.shuffle

    val reorder = remember { ReorderState() }
    reorder.entries = entries
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = playlist?.name.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!editMode && shuffleOn) {
                                scope.launch { snackbarHostState.showSnackbar(shuffleMessage) }
                            } else {
                                editMode = !editMode
                                selected = emptySet()
                                reorder.end()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (editMode) Icons.Filled.Done else Icons.Filled.Edit,
                            contentDescription = stringResource(
                                if (editMode) R.string.done else R.string.edit
                            )
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.more_actions)
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                onClick = {
                                    menuOpen = false
                                    renameText = playlist?.name.orEmpty()
                                    renaming = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.change_cover)) },
                                onClick = {
                                    menuOpen = false
                                    pickCover()
                                }
                            )
                            if (playlist?.coverPath != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remove_cover)) },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.clearCover()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.duplicate)) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.duplicate()
                                }
                            )
                            if (missingCount > 0) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remove_unavailable)) },
                                    onClick = {
                                        menuOpen = false
                                        confirmingRemoveUnavailable = true
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                onClick = {
                                    menuOpen = false
                                    confirmingDelete = true
                                }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (editMode) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            editMode = false
                            selected = emptySet()
                        }) { Text(stringResource(R.string.cancel)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                viewModel.removeTracks(selected)
                                selected = emptySet()
                            },
                            enabled = selected.isNotEmpty()
                        ) {
                            Text(
                                pluralStringResource(
                                    R.plurals.remove_tracks,
                                    selected.size,
                                    selected.size
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item(key = "header") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlaylistCover(
                            coverPath = playlist?.coverPath,
                            seed = playlist?.name.orEmpty(),
                            modifier = Modifier
                                .size(140.dp)
                                .clickable { pickCover() }
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist?.name.orEmpty(),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = Duration.formatPlaylistStats(
                                    entries.size,
                                    durationMs,
                                    missingCount
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = viewModel::playFromTop,
                            enabled = available.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.play))
                        }
                        Spacer(Modifier.width(12.dp))
                        FilledTonalIconButton(
                            onClick = viewModel::shufflePlay,
                            enabled = available.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shuffle,
                                contentDescription = stringResource(R.string.shuffle)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onAddSongs) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.add_songs)
                            )
                        }
                    }
                }
            }

            if (missingCount > 0) {
                item(key = "missing") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                R.string.available_of_total,
                                available.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { confirmingRemoveUnavailable = true }) {
                            Text(stringResource(R.string.remove_unavailable))
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.empty_playlist),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.empty_playlist_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onAddSongs) {
                            Text(stringResource(R.string.add_songs))
                        }
                    }
                }
            }

            itemsIndexed(entries, key = { _, entry -> entry.itemId }) { index, entry ->
                // Passed as a slot so the row keeps `modifier` as its only Modifier parameter.
                val dragHandle: @Composable () -> Unit = {
                    Box(
                        modifier = Modifier
                            .pointerInput(entry.itemId) {
                                detectDragGestures(
                                    onDragStart = { reorder.start(entry.itemId) },
                                    onDragCancel = {
                                        reorder.end()
                                        viewModel.commitOrder()
                                    },
                                    onDragEnd = {
                                        reorder.end()
                                        viewModel.commitOrder()
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        reorder.drag(amount.y, rowHeightPx, viewModel::move)
                                    }
                                )
                            }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DragHandle,
                            contentDescription = stringResource(R.string.reorder),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                PlaylistRow(
                    entry = entry,
                    index = index,
                    isCurrent = entry.id == currentTrackId,
                    editing = editMode,
                    selected = entry.id in selected,
                    shuffleOn = shuffleOn,
                    onClick = {
                        if (editMode) {
                            selected = selected.toggle(entry.id)
                        } else {
                            viewModel.playTrack(entry.id)
                        }
                    },
                    onToggleSelected = { selected = selected.toggle(entry.id) },
                    onRemove = { viewModel.removeTrack(entry.id) },
                    dragHandle = dragHandle,
                    modifier = Modifier
                        .zIndex(if (reorder.isDragging(entry.itemId)) 1f else 0f)
                        .graphicsLayer { translationY = reorder.translationFor(entry.itemId) }
                )
            }
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename_playlist)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.playlist_name)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.rename(renameText)
                        renaming = false
                    },
                    enabled = renameText.isNotBlank()
                ) { Text(stringResource(R.string.rename)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.delete_playlist_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_playlist_body,
                        playlist?.name.orEmpty()
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.delete(onBack)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (confirmingRemoveUnavailable) {
        AlertDialog(
            onDismissRequest = { confirmingRemoveUnavailable = false },
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.remove_unavailable_question,
                        missingCount,
                        missingCount
                    )
                )
            },
            text = { Text(stringResource(R.string.remove_unavailable_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemoveUnavailable = false
                    viewModel.removeUnavailable()
                }) { Text(stringResource(R.string.remove_unavailable)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemoveUnavailable = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    entry: PlaylistEntryRow,
    index: Int,
    isCurrent: Boolean,
    editing: Boolean,
    selected: Boolean,
    shuffleOn: Boolean,
    onClick: () -> Unit,
    onToggleSelected: () -> Unit,
    onRemove: () -> Unit,
    dragHandle: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    var rowMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent
            )
            .alpha(if (entry.isMissing) 0.38f else 1f)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (editing) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
            Spacer(Modifier.width(4.dp))
        } else {
            Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (shuffleOn) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                } else {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        TrackTile(seed = entry.uri, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.isMissing) {
                Text(
                    text = stringResource(R.string.file_missing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (editing) {
            dragHandle()
        } else {
            Text(
                text = if (entry.durationMs > 0) {
                    Duration.formatTrack(entry.durationMs)
                } else {
                    stringResource(R.string.unknown_duration)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                IconButton(onClick = { rowMenuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.more_actions)
                    )
                }
                DropdownMenu(
                    expanded = rowMenuOpen,
                    onDismissRequest = { rowMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.play)) },
                        enabled = !entry.isMissing,
                        onClick = {
                            rowMenuOpen = false
                            onClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_from_playlist)) },
                        onClick = {
                            rowMenuOpen = false
                            onRemove()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = null
                            )
                        }
                    )
                }
            }
        }
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (id in this) this - id else this + id

/** Fixed-height rows make index math trivial; no lazy-layout offset lookups needed. */
private class ReorderState {
    var entries: List<PlaylistEntryRow> = emptyList()
    var draggingId by mutableStateOf<Long?>(null)
    var offset by mutableFloatStateOf(0f)
    private var baseIndex = 0
    private var total = 0f

    fun start(itemId: Long) {
        draggingId = itemId
        baseIndex = entries.indexOfFirst { it.itemId == itemId }
        total = 0f
        offset = 0f
    }

    fun drag(dy: Float, rowHeight: Float, move: (Int, Int) -> Unit) {
        if (draggingId == null || rowHeight <= 0f) return
        total += dy
        offset = total
        val target = (baseIndex + (total / rowHeight).roundToInt())
            .coerceIn(0, entries.lastIndex.coerceAtLeast(0))
        if (target != baseIndex) {
            move(baseIndex, target)
            total -= (target - baseIndex) * rowHeight
            baseIndex = target
            offset = total
        }
    }

    fun end() {
        draggingId = null
        total = 0f
        offset = 0f
    }

    fun isDragging(itemId: Long): Boolean = draggingId == itemId

    fun translationFor(itemId: Long): Float = if (draggingId == itemId) offset else 0f
}

private fun playlistDetailViewModelFactory(
    context: Context,
    playlistId: Long
): ViewModelProvider.Factory {
    val container = (context.applicationContext as Mp3BoxApp).container
    return viewModelFactory {
        initializer {
            PlaylistDetailViewModel(
                playlistId,
                container.playlistRepository,
                container.playerRepository
            )
        }
    }
}
