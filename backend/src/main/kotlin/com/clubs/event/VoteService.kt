package com.clubs.event

import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class VoteService(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val membershipRepository: MembershipRepository,
    private val clubRepository: ClubRepository,
    private val clubRoleGuard: ClubRoleGuard,
    private val rosterService: RosterService,
    private val eventPublisher: ApplicationEventPublisher
) {

    private val log = LoggerFactory.getLogger(VoteService::class.java)

    @Transactional
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
        // Встреча с порогом набора (V83): голос «Иду» сразу кладёт в состав или в очередь, любой
        // другой — выводит оттуда. У формата «сколько придёт» голос по-прежнему только мнение.
        rosterService.applyVote(event, userId, voteEnum)
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
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")
        val response = eventResponseRepository.findByEventAndUser(eventId, userId)
        // После Этапа 2 действующий статус пользователя — final_status (confirmed / waitlisted /
        // declined); до Этапа 2 — голос этапа 1. EventPage завязывает на это единственное поле
        // И кнопки подтверждения/отказа, И бейдж статуса, поэтому подтверждённый пользователь
        // должен прочитать назад "confirmed", а не неизменившийся "going" с этапа 1 — иначе UI
        // никогда не отразит подтверждение/отказ. Тот же приоритет, что в getEventResponders ниже.
        return MyVoteDto(
            vote = effectiveStatus(event, response?.stage1Vote?.literal, response?.finalStatus?.literal),
            // Место показываем только пока идёт набор: после закрытия состава его несёт сам vote.
            seat = if (isCollectingRoster(event)) response?.stage2Vote?.literal else null
        )
    }

    /**
     * Действующий статус участника для UI. Обычно это final_status с откатом на голос Этапа 1,
     * но у встречи с ПОРОГОМ НАБОРА (V83), пока набор идёт, приоритет обратный: голос «Иду» сразу
     * пишет final_status = confirmed, и если отдать его наружу, человек выпадет из вкладки «Идут»,
     * а кнопка его голоса перестанет подсвечиваться — состав в этой фазе показывает кольцо, а
     * список и кнопки живут голосами.
     */
    private fun effectiveStatus(event: Event, stage1: String?, finalStatus: String?): String? =
        if (isCollectingRoster(event)) stage1 ?: finalStatus
        else finalStatus ?: stage1

    /** Встреча с лимитом, у которой набор ещё идёт: голос и место значат разное. */
    private fun isCollectingRoster(event: Event): Boolean =
        event.isRosterEvent && event.status == EventStatus.upcoming

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
        // Тем же гейтом закрыт telegram_username: он нужен менеджеру, чтобы написать не ответившему
        // на Этапе 2 (event-stage2-composition.md § 5), но рядовому участнику контакты соседей
        // по событию не полагаются.
        val club = clubRepository.findById(event.clubId)
        val isManager = club != null && clubRoleGuard.hasCapability(club, userId, ClubCapability.MANAGE_EVENTS)

        return eventResponseRepository.findRespondersWithUsers(eventId).map { r ->
            EventResponderDto(
                userId = r.userId,
                firstName = r.firstName,
                lastName = r.lastName,
                avatarUrl = r.avatarUrl,
                status = effectiveStatus(event, r.stage1Vote?.literal, r.finalStatus?.literal) ?: "going",
                seat = if (isCollectingRoster(event)) r.finalStatus?.literal else null,
                attendance = r.attendance?.literal,
                disputeNote = if (isManager) r.disputeNote else null,
                telegramUsername = if (isManager) r.telegramUsername else null
            )
        }
    }

    /**
     * Таб «Без ответа»: участники клуба, от которых ещё ждут ответа на Этапе 2. Только менеджеру —
     * рядовому участнику знать, кто молчит, незачем.
     */
    fun getPendingMembers(eventId: UUID, userId: UUID): List<EventResponderDto> {
        requireEventManager(eventId, userId)
        return eventResponseRepository.findStage2PendingMembers(eventId).map { r ->
            EventResponderDto(
                userId = r.userId,
                firstName = r.firstName,
                lastName = r.lastName,
                avatarUrl = r.avatarUrl,
                // Голоса может не быть вовсе — тогда это молчун, статус "no_answer".
                status = r.stage1Vote?.literal ?: "no_answer",
                attendance = null,
                disputeNote = null,
                telegramUsername = r.telegramUsername,
                remindedAt = r.stage2RemindedAt
            )
        }
    }

    /**
     * Ручное напоминание ответить: [targetUserId] — конкретному участнику, `null` — всем, от кого
     * ждут ответа. Возвращает, сколько напоминаний реально ушло (повтор даёт 0).
     */
    @Transactional
    fun remind(eventId: UUID, userId: UUID, targetUserId: UUID?): RemindResultDto {
        val event = requireEventManager(eventId, userId)
        // Окно то же, в котором участник может ответить: подтверждение (Этап 2) либо идущий набор
        // состава. Второе — событие ещё `upcoming` — и есть главный случай напоминания у форматов
        // с лимитом: после закрытия состава отвечать уже нечего, а до него молчание участников
        // решает, наберётся ли встреча вообще.
        if (event.status != EventStatus.stage_2 && !isCollectingRoster(event)) {
            throw ValidationException("Confirmation is not open for this event")
        }
        if (!event.eventDatetime.isAfter(OffsetDateTime.now())) throw ValidationException("Event has already started")

        // Цели пересекаем с серверным набором: чужой userId не должен попасть в рассылку.
        val pending = eventResponseRepository.findStage2PendingMembers(eventId).map { it.userId }
        val targets = targetUserId?.let { target -> pending.filter { it == target } } ?: pending
        val telegramIds = eventResponseRepository.markStage2Reminded(eventId, targets)

        // DM — на AFTER_COMMIT: уведомление без закоммиченной отметки означало бы повторную отправку.
        if (telegramIds.isNotEmpty()) eventPublisher.publishEvent(Stage2ReminderSentEvent(event, telegramIds))
        log.info("Stage 2 reminder: eventId={} userId={} reminded={}", eventId, userId, telegramIds.size)
        return RemindResultDto(remindedCount = telegramIds.size)
    }

    /** Событие + гейт «владелец или активный со-организатор клуба события». */
    private fun requireEventManager(eventId: UUID, userId: UUID): Event {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")
        val club = clubRepository.findById(event.clubId) ?: throw NotFoundException("Club not found")
        clubRoleGuard.requireCapability(club, userId, ClubCapability.MANAGE_EVENTS)
        return event
    }
}
