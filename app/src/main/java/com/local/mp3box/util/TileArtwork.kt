package com.local.mp3box.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Plan §8.2: a stable per-seed gradient so tiles never flicker or reshuffle between
 * recompositions. The seed (a track title or playlist name, spec §7.4) picks one of 256 hues,
 * so the same seed always yields the same pair of colours, on screen and in the media card.
 */
private fun hueIndex(seed: String): Int = (seed.hashCode() ushr 8) and 0xFF

private fun tileColors(hueIndex: Int): Pair<Color, Color> {
    val hue = hueIndex / 255f * 360f
    return Color.hsl(hue, 0.30f, 0.22f) to Color.hsl((hue + 28f) % 360f, 0.36f, 0.38f)
}

fun tileBrush(seed: String): Brush = Brush.linearGradient(tileColors(hueIndex(seed)).toList())

/** One JPEG per hue: at ~5 KB each the whole set stays near 1 MB, whatever the library size. */
private val artworkCache = ConcurrentHashMap<Int, ByteArray>()

/**
 * The same gradient as JPEG bytes for `MediaMetadata.artworkData`, so DokeOS's media card
 * and lock screen show artwork instead of an empty square (plan §7.1).
 */
fun tileJpeg(seed: String): ByteArray {
    val hueIndex = hueIndex(seed)
    return artworkCache.getOrPut(hueIndex) { encodeTile(hueIndex) }
}

private fun encodeTile(hueIndex: Int): ByteArray {
    val (start, end) = tileColors(hueIndex)
    val size = ARTWORK_PX.toFloat()
    val bitmap = createBitmap(ARTWORK_PX, ARTWORK_PX, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, size, size, start.toArgb(), end.toArgb(), Shader.TileMode.CLAMP)
    }
    Canvas(bitmap).drawRect(0f, 0f, size, size, paint)
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
    bitmap.recycle()
    return out.toByteArray()
}

private const val ARTWORK_PX = 512
