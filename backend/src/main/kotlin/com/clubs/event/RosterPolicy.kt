package com.clubs.event

import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.reputation.ReputationPolicy

/**
 * Обстоятельства отказа от места — всё, что нужно [RosterPolicy], одним объектом. Собирается
 * в двух местах: [Stage2Service.declineParticipation] (реально списывает) и
 * [EventMapper.toDetailDto] (называет цену на экране ДО открытия диалога).
 */
data class DeclineSituation(
    val format: EventFormat,
    /** Участник держал место в составе; `waitlisted` никого не держит. */
    val heldSlot: Boolean,
    /** Состав закрыт: пока идёт набор, на человека никто не рассчитывал. */
    val rosterClosed: Boolean,
    /** До встречи осталось ≤ `events.late-decline-threshold-minutes` (4 ч). */
    val withinDeclineCutoff: Boolean,
    /** MAX: в очереди есть кто-то, кто немедленно займёт освободившееся место. */
    val hasReplacement: Boolean = false,
    /** MIN: после этого отказа в составе останется не меньше порога. */
    val staysAtThreshold: Boolean = false
)

/**
 * Чистая политика цены отказа от места (V85, решения PO 2026-08-21 и 2026-08-31). Единственный
 * источник истины для экрана и для списания.
 *
 * Цена выводится из смысла формата, а не из общей матрицы:
 *  - **MIN** — ущерб в срыве встречи: бесплатно, пока состав остаётся ≥ порога;
 *  - **MAX** — ущерб в сожжённом месте: бесплатно, если в очереди есть замена;
 *  - **ANY** — репутации нет вовсе.
 *
 * Раньше отказ внутри порога 4 ч был просто запрещён — человек молча не приходил и получал
 * −200 (no_show). Теперь отказ возможен всегда, но платный. См. docs/modules/event-formats.md § 4.
 */
object RosterPolicy {

    /** Вид репутации за отказ от места ПРЯМО СЕЙЧАС, либо null — если отказ бесплатный. */
    fun declineKind(situation: DeclineSituation): ReputationKind? = with(situation) {
        when {
            format == EventFormat.ANY || !heldSlot -> null
            // Набор ещё идёт: состав не объявлен, никто на человека не рассчитывал.
            !rosterClosed -> null
            // Порог держится и без этого человека — встреча состоится, ущерба нет. Очередь у MIN
            // недостижима (верхней границы нет), поэтому hasReplacement здесь не читается.
            format == EventFormat.MIN -> when {
                staysAtThreshold -> null
                withinDeclineCutoff -> ReputationKind.late_decline_uncovered
                else -> ReputationKind.abandoned_slot
            }
            // MAX: замена есть и время подготовиться у неё тоже — дыры в составе не остаётся.
            hasReplacement && !withinDeclineCutoff -> null
            hasReplacement -> ReputationKind.late_decline_covered
            withinDeclineCutoff -> ReputationKind.late_decline_uncovered
            else -> ReputationKind.abandoned_slot
        }
    }

    /** Та же цена, но положительным числом для текста «спишется N очков»; 0 = бесплатно. */
    fun declineCostPoints(situation: DeclineSituation): Int =
        declineKind(situation)?.let { -ReputationPolicy.pointsFor(it) } ?: 0
}
