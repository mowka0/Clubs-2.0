package com.clubs.event

import com.clubs.club.ClubRepository
import com.clubs.common.dto.PageResponse
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.skladchina.SkladchinaRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class EventService(
    private val eventRepository: EventRepository,
    private val clubRepository: ClubRepository,
    private val eventMapper: EventMapper,
    private val eventPublisher: ApplicationEventPublisher,
    private val skladchinaRepository: SkladchinaRepository
) {

    private val log = LoggerFactory.getLogger(EventService::class.java)

    @Transactional
    fun createEvent(clubId: UUID, request: CreateEventRequest, userId: UUID): EventDetailDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club organizer can create events")
        // Пустое/пробельное уточнение к месту схлопывается в null — как cancellationReason в cancelEvent.
        val normalizedRequest = request.copy(locationHint = request.locationHint?.trim()?.takeIf { it.isNotEmpty() })
        val event = eventRepository.create(normalizedRequest, clubId, userId)
        log.info("Event created: id={} clubId={} title='{}' userId={}", event.id, clubId, event.title, userId)
        // DM участникам рассылает EventBotNotifier на AFTER_COMMIT. Публикация внутри
        // транзакции позволяет слушателю вовсе не сработать, если внешний
        // @Transactional откатится. По аналогии с PaymentService / SkladchinaService.
        eventPublisher.publishEvent(EventCreatedEvent(event))
        return eventMapper.toDetailDto(event, goingCount = 0, maybeCount = 0, notGoingCount = 0, confirmedCount = 0)
    }

    fun getClubEvents(clubId: UUID, statusStr: String?, page: Int, size: Int): PageResponse<EventListItemDto> {
        clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        val status = statusStr?.let {
            try { EventStatus.valueOf(it) }
            catch (e: IllegalArgumentException) { null }
        }
        return eventRepository.findByClubId(clubId, status, page, size)
    }

    fun getEvent(id: UUID): EventDetailDto {
        val event = eventRepository.findById(id) ?: throw NotFoundException("Event not found")
        val counts = eventRepository.getVoteCounts(id)
        return eventMapper.toDetailDto(
            event,
            goingCount = counts["going"] ?: 0,
            maybeCount = counts["maybe"] ?: 0,
            notGoingCount = counts["notGoing"] ?: 0,
            confirmedCount = counts["confirmed"] ?: 0
        )
    }

    /**
     * F5-14: организатор отменяет ещё не начавшееся событие. Атомарно отменяет событие + привязанный
     * активный сбор (pending → released, без репутации), затем на AFTER_COMMIT рассылает DM
     * заинтересованным проголосовавшим. SQL-guard (status active AND event_datetime > now) даёт
     * 0 строк ⇒ 409 для начавшегося/финализированного/уже отменённого события. Репутация не
     * трогается никогда — каскад идёт через репозитории, по аналогии с ClubService.deleteClub.
     */
    @Transactional
    fun cancelEvent(eventId: UUID, userId: UUID, reason: String?): EventDetailDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")
        val club = clubRepository.findById(event.clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club organizer can cancel events")

        val normalizedReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        if (eventRepository.cancelEvent(eventId, normalizedReason) == 0) {
            throw ConflictException("Событие нельзя отменить: оно уже началось, завершено или отменено")
        }
        skladchinaRepository.cancelActiveByEventId(eventId)

        log.info("Event cancelled: id={} userId={} reasonGiven={}", eventId, userId, normalizedReason != null)
        eventPublisher.publishEvent(EventCancelledEvent(event, normalizedReason))
        return getEvent(eventId)
    }
}
