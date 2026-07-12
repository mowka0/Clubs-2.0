package com.clubs.club

import com.clubs.common.auth.ClubManagerGuard
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
    private val clubManagerGuard: ClubManagerGuard,
    private val membershipRepository: MembershipRepository,
    private val transactionRepository: TransactionRepository
) {

    fun getFinances(clubId: UUID, userId: UUID): FinancesDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        // Менеджерский гейт (co-organizers), синхронно с @RequiresClubManager на контроллере.
        clubManagerGuard.requireManager(club, userId)

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
