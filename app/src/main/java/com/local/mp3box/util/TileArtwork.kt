package com.local.mp3box.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import android.graphics.Color as AndroidColor
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Plan §8.2: a stable per-seed gradient so tiles never flicker or reshuffle between
 * recompositions. Same seed always yields the same pair of hues.
 */
fun tileBrush(seed: String): Brush {
    val hue = hueOf(seed)
    return Brush.linearGradient(
        listOf(
            Color.hsl(hue, 0.30f, 0.22f),
            Color.hsl((hue + 28f) % 360f, 0.36f, 0.38f)
        )
    )
}

/**
 * The same gradient as JPEG bytes for `MediaMetadata.artworkData`, so DokeOS's media card
 * and lock screen show artwork instead of an empty square (plan §7.1). Gradients encode very
 * small — 512 px at quality 85 is ~5 KB — and encoded bytes are cached per seed.
 */
private val artworkCache = object : LruCache<String, ByteArray>(64) {}

fun tileJpeg(seed: String, sizePx: Int = 512): ByteArray {
    artworkCache.get(seed)?.let { return it }
    val hue = hueOf(seed)
    val bitmap = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, sizePx.toFloat(), sizePx.toFloat(),
            intArrayOf(
                hslToArgb(hue, 0.30f, 0.22f),
                hslToArgb((hue + 28f) % 360f, 0.36f, 0.38f)
            ),
            null,
            Shader.TileMode.CLAMP
        )
    }
    Canvas(bitmap).drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
    bitmap.recycle()
    return out.toByteArray().also { artworkCache.put(seed, it) }
}

private fun hueOf(seed: String): Float = ((seed.hashCode() ushr 8) and 0xFF) / 255f * 360f

private fun hslToArgb(hue: Float, saturation: Float, lightness: Float): Int {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val h = hue / 60f
    val x = chroma * (1f - abs(h % 2f - 1f))
    val (r, g, b) = when {
        h < 1f -> Triple(chroma, x, 0f)
        h < 2f -> Triple(x, chroma, 0f)
        h < 3f -> Triple(0f, chroma, x)
        h < 4f -> Triple(0f, x, chroma)
        h < 5f -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    val m = lightness - chroma / 2f
    return AndroidColor.rgb(
        ((r + m) * 255f).roundToInt().coerceIn(0, 255),
        ((g + m) * 255f).roundToInt().coerceIn(0, 255),
        ((b + m) * 255f).roundToInt().coerceIn(0, 255)
    )
}
