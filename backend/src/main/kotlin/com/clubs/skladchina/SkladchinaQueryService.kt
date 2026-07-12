package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubManagerGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.skladchina.template.DeclinePolicy
import com.clubs.skladchina.template.SkladchinaTemplateRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Read-сторона движка складчины: деталка + список активных по клубу, с проверками доступа и
 * маппингом в DTO. Мутаций нет, поэтому все остальные сервисы зависят от него ради response-DTO,
 * которые возвращают (лист в графе зависимостей). Выделен из бывшего god-`SkladchinaService`
 * по ответственности.
 */
@Service
class SkladchinaQueryService(
    private val skladchinaRepository: SkladchinaRepository,
    private val clubRepository: ClubRepository,
    private val clubManagerGuard: ClubManagerGuard,
    private val mapper: SkladchinaMapper,
    private val templateRegistry: SkladchinaTemplateRegistry
) {

    @Transactional(readOnly = true)
    fun getClubActiveSkladchinas(clubId: UUID, callerId: UUID): List<MySkladchinaListItemDto> {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        // Один расчёт на весь список: эндпоинт и так за @RequiresClubManager, но создатель-без-роли
        // сюда не попадает, поэтому предикат честный, не константа.
        val callerIsManager = clubManagerGuard.isManager(club, callerId)
        val skladchinas = skladchinaRepository.findActiveByClub(clubId)
        return skladchinas.map { s ->
            val collected = skladchinaRepository.sumCollectedKopecks(s.id)
            val totalParticipants = skladchinaRepository.countParticipants(s.id)
            val paid = skladchinaRepository.countParticipantsByStatus(s.id, SkladchinaParticipantStatus.paid)
            val callerParticipant = skladchinaRepository.findParticipant(s.id, callerId)
            mapper.toMyFeedItemDto(
                MySkladchinaFeedItem(
                    skladchina = s,
                    clubName = club.name,
                    clubAvatarUrl = club.avatarUrl,
                    myStatus = callerParticipant?.status,
                    collectedKopecks = collected,
                    participantCount = totalParticipants,
                    paidCount = paid
                ),
                callerId,
                callerIsManager
            )
        }
    }

    @Transactional(readOnly = true)
    fun getDetail(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        val club = clubRepository.findById(skladchina.clubId)
            ?: throw NotFoundException("Club not found")

        // Доступ: создатель ИЛИ участник ИЛИ менеджер клуба (У-1: владелец видит и ведёт сбор
        // со-орга и наоборот, даже не будучи участником).
        val isCreator = skladchina.creatorId == callerId
        val callerParticipant = skladchinaRepository.findParticipant(skladchinaId, callerId)
        val callerIsManager = clubManagerGuard.isManager(club, callerId)
        if (!isCreator && callerParticipant == null && !callerIsManager) {
            throw ForbiddenException("Not allowed to view this skladchina")
        }

        val participants = skladchinaRepository.findParticipantsWithInfo(skladchinaId)
        val collected = skladchinaRepository.sumCollectedKopecks(skladchinaId)
        val declineRequiresApproval =
            templateRegistry.forType(skladchina.template).declinePolicy == DeclinePolicy.REQUIRES_APPROVAL
        return mapper.toDetailDto(
            skladchina, club.name, club.avatarUrl, callerId, callerIsManager, participants, collected,
            declineRequiresApproval
        )
    }

    /**
     * Состояние сплита, привязанного к [eventId] — для кнопки «Разделить счёт» на EventPage.
     * Возвращает активный сплит (→ открыть его) или, если такого нет, успешно закрытый (→ уже
     * собрано); оба поля null, когда сплита нет либо остались только failed/cancelled (→ кнопка
     * создаёт новый сплит).
     */
    @Transactional(readOnly = true)
    fun findEventSplitState(eventId: UUID): EventSplitStateDto {
        val split = skladchinaRepository.findBlockingByEventId(eventId)
        return EventSplitStateDto(skladchinaId = split?.id, status = split?.status?.literal)
    }
}
