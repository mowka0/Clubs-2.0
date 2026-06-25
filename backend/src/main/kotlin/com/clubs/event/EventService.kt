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
        val event = eventRepository.create(request, clubId, userId)
        log.info("Event created: id={} clubId={} title='{}' userId={}", event.id, clubId, event.title, userId)
        // Member DMs are dispatched AFTER_COMMIT by EventBotNotifier. Publishing
        // inside the transaction lets the listener skip entirely if the outer
        // @Transactional rolls back. Mirrors PaymentService / SkladchinaService.
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
     * F5-14: organizer cancels a not-yet-started event. Cancels the event + any linked active split
     * (pending → released, no reputation) atomically, then DMs interested voters AFTER_COMMIT. The
     * SQL guard (status active AND event_datetime > now) yields 0 rows ⇒ 409 for a started/finalized/
     * already-cancelled event. Reputation is never touched — the cascade runs through repositories,
     * mirroring ClubService.deleteClub.
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
