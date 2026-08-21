package com.clubs.event

import com.clubs.generated.jooq.enums.ReputationKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Таблица цены отказа (docs/modules/event-roster-threshold.md § 4, решение PO 2026-08-21).
 * Политика чистая, поэтому проверяется целиком — все пять случаев и оба формата-исключения.
 */
class RosterPolicyTest {

    private fun kind(
        isOpenEvent: Boolean = false,
        heldSlot: Boolean = true,
        rosterClosed: Boolean = true,
        withinCutoff: Boolean = false,
        hasReplacement: Boolean = false
    ): ReputationKind? = RosterPolicy.declineKind(
        isOpenEvent = isOpenEvent,
        heldSlot = heldSlot,
        rosterClosed = rosterClosed,
        withinDeclineCutoff = withinCutoff,
        hasReplacement = hasReplacement
    )

    @Test
    fun `набор ещё идёт — выход бесплатный при любой очереди`() {
        assertNull(kind(rosterClosed = false, hasReplacement = false))
        assertNull(kind(rosterClosed = false, hasReplacement = true))
        assertNull(kind(rosterClosed = false, withinCutoff = true))
    }

    @Test
    fun `состав закрыт, до встречи далеко — замена есть бесплатно, замены нет минус 100`() {
        assertNull(kind(hasReplacement = true))
        assertEquals(ReputationKind.abandoned_slot, kind(hasReplacement = false))
    }

    @Test
    fun `внутри порога отказа — минус 50 с заменой и минус 150 без неё`() {
        assertEquals(ReputationKind.late_decline_covered, kind(withinCutoff = true, hasReplacement = true))
        assertEquals(ReputationKind.late_decline_uncovered, kind(withinCutoff = true, hasReplacement = false))
    }

    @Test
    fun `очередь и открытая встреча не платят никогда`() {
        // Waitlisted никого не держит: выход из очереди бесплатен на любом этапе.
        assertNull(kind(heldSlot = false, withinCutoff = true))
        // Открытая встреча целиком вне репутации (V62, PO 2026-07-21).
        assertNull(kind(isOpenEvent = true, withinCutoff = true))
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
                isOpenEvent = false, heldSlot = true, rosterClosed = true,
                withinDeclineCutoff = true, hasReplacement = false
            )
        )
        assertEquals(
            0,
            RosterPolicy.declineCostPoints(
                isOpenEvent = false, heldSlot = true, rosterClosed = false,
                withinDeclineCutoff = true, hasReplacement = false
            )
        )
    }
}
