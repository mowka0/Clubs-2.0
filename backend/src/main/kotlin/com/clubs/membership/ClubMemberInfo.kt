package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Projection of MEMBERSHIPS x USERS x USER_CLUB_REPUTATION used by the club
 * members list. Lives in the membership module because it is built around a
 * membership row plus joined user/reputation fields.
 *
 * [status] + [subscriptionExpiresAt] drive the organizer dashboard buckets
 * (de-Stars Slice 2): `frozen` → «Ждут оплаты», `active` with expiry within a
 * week → «Скоро закончится», otherwise «Активные». They are raw here; the mapper
 * only exposes them to the organizer.
 */
data class ClubMemberInfo(
    val userId: UUID,
    val firstName: String?,
    val lastName: String?,
    val avatarUrl: String?,
    val role: MembershipRole,
    val joinedAt: OffsetDateTime,
    // Raw cache sibling (nullable when no reputation row); the "Новичок" threshold (outcomeCount)
    // is applied by the mapper. P1b shows Trust (computed in MemberService), not the raw index.
    val promiseFulfillmentPct: BigDecimal?,
    // Stage-2 confirmations to date. Distinguishes a finance-only member (0 confirmations → no
    // attendance track) from a no-show (confirmations > 0, 0% fulfillment) so the list hides the
    // misleading "Обещания 0%" for the former — parity with ProfilePage's hasActivity (F5-08).
    val totalConfirmations: Int?,
    val outcomeCount: Int,
    val status: MembershipStatus,
    val subscriptionExpiresAt: OffsetDateTime?
)
