package app.haven.launcher.data

import java.text.Normalizer
import java.util.Locale

/** Pre-computed, normalised forms of an app's label so typing stays instant with hundreds of apps. */
data class SearchKey(
    val full: String,      // "google maps"
    val compact: String,   // "googlemaps"
    val words: List<String>,
    val initials: String,  // "gm"
    val packageName: String,
    val systemLabel: String, // original label, so a renamed app is still found by its real name
)

object Search {
    private val marks = Regex("\\p{Mn}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val camelBoundary = Regex("(?<=\\p{Ll})(?=\\p{Lu})")

    /** Lowercase, strip accents ("Café" -> "cafe") and collapse punctuation to spaces. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(marks, "")
            .lowercase(Locale.ROOT)
            .replace(separators, " ")
            .trim()

    fun keyFor(label: String, systemLabel: String, packageName: String): SearchKey {
        // Split camel case first so "WhatsApp" matches "app" and "wa".
        val full = normalize(label.replace(camelBoundary, " "))
        val words = full.split(' ').filter { it.isNotEmpty() }
        return SearchKey(
            full = full,
            compact = full.replace(" ", ""),
            words = words,
            initials = words.joinToString("") { it.take(1) },
            packageName = packageName.lowercase(Locale.ROOT),
            systemLabel = normalize(systemLabel).replace(" ", ""),
        )
    }

    /**
     * Higher is better, 0 means no match. Ordered by what people usually mean:
     * exact name, then prefix, then the start of any word, then initials, then anywhere.
     */
    fun score(key: SearchKey, rawQuery: String): Int {
        val q = normalize(rawQuery)
        if (q.isEmpty()) return 0
        val qc = q.replace(" ", "")
        return when {
            key.compact == qc -> 1000
            key.full.startsWith(q) || key.compact.startsWith(qc) -> 900 - key.compact.length.coerceAtMost(99)
            key.words.any { it.startsWith(qc) } -> 800 - key.compact.length.coerceAtMost(99)
            qc.length >= 2 && key.initials.startsWith(qc) -> 700
            key.compact.contains(qc) -> 600 - key.compact.indexOf(qc).coerceAtMost(99)
            key.systemLabel.contains(qc) -> 500
            qc.length >= 3 && key.packageName.contains(qc) -> 300
            qc.length >= 3 && isSubsequence(qc, key.compact) -> 100
            else -> 0
        }
    }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        var i = 0
        for (c in haystack) {
            if (c == needle[i] && ++i == needle.length) return true
        }
        return false
    }
}
