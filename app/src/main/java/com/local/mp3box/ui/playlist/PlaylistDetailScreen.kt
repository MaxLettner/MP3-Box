package com.local.mp3box.ui.playlist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.mp3box.R
import com.local.mp3box.data.model.PlaylistEntryRow
import com.local.mp3box.ui.appContainer
import com.local.mp3box.ui.components.ConfirmDialog
import com.local.mp3box.ui.components.NameDialog
import com.local.mp3box.ui.components.PlaylistCover
import com.local.mp3box.ui.components.TrackTile
import com.local.mp3box.ui.components.trackDurationText
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
    val container = appContainer()
    val viewModel = viewModel {
        PlaylistDetailViewModel(playlistId, container.playlistRepository, container.playerRepository)
    }

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

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                PlaylistDetailEvent.CoverFailed -> R.string.cover_failed
                PlaylistDetailEvent.Duplicated -> R.string.playlist_duplicated
                PlaylistDetailEvent.DuplicateFailed -> R.string.playlist_duplicate_failed
            }
            snackbarHostState.showSnackbar(context.getString(message))
        }
    }

    val available = entries.filterNot { it.track.isMissing }
    val missingCount = entries.size - available.size
    val durationMs = available.sumOf { it.track.durationMs }
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
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.turn_off_shuffle_to_reorder)
                                    )
                                }
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
                val trackId = entry.track.id
                PlaylistRow(
                    entry = entry,
                    index = index,
                    isCurrent = trackId == currentTrackId,
                    editing = editMode,
                    selected = trackId in selected,
                    shuffleOn = shuffleOn,
                    onClick = {
                        if (editMode) {
                            selected = selected.toggle(trackId)
                        } else {
                            viewModel.playTrack(trackId)
                        }
                    },
                    onToggleSelected = { selected = selected.toggle(trackId) },
                    onRemove = { viewModel.removeTracks(setOf(trackId)) },
                    dragHandle = dragHandle,
                    modifier = Modifier
                        .zIndex(if (reorder.isDragging(entry.itemId)) 1f else 0f)
                        .graphicsLayer { translationY = reorder.translationFor(entry.itemId) }
                )
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = stringResource(R.string.rename_playlist),
            confirmLabel = stringResource(R.string.rename),
            initial = playlist?.name.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = { name ->
                viewModel.rename(name)
                renaming = false
            }
        )
    }

    if (confirmingDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_playlist_title),
            text = stringResource(R.string.delete_playlist_body, playlist?.name.orEmpty()),
            confirmLabel = stringResource(R.string.delete),
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                viewModel.delete(onBack)
            }
        )
    }

    if (confirmingRemoveUnavailable) {
        ConfirmDialog(
            title = pluralStringResource(
                R.plurals.remove_unavailable_question,
                missingCount,
                missingCount
            ),
            text = stringResource(R.string.remove_unavailable_body),
            confirmLabel = stringResource(R.string.remove_unavailable),
            onDismiss = { confirmingRemoveUnavailable = false },
            onConfirm = {
                confirmingRemoveUnavailable = false
                viewModel.removeUnavailable()
            }
        )
    }
}

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
            .alpha(if (entry.track.isMissing) 0.38f else 1f)
            .clickable(onClick = onClick)
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
        TrackTile(seed = entry.track.title, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.track.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.track.isMissing) {
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
                text = trackDurationText(entry.track.durationMs),
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
                        enabled = !entry.track.isMissing,
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
    private var draggingId by mutableStateOf<Long?>(null)

    /** Finger offset from the dragged row's current slot; drawn as its translation. */
    private var offset by mutableFloatStateOf(0f)
    private var baseIndex = 0

    fun start(itemId: Long) {
        draggingId = itemId
        baseIndex = entries.indexOfFirst { it.itemId == itemId }
        offset = 0f
    }

    fun drag(dy: Float, rowHeight: Float, move: (Int, Int) -> Unit) {
        if (draggingId == null || rowHeight <= 0f) return
        offset += dy
        val target = (baseIndex + (offset / rowHeight).roundToInt())
            .coerceIn(0, entries.lastIndex.coerceAtLeast(0))
        if (target != baseIndex) {
            move(baseIndex, target)
            offset -= (target - baseIndex) * rowHeight
            baseIndex = target
        }
    }

    fun end() {
        draggingId = null
        offset = 0f
    }

    fun isDragging(itemId: Long): Boolean = draggingId == itemId

    fun translationFor(itemId: Long): Float = if (draggingId == itemId) offset else 0f
}
