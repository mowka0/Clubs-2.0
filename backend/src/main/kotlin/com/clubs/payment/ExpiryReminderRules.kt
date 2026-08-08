package com.clubs.payment

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Правила напоминаний об истечении подписки: на каких порогах слать DM и какой фразой назвать срок.
 * Вынесено из планировщика — это чистые функции без БД и Telegram, они и покрыты юнит-тестами.
 */
internal object ExpiryReminderRules {

    // Сколько дней до конца окна доступа остаётся, когда участник получает DM. Порогов ровно два
    // (PO 2026-08-08: раньше напоминание уходило на КАЖДОМ тике крона — при дневном кроне три
    // одинаковых DM подряд). Список строго по убыванию: thresholdFor берёт из него последний
    // подходящий, то есть ближайший наступивший порог.
    private val THRESHOLDS_DAYS = listOf(3, 1)

    // Горизонт выборки кандидатов (в днях) — на сутки шире самого дальнего порога. Пороги считаются
    // в календарных днях по МСК, а запрос отбирает строки по абсолютному времени: подписка, до конца
    // которой ровно 3 календарных дня, но по времени суток чуть дальше, в окно «now + 3 дня» бы не
    // попала и напоминание за 3 дня было бы пропущено.
    val SCAN_HORIZON_DAYS: Long = THRESHOLDS_DAYS.first() + 1L

    // Дни считаем московскими сутками: в DM «завтра» и «через 3 дня» должны совпадать с тем, как
    // читатель видит дату (остальные уведомления тоже рендерятся в МСК), а не с границей суток UTC.
    private val DISPLAY_ZONE = ZoneId.of("Europe/Moscow")

    /** Сколько календарных дней (по МСК) осталось до конца окна доступа; 0 = истекает сегодня. */
    fun daysLeft(now: OffsetDateTime, expiresAt: OffsetDateTime): Long = ChronoUnit.DAYS.between(
        now.atZoneSameInstant(DISPLAY_ZONE).toLocalDate(),
        expiresAt.atZoneSameInstant(DISPLAY_ZONE).toLocalDate()
    )

    /** Порог, которому отвечает сегодняшний тик (3 или 1), либо null — напоминать ещё рано. */
    fun thresholdFor(daysLeft: Long): Int? = THRESHOLDS_DAYS.lastOrNull { daysLeft <= it }

    /** Отправленный [lastSentThreshold] закрывает свой порог; открытыми остаются только более поздние. */
    fun isDue(threshold: Int, lastSentThreshold: Int?): Boolean =
        lastSentThreshold == null || lastSentThreshold > threshold

    /**
     * Срок в тексте DM берётся из фактического остатка, а не из порога: если тик крона был пропущен
     * (простой, ре-деплой), напоминание уходит позже — и «через 3 дня» стало бы неправдой.
     */
    fun deadlinePhrase(daysLeft: Long): String = when {
        daysLeft <= 0L -> "сегодня"
        daysLeft == 1L -> "завтра"
        else -> "через $daysLeft дня"
    }
}
