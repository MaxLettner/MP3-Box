package com.local.mp3box.util

/**
 * Spec §4.2: strip the extension, underscores become spaces, collapse runs of
 * whitespace and trim. No ID3 reading anywhere.
 */
fun titleFromFileName(fileName: String): String {
    val base = fileName.substringBeforeLast('.', fileName)
    val spaced = base.replace('_', ' ')
    val collapsed = spaced.replace(Regex("\\s+"), " ").trim()
    return collapsed.ifEmpty { fileName }
}
