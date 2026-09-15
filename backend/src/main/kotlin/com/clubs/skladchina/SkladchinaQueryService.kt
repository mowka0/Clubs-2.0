package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.debt.DebtMapper
import com.clubs.debt.DebtPersonDto
import com.clubs.debt.DebtRepository
import com.clubs.event.EventRepository
import com.clubs.generated.jooq.enums.DebtStatus
import com.clubs.generated.jooq.enums.SkladchinaKind
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
            minAttended = MIN_ATTENDED
        ).map { SplittableEventDto(it.eventId, it.title, it.eventDatetime, it.attendedCount, it.attendedUserIds) }
    }

    /** Список активных сборов клуба для «Управления» (за @RequiresCapability(MANAGE_SKLADCHINA) на контроллере). */
    @Transactional(readOnly = true)
    fun getClubActiveSkladchinas(clubId: UUID, callerId: UUID): List<MySkladchinaListItemDto> {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        val items = skladchinaRepository.findAllByClubWithAggregates(clubId, includeCompleted = false, viewerId = callerId)
        val creatorNames = userRepository.findByIds(items.map { it.skladchina.creatorId }.toSet())
            .associate { it.id!! to it.firstName }
        return items.map {
            mapper.toMyFeedItemDto(
                MySkladchinaFeedItem(
                    skladchina = it.skladchina, clubName = club.name, clubAvatarUrl = club.avatarUrl,
                    creatorName = creatorNames[it.skladchina.creatorId] ?: "",
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
        val debtItems = debtRepository.findBySkladchina(skladchinaId)
        val debts = debtItems.map { debtMapper.toDto(it, now) }
        // Оплативших видят все участники — людьми, без сумм (PO 2026-09-14).
        val paid = debtItems.filter { it.debt.status == DebtStatus.received }.map { it.debtor }.distinctBy { it.id }.map(debtMapper::toPersonDto)
        val event = s.eventId?.let { eventRepository.findById(it) }
        return mapper.toDetailDto(
            skladchina = s,
            clubName = club.name,
            clubAvatarUrl = club.avatarUrl,
            creator = userRepository.findById(s.creatorId)
                ?.let { DebtPersonDto(it.id!!, it.firstName, it.lastName, it.telegramUsername, it.avatarUrl) }
                ?: DebtPersonDto(s.creatorId, "", null, null, null),
            callerId = callerId,
            canCancel = isCreator || club.ownerId == callerId,
            totals = debtRepository.totals(skladchinaId),
            enrolledCount = if (s.enrollmentUntil != null) skladchinaRepository.countEnrolled(skladchinaId) else 0,
            myEnrolled = s.isEnrolling && skladchinaRepository.isEnrolled(skladchinaId, callerId),
            // Этап записи — кто в деле; «По желанию» — кого позвали скинуться.
            enrolled = if (s.isEnrolling || s.kind == SkladchinaKind.voluntary) skladchinaRepository.findEnrolledPersons(skladchinaId).map(debtMapper::toPersonDto) else emptyList(),
            paid = paid,
            debts = debts,
            event = event
        )
    }

    /** Кнопка «Скинуться» на EventPage: активный сбор → открыть, собранный → уже собрано, иначе создать. Только участникам клуба встречи. */
    @Transactional(readOnly = true)
    fun findEventSplitState(eventId: UUID, callerId: UUID): EventSplitStateDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Встреча не найдена")
        requireMember(event.clubId, callerId)
        val split = skladchinaRepository.findBlockingByEventId(eventId)
        return EventSplitStateDto(skladchinaId = split?.id, status = split?.status?.literal)
    }

    private fun requireMember(clubId: UUID, callerId: UUID) {
        if (!membershipRepository.isActiveMemberInActiveClub(callerId, clubId)) {
            throw ForbiddenException("Только для участников клуба")
        }
    }

    companion object {
        // Сколько пришедших должно быть у встречи, чтобы предлагать по ней скинуться. Один — тоже
        // повод: с ним и делят счёт (PO 2026-09-15, до этого требовалось двое).
        private const val MIN_ATTENDED = 1
    }
}
