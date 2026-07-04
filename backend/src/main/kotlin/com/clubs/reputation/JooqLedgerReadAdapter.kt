package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.REPUTATION_LEDGER
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * jOOQ-реализация [LedgerReadPort]. Читает `reputation_ledger ⋈ clubs.owner_id`, классифицируя
 * KEPT-исходы ПО KIND (никогда по points — бэкфилл V18 записал устаревшие величины, так что points
 * через эту границу врут). `skladchina_paid` намеренно исключён из kept-набора: его может
 * проставить владелец, и он никогда не должен попадать во входы L3.
 */
@Component
class JooqLedgerReadAdapter(private val dsl: DSLContext) : LedgerReadPort {

    override fun footprintByUser(userIds: Collection<UUID>): Map<UUID, Map<UUID, Int>> {
        if (userIds.isEmpty()) return emptyMap()
        val l = REPUTATION_LEDGER
        val ownerClubCount = DSL.countDistinct(l.CLUB_ID)
        return dsl.select(l.USER_ID, CLUBS.OWNER_ID, ownerClubCount)
            .from(l)
            .join(CLUBS).on(CLUBS.ID.eq(l.CLUB_ID))
            .where(
                l.USER_ID.`in`(userIds.toSet())
                    .and(l.KIND.`in`(ReputationKind.ironclad, ReputationKind.spontaneous)),
            )
            .groupBy(l.USER_ID, CLUBS.OWNER_ID)
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! to (it.value3() ?: 0) })
            .mapValues { (_, pairs) -> pairs.toMap() }
    }
}
