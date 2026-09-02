package com.clubs.event

import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.reputation.ReputationPolicy
import com.fasterxml.jackson.annotation.JsonValue
import java.time.OffsetDateTime

/**
 * Обстоятельства отказа от места — всё, что нужно [RosterPolicy], одним объектом. Собирается
 * в двух местах: [Stage2Service.declineParticipation] (реально списывает) и
 * [EventMapper.toDetailDto] (называет цену на экране ДО открытия диалога).
 */
data class DeclineSituation(
    /** Открытая встреча: мест нет, репутации нет. */
    val isOpenEvent: Boolean,
    /** Участник держал место в составе; `waitlisted` никого не держит. */
    val heldSlot: Boolean,
    /** Состав закрыт: пока идёт набор, на человека никто не рассчитывал. */
    val rosterClosed: Boolean,
    /** До встречи осталось ≤ `events.late-decline-threshold-minutes` (4 ч). */
    val withinDeclineCutoff: Boolean,
    /** В очереди есть кто-то, кто немедленно займёт освободившееся место. */
    val hasReplacement: Boolean = false,
    /** После этого отказа состав останется не меньше обещанного — см. [RosterPolicy.staysAtThreshold]. */
    val staysAtThreshold: Boolean = false
)

/**
 * Что случится, если участник из состава откажется прямо сейчас (V86, § 6 спеки). Называет
 * СЕРВЕР — клиент не выводит последствие из условий, иначе копия правил разъедется с рантаймом
 * (урок V83). Порядок значений — порядок проверки.
 */
enum class DeclineConsequence(@get:JsonValue val wire: String) {
    /** Открытая встреча — репутация не пострадает. */
    OPEN("open"),
    /** Место сразу займёт первый из очереди. */
    REPLACED("replaced"),
    /** Участник последний в составе: освободит место — встреча отменится. */
    ROSTER_EMPTY("roster_empty"),
    /** Состав станет ниже минимума — организатор решит, состоится ли встреча. */
    BELOW_MINIMUM("below_minimum"),
    /** Заменить некем, место останется пустым (в том числе после «Проводим»). */
    SEAT_EMPTY("seat_empty")
}

/**
 * Чистая политика цены отказа от места (v2, решение PO 2026-09-02). Единственный источник истины
 * для экрана и для списания. Одна формула вместо ветвления по формату:
 *
 * > Отказ из закрытого состава бесплатен, если есть замена в очереди. Замены нет — бесплатен,
 * > если после отказа состав остаётся не меньше ОБЕЩАННОГО; иначе платный. Обещанное — минимум,
 * > если он задан; без минимума — каждое занятое место.
 *
 * Раньше отказ внутри порога 4 ч был просто запрещён — человек молча не приходил и получал
 * −200 (no_show). Теперь отказ возможен всегда, но платный. См. docs/modules/event-formats.md § 6.
 */
object RosterPolicy {

    /** Вид репутации за отказ от места ПРЯМО СЕЙЧАС, либо null — если отказ бесплатный. */
    fun declineKind(situation: DeclineSituation): ReputationKind? = with(situation) {
        when {
            isOpenEvent || !heldSlot -> null
            // Набор ещё идёт: состав не объявлен, никто на человека не рассчитывал.
            !rosterClosed -> null
            // Замена есть: дыры в составе не остаётся, платно только если ей не дали времени собраться.
            hasReplacement -> if (withinDeclineCutoff) ReputationKind.late_decline_covered else null
            // Обещанное держится и без этого человека — ущерба нет.
            staysAtThreshold -> null
            withinDeclineCutoff -> ReputationKind.late_decline_uncovered
            else -> ReputationKind.abandoned_slot
        }
    }

    /** Та же цена, но положительным числом для текста «спишется N очков»; 0 = бесплатно. */
    fun declineCostPoints(situation: DeclineSituation): Int =
        declineKind(situation)?.let { -ReputationPolicy.pointsFor(it) } ?: 0

    /**
     * Остаётся ли состав после одного отказа не меньше обещанного. Без минимума правая часть
     * равна текущему составу, условие ложно всегда — это «каждое место — обещание».
     */
    fun staysAtThreshold(confirmedBefore: Int, minParticipants: Int?): Boolean =
        confirmedBefore - 1 >= (minParticipants ?: confirmedBefore)

    /**
     * Последствие отказа для диалога — первое совпавшее (§ 6 спеки). null, пока идёт набор:
     * диалог без последствий. Считается на уровне события (для любого, кто держит место);
     * выход из очереди клиент отличает сам по своему статусу.
     */
    fun declineConsequence(
        isOpenEvent: Boolean,
        rosterClosed: Boolean,
        waitlistedCount: Int,
        confirmedCount: Int,
        minParticipants: Int?,
        rosterDecided: Boolean
    ): DeclineConsequence? = when {
        isOpenEvent -> if (rosterClosed) DeclineConsequence.OPEN else null
        !rosterClosed -> null
        waitlistedCount > 0 -> DeclineConsequence.REPLACED
        confirmedCount <= 1 -> DeclineConsequence.ROSTER_EMPTY
        minParticipants != null && confirmedCount - 1 < minParticipants && !rosterDecided ->
            DeclineConsequence.BELOW_MINIMUM
        else -> DeclineConsequence.SEAT_EMPTY
    }
}

/**
 * Тайминги набора — одна арифметика на сервис, маппер и гарды даты, чтобы дедлайн и момент
 * предупреждения нигде не считались чуть по-разному.
 */
object RosterSchedule {

    /** Момент закрытия набора: старт минус интервал (свой у события или глобальный дефолт). */
    fun deadline(eventDatetime: OffsetDateTime, stage2LeadMinutes: Int?, defaultLeadMinutes: Long): OffsetDateTime =
        eventDatetime.minusMinutes((stage2LeadMinutes ?: defaultLeadMinutes.toInt()).toLong())

    /** Момент предупреждения о недоборе (②): за [warningMinutes] до дедлайна. */
    fun warningAt(deadline: OffsetDateTime, warningMinutes: Long): OffsetDateTime =
        deadline.minusMinutes(warningMinutes)

    /**
     * Значение отметки предупреждения при создании и правке (§ 3.2): минимума нет — отметка не
     * нужна; момент уже в прошлом — «израсходовано» (DM не будет); момент впереди — тик поставит сам.
     */
    fun initialWarningMark(
        minParticipants: Int?,
        deadline: OffsetDateTime,
        warningMinutes: Long,
        now: OffsetDateTime
    ): OffsetDateTime? = when {
        minParticipants == null -> null
        !warningAt(deadline, warningMinutes).isAfter(now) -> now
        else -> null
    }
}
