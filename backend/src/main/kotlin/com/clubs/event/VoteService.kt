package com.clubs.event

import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubManagerGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class VoteService(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val membershipRepository: MembershipRepository,
    private val clubRepository: ClubRepository,
    private val clubManagerGuard: ClubManagerGuard,
    private val eventPublisher: ApplicationEventPublisher
) {

    private val log = LoggerFactory.getLogger(VoteService::class.java)

    fun castVote(eventId: UUID, userId: UUID, request: CastVoteRequest): VoteResponseDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (!membershipRepository.isMember(userId, event.clubId)) {
            throw ForbiddenException("Not a member of this club")
        }

        if (event.status != EventStatus.upcoming) {
            throw ValidationException("Voting is not available for this event")
        }

        // S1-001: окно голосования должно использовать ТУ ЖЕ точную границу, что и лента
        // (EventMapper.computeActionRequired / JooqEventRepository.findMyFeed):
        // открыто ⇔ event_datetime - votingOpensDaysBefore дней <= now. ChronoUnit.DAYS.between
        // отбрасывает дробную часть суток — голосование открывалось бы на ~24 часа раньше и
        // расходилось с бейджем «требуется действие» в UI. См. events.md § voting window.
        val votingOpensAt = event.eventDatetime.minusDays(event.votingOpensDaysBefore.toLong())
        if (OffsetDateTime.now().isBefore(votingOpensAt)) {
            throw ValidationException("Voting has not started yet")
        }

        val voteEnum = Stage_1Vote.values().find { it.literal == request.vote }
            ?: throw ValidationException("Invalid vote value: ${request.vote}")

        eventResponseRepository.upsertStage1Vote(eventId, userId, voteEnum)
        log.info("Vote cast: eventId={} userId={} vote={}", eventId, userId, request.vote)
        // Живой закреп в чате перерисовывает счётчики голосов (dirty-флаг, дебаунс на стороне слушателя).
        eventPublisher.publishEvent(EventRosterChangedEvent(eventId))

        val counts = eventResponseRepository.countByVote(eventId)
        return VoteResponseDto(
            eventId = eventId,
            vote = request.vote,
            goingCount = counts["going"] ?: 0,
            maybeCount = counts["maybe"] ?: 0,
            notGoingCount = counts["notGoing"] ?: 0
        )
    }

    fun getMyVote(eventId: UUID, userId: UUID): MyVoteDto {
        eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")
        val response = eventResponseRepository.findByEventAndUser(eventId, userId)
        // После Этапа 2 действующий статус пользователя — final_status (confirmed / waitlisted /
        // declined); до Этапа 2 — голос этапа 1. EventPage завязывает на это единственное поле
        // И кнопки подтверждения/отказа, И бейдж статуса, поэтому подтверждённый пользователь
        // должен прочитать назад "confirmed", а не неизменившийся "going" с этапа 1 — иначе UI
        // никогда не отразит подтверждение/отказ. Тот же приоритет, что в getEventResponders ниже.
        return MyVoteDto(vote = response?.finalStatus?.literal ?: response?.stage1Vote?.literal)
    }

    /**
     * Возвращает список откликнувшихся на событие (с данными пользователя + текущим намерением).
     * Доступно только участникам клуба — то же правило видимости, что и у самого голосования.
     */
    fun getEventResponders(eventId: UUID, userId: UUID): List<EventResponderDto> {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (!membershipRepository.isMember(userId, event.clubId)) {
            throw ForbiddenException("Not a member of this club")
        }

        // F5-06 (A01): dispute_note — приватное сообщение, адресованное организатору: читать его
        // может только менеджер клуба (владелец или активный со-орг — тот, кто разбирает споры,
        // co-organizers точка 27), а не каждый участник. Ключ — менеджерство в клубе (НЕ event.createdBy:
        // создатель-невладелец без роли видеть заметку не должен).
        // Заметка остаётся в SQL-проекции; здесь мы зануляем её, чтобы участникам она не ушла по сети.
        val club = clubRepository.findById(event.clubId)
        val isManager = club != null && clubManagerGuard.isManager(club, userId)

        return eventResponseRepository.findRespondersWithUsers(eventId).map { r ->
            EventResponderDto(
                userId = r.userId,
                firstName = r.firstName,
                lastName = r.lastName,
                avatarUrl = r.avatarUrl,
                status = r.finalStatus?.literal ?: r.stage1Vote?.literal ?: "going",
                attendance = r.attendance?.literal,
                disputeNote = if (isManager) r.disputeNote else null
            )
        }
    }
}
