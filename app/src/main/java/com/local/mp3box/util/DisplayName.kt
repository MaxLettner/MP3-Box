package com.local.mp3box.util

/** A run of digits at the very end of the base name: the duplicate suffix in `Test2` or `years2`. */
private val TRAILING_NUMBER = Regex("\\d+$")

/**
 * Spec §4.2: strip the extension and a trailing number (a common duplicate suffix), underscores
 * become spaces, collapse runs of whitespace and trim. Numbers elsewhere in the name are kept, so
 * `Test2.m4a` and `Test.m4a` both read `Test` while `Test3Test.m4a` stays `Test3Test`. No ID3
 * reading anywhere.
 */
fun titleFromFileName(fileName: String): String {
    val base = fileName.substringBeforeLast('.', fileName).trimEnd()
    val spaced = base.replace(TRAILING_NUMBER, "").replace('_', ' ')
    val collapsed = spaced.replace(Regex("\\s+"), " ").trim()
    return collapsed.ifEmpty {
        // Only numbers in the name (`1234.mp3`) or a blank base: keep the name itself.
        base.replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifEmpty { fileName }
    }
}
