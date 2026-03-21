package com.clubs.club

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.enums.TransactionStatus
import com.clubs.generated.jooq.enums.TransactionType
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.TRANSACTIONS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Service
class FinancesService(
    private val clubRepository: ClubRepository,
    private val dsl: DSLContext
) {

    fun getFinances(clubId: UUID, userId: UUID): FinancesDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club organizer can view finances")

        val now = OffsetDateTime.now()
        val startOfMonth = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0)

        val activeMembers = dsl.fetchCount(
            MEMBERSHIPS,
            MEMBERSHIPS.CLUB_ID.eq(clubId).and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
        )

        val monthlyRevenue = dsl
            .select(DSL.coalesce(DSL.sum(TRANSACTIONS.AMOUNT), DSL.`val`(0)))
            .from(TRANSACTIONS)
            .where(
                TRANSACTIONS.CLUB_ID.eq(clubId)
                    .and(TRANSACTIONS.TYPE.eq(TransactionType.subscription))
                    .and(TRANSACTIONS.STATUS.eq(TransactionStatus.completed))
                    .and(TRANSACTIONS.CREATED_AT.greaterOrEqual(startOfMonth))
            )
            .fetchOne(0, Int::class.java) ?: 0

        val organizerShare = (monthlyRevenue * 0.8).toInt()
        val platformFee = monthlyRevenue - organizerShare

        return FinancesDto(
            activeMembers = activeMembers,
            monthlyRevenue = monthlyRevenue,
            organizerShare = organizerShare,
            platformFee = platformFee,
            organizerSharePct = BigDecimal("80.00"),
            platformFeePct = BigDecimal("20.00")
        )
    }
}
