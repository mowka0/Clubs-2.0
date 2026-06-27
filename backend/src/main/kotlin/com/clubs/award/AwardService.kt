package com.clubs.award

import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Club-local awards (member admin profile S2). The organizer grants cosmetic recognition to a member
 * «как интересы» (pick a past award or type a fresh emoji + label). Awards are public to all members
 * (R3) and never touch reputation/XP/rank (R4) — there are no ledger/reputation hooks here by design.
 *
 * Caller authorization (organizer) is declarative on the controller (@RequiresOrganizer); this service
 * only enforces the business rules: target must be a member, the per-member cap, and no duplicate label.
 */
@Service
class AwardService(
    private val awardRepository: AwardRepository,
    private val membershipRepository: MembershipRepository,
    private val mapper: AwardMapper
) {
    private val log = LoggerFactory.getLogger(AwardService::class.java)

    @Transactional(readOnly = true)
    fun getMemberAwards(clubId: UUID, userId: UUID): List<AwardDto> =
        awardRepository.findByMember(clubId, userId).map(mapper::toDto)

    @Transactional(readOnly = true)
    fun getSuggestions(clubId: UUID): List<AwardSuggestionDto> =
        awardRepository.findSuggestions(clubId, MAX_SUGGESTIONS).map(mapper::toDto)

    @Transactional
    fun grant(clubId: UUID, targetUserId: UUID, emoji: String, label: String, callerId: UUID): AwardDto {
        // The award is meaningless for a non-member — require an existing membership in this club.
        membershipRepository.findByUserAndClub(targetUserId, clubId)
            ?: throw NotFoundException("Участник не найден в этом клубе")

        val cleanEmoji = emoji.trim()
        val cleanLabel = label.trim().replace(WHITESPACE, " ")
        if (cleanEmoji.isEmpty()) throw ValidationException("Укажите эмодзи награды")
        if (cleanLabel.isEmpty()) throw ValidationException("Укажите название награды")

        if (awardRepository.countByMember(clubId, targetUserId) >= MAX_AWARDS_PER_MEMBER) {
            throw ValidationException("Максимум $MAX_AWARDS_PER_MEMBER наград на участника")
        }
        if (awardRepository.existsByLabel(clubId, targetUserId, cleanLabel)) {
            throw ValidationException("Такая награда уже выдана")
        }

        val saved = awardRepository.insert(
            Award(
                id = UUID.randomUUID(),
                clubId = clubId,
                userId = targetUserId,
                emoji = cleanEmoji,
                label = cleanLabel,
                awardedBy = callerId,
                awardedAt = OffsetDateTime.now()
            )
        )
        log.info("Award granted: clubId={} targetUserId={} by={} label={}", clubId, targetUserId, callerId, cleanLabel)
        return mapper.toDto(saved)
    }

    @Transactional
    fun revoke(clubId: UUID, targetUserId: UUID, awardId: UUID, callerId: UUID) {
        // Scoped delete (awardId must belong to clubId+userId); 0 rows = wrong club/member or already gone.
        val rows = awardRepository.delete(awardId, clubId, targetUserId)
        if (rows == 0) throw NotFoundException("Награда не найдена")
        log.info("Award revoked: clubId={} targetUserId={} by={} awardId={}", clubId, targetUserId, callerId, awardId)
    }

    companion object {
        const val MAX_AWARDS_PER_MEMBER = 6
        private const val MAX_SUGGESTIONS = 20
        private val WHITESPACE = Regex("\\s+")
    }
}
