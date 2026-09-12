package com.clubs.common.util

import kotlin.math.abs

/** Суммы в DM и чат-постах: «1 000 ₽», «833,33 ₽». Копейки показываются только когда они есть. */
object Money {

    // Обычный пробел между разрядами: суммы короткие, а неразрывный ломал бы поиск по логам и тестам.
    private const val THOUSANDS_SEPARATOR = ' '

    fun rub(kopecks: Long): String {
        val sign = if (kopecks < 0) "−" else ""
        val rubles = abs(kopecks) / 100
        val kop = abs(kopecks) % 100
        val grouped = rubles.toString().reversed().chunked(3).joinToString(THOUSANDS_SEPARATOR.toString()).reversed()
        return if (kop == 0L) "$sign$grouped ₽" else "$sign$grouped,${kop.toString().padStart(2, '0')} ₽"
    }
}
