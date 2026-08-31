package com.clubs.event

import com.clubs.generated.jooq.enums.ReputationKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Таблицы цены отказа (docs/modules/event-formats.md § 4). Политика чистая, поэтому проверяется
 * целиком — оба формата с лимитом и формат без него.
 *
 * Ключевое различие: у MAX ущерб в сожжённом месте (спасает замена из очереди), у MIN — в срыве
 * встречи (спасает то, что состав остаётся не ниже порога).
 */
class RosterPolicyTest {

    private fun max(
        heldSlot: Boolean = true,
        rosterClosed: Boolean = true,
        withinCutoff: Boolean = false,
        hasReplacement: Boolean = false
    ): ReputationKind? = RosterPolicy.declineKind(
        DeclineSituation(
            format = EventFormat.MAX,
            heldSlot = heldSlot,
            rosterClosed = rosterClosed,
            withinDeclineCutoff = withinCutoff,
            hasReplacement = hasReplacement
        )
    )

    private fun min(
        heldSlot: Boolean = true,
        rosterClosed: Boolean = true,
        withinCutoff: Boolean = false,
        staysAtThreshold: Boolean = false
    ): ReputationKind? = RosterPolicy.declineKind(
        DeclineSituation(
            format = EventFormat.MIN,
            heldSlot = heldSlot,
            rosterClosed = rosterClosed,
            withinDeclineCutoff = withinCutoff,
            staysAtThreshold = staysAtThreshold
        )
    )

    @Test
    fun `набор ещё идёт — выход бесплатный при любых обстоятельствах`() {
        assertNull(max(rosterClosed = false, hasReplacement = false))
        assertNull(max(rosterClosed = false, withinCutoff = true))
        assertNull(min(rosterClosed = false, staysAtThreshold = false))
        assertNull(min(rosterClosed = false, withinCutoff = true))
    }

    @Test
    fun `MAX, состав закрыт, до встречи далеко — замена есть бесплатно, замены нет минус 100`() {
        assertNull(max(hasReplacement = true))
        assertEquals(ReputationKind.abandoned_slot, max(hasReplacement = false))
    }

    @Test
    fun `MAX внутри порога отказа — минус 50 с заменой и минус 150 без неё`() {
        assertEquals(ReputationKind.late_decline_covered, max(withinCutoff = true, hasReplacement = true))
        assertEquals(ReputationKind.late_decline_uncovered, max(withinCutoff = true, hasReplacement = false))
    }

    @Test
    fun `MIN не платит, пока состав остаётся не ниже порога`() {
        // Верхней границы у формата нет, поэтому «лишний» участник уходит бесплатно даже
        // за четыре часа до встречи: встреча всё равно состоится.
        assertNull(min(staysAtThreshold = true))
        assertNull(min(staysAtThreshold = true, withinCutoff = true))
    }

    @Test
    fun `MIN платит, когда отказ роняет состав ниже порога`() {
        assertEquals(ReputationKind.abandoned_slot, min(staysAtThreshold = false))
        assertEquals(
            ReputationKind.late_decline_uncovered,
            min(staysAtThreshold = false, withinCutoff = true)
        )
    }

    @Test
    fun `очередь и формат без лимита не платят никогда`() {
        // Waitlisted никого не держит: выход из очереди бесплатен на любом этапе.
        assertNull(max(heldSlot = false, withinCutoff = true))
        assertNull(min(heldSlot = false, withinCutoff = true))
        // «Сколько придёт» целиком вне репутации (V62, PO 2026-07-21).
        assertNull(
            RosterPolicy.declineKind(
                DeclineSituation(
                    format = EventFormat.ANY, heldSlot = true,
                    rosterClosed = true, withinDeclineCutoff = true
                )
            )
        )
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
                    format = EventFormat.MAX, heldSlot = true, rosterClosed = true,
                    withinDeclineCutoff = true, hasReplacement = false
                )
            )
        )
        assertEquals(
            0,
            RosterPolicy.declineCostPoints(
                DeclineSituation(
                    format = EventFormat.MAX, heldSlot = true, rosterClosed = false,
                    withinDeclineCutoff = true, hasReplacement = false
                )
            )
        )
    }
}
