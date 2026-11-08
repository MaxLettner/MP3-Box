package com.local.mp3box.util

/**
 * Spec §8.3: human/natural A–Z. Digit runs compare numerically, numbers sort
 * before letters, case is ignored. SQLite cannot do this, so it runs in Kotlin
 * on the Library list and on the library queue.
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                while (i < a.length && a[i].isDigit()) i++
                val startB = j
                while (j < b.length && b[j].isDigit()) j++

                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                val byLength = numA.length.compareTo(numB.length)
                if (byLength != 0) return byLength
                val byDigits = numA.compareTo(numB)
                if (byDigits != 0) return byDigits
            } else {
                if (ca.isDigit() != cb.isDigit()) return if (ca.isDigit()) -1 else 1
                val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
