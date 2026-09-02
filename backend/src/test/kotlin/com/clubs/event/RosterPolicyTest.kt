package com.clubs.event

import com.clubs.generated.jooq.enums.ReputationKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Цена отказа v2 (docs/modules/event-formats.md § 6, AC-7): одна формула вместо ветвления по
 * формату. Обещанное — минимум, если он задан; без минимума — каждое занятое место.
 * Политика чистая, поэтому проверяется целиком: шесть клеток таблицы и два нулевых случая.
 */
class RosterPolicyTest {

    private fun kind(
        heldSlot: Boolean = true,
        rosterClosed: Boolean = true,
        withinCutoff: Boolean = false,
        hasReplacement: Boolean = false,
        staysAtThreshold: Boolean = false,
        isOpenEvent: Boolean = false
    ): ReputationKind? = RosterPolicy.declineKind(
        DeclineSituation(
            isOpenEvent = isOpenEvent,
            heldSlot = heldSlot,
            rosterClosed = rosterClosed,
            withinDeclineCutoff = withinCutoff,
            hasReplacement = hasReplacement,
            staysAtThreshold = staysAtThreshold
        )
    )

    @Test
    fun `набор ещё идёт — выход бесплатный при любых обстоятельствах`() {
        assertNull(kind(rosterClosed = false))
        assertNull(kind(rosterClosed = false, withinCutoff = true))
        assertNull(kind(rosterClosed = false, withinCutoff = true, staysAtThreshold = false))
    }

    @Test
    fun `замена в очереди есть — 0 далеко от встречи и минус 50 внутри порога`() {
        assertNull(kind(hasReplacement = true))
        assertEquals(ReputationKind.late_decline_covered, kind(hasReplacement = true, withinCutoff = true))
        // Новая клетка v2: с минимумом очередь тоже бывает (потолок есть у любой встречи), и
        // замена внутри порога стоит −50, а не 0 «потому что минимум держится».
        assertEquals(
            ReputationKind.late_decline_covered,
            kind(hasReplacement = true, withinCutoff = true, staysAtThreshold = true)
        )
    }

    @Test
    fun `замены нет, состав остаётся не ниже обещанного — бесплатно даже внутри порога`() {
        assertNull(kind(staysAtThreshold = true))
        assertNull(kind(staysAtThreshold = true, withinCutoff = true))
    }

    @Test
    fun `замены нет, состав падает ниже обещанного — минус 100, внутри порога минус 150`() {
        assertEquals(ReputationKind.abandoned_slot, kind())
        assertEquals(ReputationKind.late_decline_uncovered, kind(withinCutoff = true))
    }

    @Test
    fun `очередь и открытая встреча не платят никогда`() {
        // Waitlisted никого не держит: выход из очереди бесплатен на любом этапе.
        assertNull(kind(heldSlot = false, withinCutoff = true))
        // Открытая целиком вне репутации (V62, PO 2026-07-21).
        assertNull(kind(isOpenEvent = true, withinCutoff = true))
    }

    @Test
    fun `staysAtThreshold — минимум обещанное, без минимума обещание каждое место`() {
        // Минимум 4: из пяти уйти можно, из четырёх — уже ниже черты.
        assertTrue(RosterPolicy.staysAtThreshold(confirmedBefore = 5, minParticipants = 4))
        assertFalse(RosterPolicy.staysAtThreshold(confirmedBefore = 4, minParticipants = 4))
        // Без минимума правая часть равна текущему составу — условие ложно всегда.
        assertFalse(RosterPolicy.staysAtThreshold(confirmedBefore = 5, minParticipants = null))
        assertFalse(RosterPolicy.staysAtThreshold(confirmedBefore = 1, minParticipants = null))
    }

    @Test
    fun `любой отказ дешевле молчаливой неявки`() {
        val noShow = com.clubs.reputation.ReputationPolicy.pointsFor(ReputationKind.no_show)
        listOf(
            ReputationKind.abandoned_slot,
            ReputationKind.late_decline_covered,
            ReputationKind.late_decline_uncovered
        ).forEach { declineKind ->
            // Иначе «пропасть молча» станет выгоднее честного предупреждения — ровно тот эффект,
            // из-за которого сняли прежний запрет отказа внутри 4 часов.
            assert(com.clubs.reputation.ReputationPolicy.pointsFor(declineKind) > noShow) {
                "$declineKind должен быть дешевле no_show"
            }
        }
    }

    @Test
    fun `declineCostPoints отдаёт положительное число для текста и ноль для бесплатного отказа`() {
        assertEquals(
            150,
            RosterPolicy.declineCostPoints(
                DeclineSituation(
                    isOpenEvent = false, heldSlot = true, rosterClosed = true,
                    withinDeclineCutoff = true, hasReplacement = false
                )
            )
        )
        assertEquals(
            0,
            RosterPolicy.declineCostPoints(
                DeclineSituation(
                    isOpenEvent = false, heldSlot = true, rosterClosed = false,
                    withinDeclineCutoff = true, hasReplacement = false
                )
            )
        )
    }

    // ---- последствие отказа (§ 6, таблица declineConsequence) ----

    private fun consequence(
        isOpenEvent: Boolean = false,
        rosterClosed: Boolean = true,
        waitlistedCount: Int = 0,
        confirmedCount: Int = 3,
        minParticipants: Int? = null,
        rosterDecided: Boolean = false
    ) = RosterPolicy.declineConsequence(
        isOpenEvent, rosterClosed, waitlistedCount, confirmedCount, minParticipants, rosterDecided
    )

    @Test
    fun `последствие — null на наборе и open у открытой в фазе подтверждения`() {
        assertNull(consequence(rosterClosed = false))
        assertNull(consequence(rosterClosed = false, minParticipants = 4, confirmedCount = 1))
        assertEquals(DeclineConsequence.OPEN, consequence(isOpenEvent = true))
        assertNull(consequence(isOpenEvent = true, rosterClosed = false))
    }

    @Test
    fun `последствие — первое совпавшее по порядку таблицы`() {
        // Очередь важнее всего остального: место сразу займут.
        assertEquals(DeclineConsequence.REPLACED, consequence(waitlistedCount = 1, confirmedCount = 1, minParticipants = 4))
        // Последний в составе — встреча отменится.
        assertEquals(DeclineConsequence.ROSTER_EMPTY, consequence(confirmedCount = 1, minParticipants = 4))
        // Минимум пробивается и организатор ещё не решал.
        assertEquals(DeclineConsequence.BELOW_MINIMUM, consequence(confirmedCount = 4, minParticipants = 4))
        // После «Проводим» — организатор уже решил, место просто пустует.
        assertEquals(DeclineConsequence.SEAT_EMPTY, consequence(confirmedCount = 4, minParticipants = 4, rosterDecided = true))
        // Минимум держится и без этого человека.
        assertEquals(DeclineConsequence.SEAT_EMPTY, consequence(confirmedCount = 5, minParticipants = 4))
        // Без минимума — место пустует.
        assertEquals(DeclineConsequence.SEAT_EMPTY, consequence(confirmedCount = 3))
    }
}
