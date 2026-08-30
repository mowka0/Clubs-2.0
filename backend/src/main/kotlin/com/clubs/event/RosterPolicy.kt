package com.clubs.event

import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.reputation.ReputationPolicy

/**
 * Чистая политика цены отказа от места (V83, решение PO 2026-08-21). Единственный источник
 * истины для двух вызывающих: [Stage2Service.declineParticipation] (реально списывает) и
 * [EventMapper.toDetailDto] (называет цену на экране ДО открытия диалога).
 *
 * Раньше отказ внутри порога 4 ч был просто запрещён — человек молча не приходил и получал
 * −200 (no_show). Теперь отказ возможен всегда, но платный, и цена зависит от двух вещей:
 * закрылся ли состав и нашлась ли замена в очереди. См. docs/modules/event-roster-threshold.md § 4.
 */
object RosterPolicy {

    /**
     * Вид репутации за отказ от места ПРЯМО СЕЙЧАС, либо null — если отказ бесплатный.
     *
     * @param isOpenEvent открытая встреча (🌊) целиком вне репутации — цены нет никогда
     * @param heldSlot участник держал место в составе (waitlisted никого не держит → бесплатно)
     * @param rosterClosed состав закрыт (у 🎟 — после закрытия набора; у ⚡ — всегда)
     * @param withinDeclineCutoff до встречи осталось ≤ events.late-decline-threshold-minutes (4 ч)
     * @param hasReplacement в очереди есть кто-то, кто немедленно займёт освободившееся место
     */
    fun declineKind(
        isOpenEvent: Boolean,
        heldSlot: Boolean,
        rosterClosed: Boolean,
        withinDeclineCutoff: Boolean,
        hasReplacement: Boolean
    ): ReputationKind? = when {
        isOpenEvent || !heldSlot -> null
        // Набор ещё идёт: состав не объявлен, никто на человека не рассчитывал — выход бесплатный.
        !rosterClosed -> null
        // Замена есть и время подготовиться у неё тоже — дыры в составе не остаётся.
        hasReplacement && !withinDeclineCutoff -> null
        hasReplacement -> ReputationKind.late_decline_covered
        withinDeclineCutoff -> ReputationKind.late_decline_uncovered
        else -> ReputationKind.abandoned_slot
    }

    /** Та же цена, но положительным числом для текста «спишется N очков»; 0 = бесплатно. */
    fun declineCostPoints(
        isOpenEvent: Boolean,
        heldSlot: Boolean,
        rosterClosed: Boolean,
        withinDeclineCutoff: Boolean,
        hasReplacement: Boolean
    ): Int = declineKind(isOpenEvent, heldSlot, rosterClosed, withinDeclineCutoff, hasReplacement)
        ?.let { -ReputationPolicy.pointsFor(it) } ?: 0
}
