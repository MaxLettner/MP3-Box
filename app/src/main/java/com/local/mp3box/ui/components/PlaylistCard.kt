package com.local.mp3box.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.local.mp3box.data.model.PlaylistStatsRow
import com.local.mp3box.util.Duration
import com.local.mp3box.util.tileBrush
import java.io.File

/** Spec §8.4: fixed 2-column grid card — square cover, name (2 lines), stats line. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistCard(
    stats: PlaylistStatsRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        PlaylistCover(
            coverPath = stats.coverPath,
            seed = stats.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stats.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = Duration.formatPlaylistStats(stats.songCount, stats.durationMs, stats.missingCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Custom cover if one was picked, otherwise the deterministic gradient (spec §7.4). */
@Composable
fun PlaylistCover(
    coverPath: String?,
    seed: String,
    modifier: Modifier = Modifier,
    iconSize: Dp? = null
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tileBrush(seed)),
        contentAlignment = Alignment.Center
    ) {
        if (coverPath != null) {
            AsyncImage(
                model = File(coverPath).toUri(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = if (iconSize != null) {
                    Modifier.size(iconSize)
                } else {
                    Modifier.fillMaxSize(0.3f)
                }
            )
        }
    }
}
