package com.clubs.event

import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.dto.PageResponse
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.skladchina.SkladchinaRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class EventService(
    private val eventRepository: EventRepository,
    private val clubRepository: ClubRepository,
    private val clubRoleGuard: ClubRoleGuard,
    private val eventMapper: EventMapper,
    private val eventPublisher: ApplicationEventPublisher,
    private val skladchinaRepository: SkladchinaRepository
) {

    companion object {
        // Сколько ближайших встреч показывает тизер-афиша смотрящему без доступа
        const val TEASER_UPCOMING_LIMIT = 3
        // Сколько последних прошедших встреч показывает тизер-афиша
        const val TEASER_PAST_LIMIT = 3
    }

    private val log = LoggerFactory.getLogger(EventService::class.java)

    @Transactional
    fun createEvent(clubId: UUID, request: CreateEventRequest, userId: UUID): EventDetailDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        // Менеджерский гейт (co-organizers), синхронно с @RequiresCapability(MANAGE_EVENTS) на контроллере.
        clubRoleGuard.requireCapability(club, userId, ClubCapability.MANAGE_EVENTS)
        // Пустые/пробельные адрес и уточнение схлопываются в null — как cancellationReason в cancelEvent.
        val normalizedRequest = request.copy(
            locationText = request.locationText?.trim()?.takeIf { it.isNotEmpty() },
            locationHint = request.locationHint?.trim()?.takeIf { it.isNotEmpty() }
        )
        val persisted = eventRepository.create(normalizedRequest, clubId, userId)
        // Срочная встреча (решение PO 2026-07-23): Этапа 1 нет — событие рождается сразу в
        // подтверждении мест, тем же transitionToStage2 и в той же транзакции. Уведомление
        // одно (EventBotNotifier ниже): stage_2-событие зовёт подтверждать, а не голосовать.
        val event = if (normalizedRequest.isUrgentEvent) {
            eventRepository.transitionToStage2(persisted.id)
            persisted.copy(status = EventStatus.stage_2, stage2Triggered = true)
        } else persisted
        log.info(
            "Event created: id={} clubId={} title='{}' userId={} urgent={}",
            event.id, clubId, event.title, userId, normalizedRequest.isUrgentEvent
        )
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

    /**
     * Тизер-афиша (решение PO 2026-07-24): урезанная афиша встреч для смотрящего БЕЗ доступа
     * к контенту клуба — гостя или участника без взноса (frozen/expired). Намеренно ДОСТУПНА
     * без членства (в отличие от getClubEvents под @RequiresMembership): человек должен видеть,
     * что клуб живой, прежде чем платить. Приватное (место, фото, состав) в проекцию не входит
     * по построению — см. ClubEventsTeaserDto.
     */
    fun getClubEventsTeaser(clubId: UUID): ClubEventsTeaserDto {
        val club = clubRepository.findById(clubId)
        // Мягко удалённый клуб не существует для читателя (как в Discovery), не 200 с пустотой.
        if (club == null || !club.isActive) throw NotFoundException("Club not found")

        val now = OffsetDateTime.now()
        val visible = eventRepository.findAllByClubWithGoingCount(clubId)
            .filter { it.event.status != EventStatus.cancelled }
        val upcoming = visible
            .filter { it.event.eventDatetime.isAfter(now) }
            .sortedBy { it.event.eventDatetime }
            .take(TEASER_UPCOMING_LIMIT)
        val past = visible
            .filter { !it.event.eventDatetime.isAfter(now) }
            .sortedByDescending { it.event.eventDatetime }
            .take(TEASER_PAST_LIMIT)
        return ClubEventsTeaserDto(
            upcoming = upcoming.map(eventMapper::toTeaserDto),
            past = past.map(eventMapper::toTeaserDto),
            totalPastCount = eventRepository.countPastEvents(clubId, now)
        )
    }

    fun getEvent(id: UUID): EventDetailDto {
        val event = eventRepository.findById(id) ?: throw NotFoundException("Event not found")
        val counts = eventRepository.getVoteCounts(id)
        return eventMapper.toDetailDto(
            event,
            goingCount = counts["going"] ?: 0,
            maybeCount = counts["maybe"] ?: 0,
            notGoingCount = counts["notGoing"] ?: 0,
            confirmedCount = counts["confirmed"] ?: 0,
            noAnswerCount = counts["noAnswer"] ?: 0,
            waitlistedCount = counts["waitlisted"] ?: 0
        )
    }

    /**
     * Системная отмена встречи без участия человека: набор не собрался, а организатор не ответил
     * на DM в отведённое окно (V83). Тот же каскад, что у ручной отмены (сбор → released, DM
     * заинтересованным на AFTER_COMMIT), но без гейта capability — инициатор здесь планировщик.
     * Гонку с ручной отменой снимает SQL-guard внутри cancelEvent: 0 строк → просто выходим.
     */
    @Transactional
    fun cancelBySystem(event: Event, reason: String) {
        if (eventRepository.cancelEvent(event.id, reason) == 0) {
            log.info("System cancel skipped — event already inactive: id={}", event.id)
            return
        }
        skladchinaRepository.cancelActiveByEventId(event.id)
        log.info("Event cancelled by system: id={} reason='{}'", event.id, reason)
        eventPublisher.publishEvent(EventCancelledEvent(event, reason))
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
        // Менеджерский гейт (co-organizers): владелец или активный со-орг отменяет событие.
        clubRoleGuard.requireCapability(club, userId, ClubCapability.MANAGE_EVENTS)

        val normalizedReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        if (eventRepository.cancelEvent(eventId, normalizedReason) == 0) {
            throw ConflictException("Событие нельзя отменить: оно уже началось, завершено или отменено")
        }
        skladchinaRepository.cancelActiveByEventId(eventId)

        log.info("Event cancelled: id={} userId={} reasonGiven={}", eventId, userId, normalizedReason != null)
        eventPublisher.publishEvent(EventCancelledEvent(event, normalizedReason))
        return getEvent(eventId)
    }

    /**
     * Редактирование встречи, включая перенос даты (решения PO 2026-07-23 и 2026-07-26):
     * только организатор/со-орг и только на Этапе 1 — с началом подтверждения мест правки
     * запрещены, подтвердившие обещали прийти в конкретное место и время. SQL-guard
     * (status=upcoming AND stage_2_triggered=false AND event_datetime > now) даёт 0 строк
     * ⇒ 409 для события в Этапе 2 / срочного / начавшегося / завершённого / отменённого.
     *
     * Дата ближе интервала Этапа 2 намеренно НЕ отклоняется — как при создании: событие
     * просто перейдёт в Этап 2 ближайшим тиком шедулера.
     *
     * Формат встречи неизменяем, поэтому зависящие от него инварианты проверяются здесь,
     * а не в DTO: там формат неизвестен. Уведомление уходит только при критичных изменениях
     * («где» и «когда») — остальное правится молча.
     */
    @Transactional
    fun updateEvent(eventId: UUID, userId: UUID, request: UpdateEventRequest): EventDetailDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")
        val club = clubRepository.findById(event.clubId) ?: throw NotFoundException("Club not found")
        // Менеджерский гейт (co-organizers): владелец или активный со-орг редактирует встречу.
        clubRoleGuard.requireCapability(club, userId, ClubCapability.MANAGE_EVENTS)

        validateFormatInvariants(event, request)

        val edit = EventEdit(
            title = request.title,
            description = request.description,
            locationText = request.locationText,
            locationLat = request.locationLat,
            locationLon = request.locationLon,
            locationHint = request.locationHint,
            eventDatetime = request.eventDatetime,
            participantLimit = request.participantLimit,
            stage2LeadMinutes = request.stage2LeadMinutes,
            photoUrl = request.photoUrl
        )
        if (eventRepository.updateEvent(eventId, edit) == 0) {
            throw ConflictException("Встречу нельзя изменить: подтверждение мест уже началось, событие прошло или отменено")
        }

        val updated = event.copy(
            title = edit.title,
            description = edit.description,
            locationText = edit.locationText,
            locationLat = edit.locationLat,
            locationLon = edit.locationLon,
            locationHint = edit.locationHint,
            eventDatetime = edit.eventDatetime,
            participantLimit = edit.participantLimit,
            stage2LeadMinutes = edit.stage2LeadMinutes,
            photoUrl = edit.photoUrl
        )
        val edited = EventEditedEvent(updated, oldEvent = event)
        log.info(
            "Event updated: id={} userId={} datetimeChanged={} locationChanged={}",
            eventId, userId, edited.isDatetimeChanged, edited.isLocationChanged
        )
        // Публикуем ВСЕГДА: даже некритичная правка должна тихо перерисовать живой закреп в чате
        // (там висят название, дата и место). Кого дёргать звуком, решает слушатель —
        // громкий пост и DM уходят только при критичных изменениях.
        eventPublisher.publishEvent(edited)
        return getEvent(eventId)
    }

    /**
     * Инварианты, завязанные на неизменяемый формат встречи (зеркалят проверки
     * CreateEventRequest, но формат берётся из самого события, а не из запроса).
     */
    private fun validateFormatInvariants(event: Event, request: UpdateEventRequest) {
        if (event.isOpenEvent && request.participantLimit != null) {
            throw ValidationException("Открытая встреча не имеет лимита участников")
        }
        if (!event.isOpenEvent && request.participantLimit == null) {
            throw ValidationException("Для встречи с местами нужен лимит участников")
        }
        if (event.isOpenEvent && request.stage2LeadMinutes != null) {
            throw ValidationException("У открытой встречи нет Этапа 2 — интервал подтверждения неприменим")
        }
        if (event.isUrgent && request.stage2LeadMinutes != null) {
            throw ValidationException("У срочной встречи нет Этапа 1 — интервал подтверждения неприменим")
        }
    }
}
