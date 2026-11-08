package com.local.mp3box.util

/** Time formatting shared by rows, the mini-player and playlist cards. */
object Duration {

    /** "3:07", or "1:02:03" once past an hour. */
    fun formatTrack(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0L)) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /** Spec §7.3 card stat: "43 min", or "1 h 12 min". */
    fun formatPlaylistLength(durationMs: Long): String {
        val totalMinutes = durationMs.coerceAtLeast(0L) / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "$hours h $minutes min" else "$minutes min"
    }

    /** Spec §7.3 card stat: "12 tracks · 43 min", plus missing suffix. */
    fun formatPlaylistStats(trackCount: Int, durationMs: Long, missingCount: Int): String {
        val tracks = if (trackCount == 1) "1 track" else "$trackCount tracks"
        val base = "$tracks · ${formatPlaylistLength(durationMs)}"
        return if (missingCount > 0) "$base · $missingCount missing" else base
    }
}
