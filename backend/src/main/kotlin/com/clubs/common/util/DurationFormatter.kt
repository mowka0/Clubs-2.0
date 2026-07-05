package com.clubs.common.util

/**
 * Форматирует длительность, заданную в минутах, в короткий русский текст для сообщений
 * пользователю: «4 ч», «1 ч 30 мин», «45 мин». Сокращения «ч»/«мин» по числу не склоняются,
 * поэтому корректны при любом значении. Заменяет прежний `минуты / 60` в тексте ошибки, который
 * из-за целочисленного деления терял остаток (1 мин → «0 ч», 90 мин → «1 ч»).
 */
object DurationFormatter {

    // Минут в часе — для разбиения длительности на часы и остаток.
    private const val MINUTES_PER_HOUR = 60L

    /** Человекочитаемая длительность из целого числа минут (ожидается неотрицательное значение). */
    fun formatMinutes(totalMinutes: Long): String {
        val hours = totalMinutes / MINUTES_PER_HOUR
        val minutes = totalMinutes % MINUTES_PER_HOUR
        return when {
            hours > 0 && minutes > 0 -> "$hours ч $minutes мин"
            hours > 0 -> "$hours ч"
            else -> "$minutes мин"
        }
    }
}
