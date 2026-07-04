package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipEvent
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Append-only лог переходов жизненного цикла членства (вступил / вышел / вернулся / истёк).
 * Записывается из [JooqMembershipRepository] в той же транзакции, что и смена статуса, поэтому
 * лог никогда не может молча пропустить переход. Чтение (retention, tenure, L3) появится
 * в более поздних срезах.
 */
interface MembershipHistoryRepository {
    fun record(userId: UUID, clubId: UUID, event: MembershipEvent, occurredAt: OffsetDateTime)
}
