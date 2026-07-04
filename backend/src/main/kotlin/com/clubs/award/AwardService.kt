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
 * Локальные для клуба награды (member admin profile S2). Организатор выдаёт участнику
 * косметическое признание «как интересы» (выбрать прошлую награду или ввести новый эмодзи + текст).
 * Награды публичны для всех участников (R3) и никогда не трогают репутацию/XP/ранг (R4) —
 * никаких хуков в ledger/репутацию здесь намеренно нет.
 *
 * Авторизация вызывающего (организатор) декларативна на контроллере (@RequiresOrganizer);
 * этот сервис проверяет только бизнес-правила: цель должна быть участником, лимит на участника,
 * отсутствие дубля названия.
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

    /**
     * Награды всех участников [clubId], сгруппированные по user id — один запрос на весь ростер,
     * чтобы каждая карточка могла показать свои чипы (R3) без N+1. Участники без наград просто
     * отсутствуют в карте.
     */
    @Transactional(readOnly = true)
    fun getClubAwardsByMember(clubId: UUID): Map<UUID, List<AwardDto>> =
        awardRepository.findByClub(clubId).groupBy({ it.userId }, { mapper.toDto(it) })

    @Transactional
    fun grant(clubId: UUID, targetUserId: UUID, emoji: String, label: String, callerId: UUID): AwardDto {
        // Награда бессмысленна для не-участника — требуем существующий membership в этом клубе.
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
        // Удаление в рамках scope (awardId должен принадлежать clubId+userId); 0 строк = не тот клуб/участник или уже удалено.
        val rows = awardRepository.delete(awardId, clubId, targetUserId)
        if (rows == 0) throw NotFoundException("Награда не найдена")
        log.info("Award revoked: clubId={} targetUserId={} by={} awardId={}", clubId, targetUserId, callerId, awardId)
    }

    companion object {
        // Максимум наград на одного участника клуба.
        const val MAX_AWARDS_PER_MEMBER = 6
        // Сколько прошлых наград клуба показывать как подсказки при выдаче новой.
        private const val MAX_SUGGESTIONS = 20
        private val WHITESPACE = Regex("\\s+")
    }
}
