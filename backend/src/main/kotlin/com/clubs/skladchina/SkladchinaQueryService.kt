package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.debt.DebtMapper
import com.clubs.debt.DebtRepository
import com.clubs.event.EventRepository
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Read-сторона сбора: деталка, список активных по клубу, встречи для «Скинуться после встречи»,
 * состояние сбора у встречи. Мутаций нет — все остальные сервисы зависят от него ради response-DTO.
 */
@Service
class SkladchinaQueryService(
    private val skladchinaRepository: SkladchinaRepository,
    private val debtRepository: DebtRepository,
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val mapper: SkladchinaMapper,
    private val debtMapper: DebtMapper
) {

    @Transactional(readOnly = true)
    fun getSplittableEvents(clubId: UUID, callerId: UUID): List<SplittableEventDto> {
        requireMember(clubId, callerId)
        return skladchinaRepository.findSplittableEvents(
            clubId = clubId,
            notOlderThan = OffsetDateTime.now().minusDays(SkladchinaCreationService.MAX_EVENT_AGE_DAYS),
            minAttended = SkladchinaCreationService.MIN_ATTENDED
        ).map { SplittableEventDto(it.eventId, it.title, it.eventDatetime, it.attendedCount) }
    }

    /** Список активных сборов клуба для «Управления» (за @RequiresCapability(MANAGE_SKLADCHINA) на контроллере). */
    @Transactional(readOnly = true)
    fun getClubActiveSkladchinas(clubId: UUID, callerId: UUID): List<MySkladchinaListItemDto> {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        return skladchinaRepository.findAllByClubWithAggregates(clubId, includeCompleted = false, viewerId = callerId)
            .map {
                mapper.toMyFeedItemDto(
                    MySkladchinaFeedItem(
                        skladchina = it.skladchina, clubName = club.name, clubAvatarUrl = club.avatarUrl,
                        totals = it.totals, myDebtStatus = null, awaitingMyConfirmation = false
                    ),
                    callerId
                )
            }
    }

    /**
     * Деталка: любой активный участник клуба (и создатель). Скрытый от вызывающего сбор — 404,
     * как будто его нет. Список долгов отдаётся только создателю (маппер).
     */
    @Transactional(readOnly = true)
    fun getDetail(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val s = skladchinaRepository.findById(skladchinaId) ?: throw NotFoundException("Сбор не найден")
        if (s.isHiddenFrom(callerId)) throw NotFoundException("Сбор не найден")
        val club = clubRepository.findById(s.clubId) ?: throw NotFoundException("Club not found")
        val isCreator = s.creatorId == callerId
        if (!isCreator) requireMember(s.clubId, callerId)

        val now = OffsetDateTime.now()
        val debts = debtRepository.findBySkladchina(skladchinaId).map { debtMapper.toDto(it, now) }
        val event = s.eventId?.let { eventRepository.findById(it) }
        return mapper.toDetailDto(
            skladchina = s,
            clubName = club.name,
            clubAvatarUrl = club.avatarUrl,
            creatorName = userRepository.findById(s.creatorId)?.firstName ?: "",
            callerId = callerId,
            canCancel = isCreator || club.ownerId == callerId,
            totals = debtRepository.totals(skladchinaId),
            enrolledCount = if (s.enrollmentUntil != null) skladchinaRepository.countEnrolled(skladchinaId) else 0,
            myEnrolled = s.isEnrolling && skladchinaRepository.isEnrolled(skladchinaId, callerId),
            debts = debts,
            event = event
        )
    }

    /** Кнопка «Скинуться» на EventPage: активный сбор → открыть, собранный → уже собрано, иначе создать. */
    @Transactional(readOnly = true)
    fun findEventSplitState(eventId: UUID): EventSplitStateDto {
        val split = skladchinaRepository.findBlockingByEventId(eventId)
        return EventSplitStateDto(skladchinaId = split?.id, status = split?.status?.literal)
    }

    private fun requireMember(clubId: UUID, callerId: UUID) {
        if (!membershipRepository.isActiveMemberInActiveClub(callerId, clubId)) {
            throw ForbiddenException("Только для участников клуба")
        }
    }
}
