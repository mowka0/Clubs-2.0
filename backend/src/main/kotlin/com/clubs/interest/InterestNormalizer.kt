package com.clubs.interest

import java.text.Normalizer

/**
 * Canonicalizes a raw interest string so duplicates collapse to one dictionary
 * row: NFC → strip wrapping quotes → trim → collapse inner whitespace → lower →
 * ё→е. Multi-word phrases are preserved (separator is the comma, handled by the
 * caller); only inner runs of whitespace are squeezed to a single space.
 */
object InterestNormalizer {

    const val MAX_LEN = 40
    const val MAX_COUNT = 15
    const val MIN_QUERY_LEN = 2

    private val WHITESPACE = Regex("\\s+")
    private val QUOTES = charArrayOf('"', '\'', '«', '»', '“', '”', '‘', '’', '`')

    /** Normalize one token; returns null if it collapses to empty. */
    fun normalize(raw: String): String? {
        val cleaned = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .trim()
            .trim(*QUOTES)
            .trim()
            .replace(WHITESPACE, " ")
            .lowercase()
            .replace('ё', 'е')
        if (cleaned.isEmpty()) return null
        return cleaned.take(MAX_LEN)
    }

    /** Normalize a list, dropping blanks and deduping (order-preserving), capped. */
    fun normalizeList(raw: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (item in raw) {
            val normalized = normalize(item) ?: continue
            seen.add(normalized)
            if (seen.size >= MAX_COUNT) break
        }
        return seen.toList()
    }
}
