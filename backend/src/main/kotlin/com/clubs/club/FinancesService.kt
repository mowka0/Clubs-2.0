package com.clubs.club

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.membership.MembershipRepository
import com.clubs.payment.TransactionRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

private const val PLATFORM_FEE_PERCENT = 20
private const val ORGANIZER_SHARE_PERCENT = 100 - PLATFORM_FEE_PERCENT

@Service
class FinancesService(
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val transactionRepository: TransactionRepository
) {

    fun getFinances(clubId: UUID, userId: UUID): FinancesDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club organizer can view finances")

        val startOfMonth = OffsetDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0)

        val activeMembers = membershipRepository.countActiveByClubId(clubId)
        val monthlyRevenue = transactionRepository.sumCompletedSubscriptionRevenueSince(clubId, startOfMonth)

        val organizerShare = monthlyRevenue * ORGANIZER_SHARE_PERCENT / 100
        val platformFee = monthlyRevenue - organizerShare

        return FinancesDto(
            activeMembers = activeMembers,
            monthlyRevenue = monthlyRevenue,
            organizerShare = organizerShare,
            platformFee = platformFee,
            organizerSharePct = BigDecimal("$ORGANIZER_SHARE_PERCENT.00"),
            platformFeePct = BigDecimal("$PLATFORM_FEE_PERCENT.00")
        )
    }
}
