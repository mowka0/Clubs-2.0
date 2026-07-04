package com.clubs.interest

import java.text.Normalizer

/**
 * Канонизирует сырую строку интереса, чтобы дубликаты схлопывались в одну строку
 * словаря: NFC → срезать обрамляющие кавычки → trim → схлопнуть внутренние пробелы →
 * lower → ё→е. Многословные фразы сохраняются (разделитель — запятая, её обрабатывает
 * вызывающий); в один пробел сжимаются только внутренние цепочки пробелов.
 */
object InterestNormalizer {

    // Максимальная длина нормализованного интереса (лишнее обрезается)
    const val MAX_LEN = 40
    // Максимум интересов в списке пользователя (normalizeList режет по этому лимиту)
    const val MAX_COUNT = 15
    // Минимальная длина поискового запроса для подсказок интересов (suggest)
    const val MIN_QUERY_LEN = 2

    // Цепочки пробельных символов — схлопываются в один пробел
    private val WHITESPACE = Regex("\\s+")
    // Кавычки, срезаемые по краям токена
    private val QUOTES = charArrayOf('"', '\'', '«', '»', '“', '”', '‘', '’', '`')

    /** Нормализует один токен; возвращает null, если он схлопнулся в пустоту. */
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

    /** Нормализует список: выбрасывает пустые, дедуплицирует (с сохранением порядка), режет по лимиту. */
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
