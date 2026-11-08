package com.local.mp3box.ui.playlist

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.local.mp3box.Mp3BoxApp
import com.local.mp3box.R
import com.local.mp3box.data.model.Track
import com.local.mp3box.ui.components.TrackTile
import com.local.mp3box.util.Duration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSongsScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onAdded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val factory = remember(context, playlistId) { addSongsViewModelFactory(context, playlistId) }
    val viewModel: AddSongsViewModel = viewModel(factory = factory)

    val visibleTracks by viewModel.visibleTracks.collectAsStateWithLifecycle()
    val existingIds by viewModel.existingTrackIds.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_songs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.add(onAdded) },
                        enabled = selected.isNotEmpty()
                    ) {
                        Text(
                            pluralStringResource(
                                R.plurals.add_tracks_count,
                                selected.size,
                                selected.size
                            )
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_songs)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
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

            val loaded = visibleTracks
            if (loaded.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.no_playable_songs_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(loaded, key = { it.id }) { track ->
                        SelectableTrackRow(
                            track = track,
                            alreadyAdded = track.id in existingIds,
                            checked = track.id in selected,
                            onToggle = { viewModel.toggle(track.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableTrackRow(
    track: Track,
    alreadyAdded: Boolean,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val enabled = !alreadyAdded && !track.isMissing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .alpha(if (track.isMissing) 0.38f else 1f)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = alreadyAdded || checked, onCheckedChange = { onToggle() }, enabled = enabled)
        TrackTile(seed = track.uri, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (alreadyAdded) {
                Text(
                    text = stringResource(R.string.in_playlist),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (track.durationMs > 0) {
                Duration.formatTrack(track.durationMs)
            } else {
                stringResource(R.string.unknown_duration)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun addSongsViewModelFactory(context: Context, playlistId: Long): ViewModelProvider.Factory {
    val container = (context.applicationContext as Mp3BoxApp).container
    return viewModelFactory {
        initializer {
            AddSongsViewModel(playlistId, container.musicFolderRepository, container.playlistRepository)
        }
    }
}
