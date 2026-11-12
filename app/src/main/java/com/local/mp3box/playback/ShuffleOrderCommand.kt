package com.local.mp3box.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * The `Player` API a [androidx.media3.session.MediaController] sees cannot set a shuffle order,
 * so the UI sends the saved order to the service, which applies it to its `ExoPlayer` (spec §6.3).
 */
object ShuffleOrderCommand {

    const val ACTION_SET_ORDER = "com.local.mp3box.SET_SHUFFLE_ORDER"

    /** `IntArray` of queue positions in shuffled play order. */
    const val KEY_INDICES = "indices"

    /** Seed ExoPlayer keeps for items inserted after the order is applied. */
    const val KEY_SEED = "seed"

    val COMMAND = SessionCommand(ACTION_SET_ORDER, Bundle.EMPTY)
}
