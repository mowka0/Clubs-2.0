package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import com.clubs.skladchina.template.SkladchinaTemplateRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Creation side of the skladchina engine: validates the request, delegates participant sourcing +
 * amount resolution to the template strategy, persists the pool and announces it (DM). Split out of
 * the former god-`SkladchinaService` by responsibility; the engine owns club/owner, deadline bounds,
 * reputation gates, persistence and the created event, the strategy owns the template-specific parts.
 */
@Service
class SkladchinaCreationService(
    private val skladchinaRepository: SkladchinaRepository,
    private val clubRepository: ClubRepository,
    private val templateRegistry: SkladchinaTemplateRegistry,
    private val eventPublisher: ApplicationEventPublisher,
    private val queryService: SkladchinaQueryService
) {
    private val log = LoggerFactory.getLogger(SkladchinaCreationService::class.java)

    @Transactional
    fun createSkladchina(clubId: UUID, request: CreateSkladchinaRequest, creatorId: UUID): SkladchinaDetailDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != creatorId) throw ForbiddenException("Only the club organizer can create skladchina")

        val templateType = SkladchinaTemplate.values().find { it.literal == request.template }
            ?: throw ValidationException("Invalid template: ${request.template}")
        val strategy = templateRegistry.forType(templateType)

        val now = OffsetDateTime.now()
        val deadlineMinAge = ChronoUnit.HOURS.between(now, request.deadline)
        val deadlineMaxAge = ChronoUnit.DAYS.between(now, request.deadline)
        if (deadlineMinAge < MIN_DEADLINE_HOURS) {
            throw ValidationException("Deadline must be at least $MIN_DEADLINE_HOURS hour ahead")
        }
        if (deadlineMaxAge > MAX_DEADLINE_DAYS) {
            throw ValidationException("Deadline must be at most $MAX_DEADLINE_DAYS days ahead")
        }

        // The template owns participant sourcing + amount resolution + its own validation;
        // the engine owns club/owner, deadline bounds, reputation gates, persistence and DM.
        val resolution = strategy.resolveCreation(clubId, creatorId, request)

        // #8: a VERIFIED template (split_bill, both modes) ALWAYS affects reputation — its
        // attendance anchor IS the anti-farm, so it bypasses the "важный сбор" gates (rate limit /
        // 24h-window / voluntary-block) that exist only to police the organizer-chosen toggle.
        // The custom toggle still runs the gates.
        val affectsReputation = strategy.outcomesVerified || request.affectsReputation
        if (request.affectsReputation && !strategy.outcomesVerified) {
            validateReputationGates(clubId, resolution.mode, deadlineMinAge, now)
        }

        val skladchinaId = UUID.randomUUID()
        val domain = Skladchina(
            id = skladchinaId,
            clubId = clubId,
            creatorId = creatorId,
            title = request.title,
            description = request.description,
            rules = request.rules,
            photoUrl = request.photoUrl,
            template = templateType,
            paymentMode = resolution.mode,
            totalGoalKopecks = resolution.totalGoalKopecks,
            paymentLink = request.paymentLink,
            paymentMethodNote = request.paymentMethodNote,
            eventId = resolution.eventId,
            deadline = request.deadline,
            affectsReputation = affectsReputation,
            status = SkladchinaStatus.active,
            closedAt = null,
            closedBy = null,
            createdAt = now,
            updatedAt = now
        )

        val created = skladchinaRepository.create(domain, resolution.participants)
        log.info("Skladchina created: id={} clubId={} creatorId={} template={} mode={} participants={}",
            created.id, clubId, creatorId, templateType, resolution.mode, resolution.participants.size)

        // DM-рассылка идёт через @TransactionalEventListener в SkladchinaBotNotifier —
        // гарантия отправки ПОСЛЕ commit'а транзакции (тот же паттерн что SkladchinaBotNotifier).
        eventPublisher.publishEvent(
            SkladchinaCreatedEvent(
                skladchinaId = created.id,
                clubId = clubId,
                clubName = club.name,
                title = created.title,
                description = created.description,
                paymentLink = created.paymentLink,
                paymentMode = created.paymentMode.literal,
                totalGoalKopecks = created.totalGoalKopecks,
                deadline = created.deadline,
                affectsReputation = created.affectsReputation,
                participantUserIds = resolution.participants.map { it.first }
            )
        )

        return queryService.getDetail(created.id, creatorId)
    }

    /**
     * Gates for the "важный сбор" toggle (affects_reputation = true), per the
     * 2026-06-12 redesign. Messages are user-facing (organizer's create form).
     */
    private fun validateReputationGates(clubId: UUID, mode: SkladchinaMode, deadlineHoursAhead: Long, now: OffsetDateTime) {
        if (mode == SkladchinaMode.voluntary) {
            // "Voluntary with a silence penalty" is an oxymoron — the toggle is fixed-modes only.
            throw ValidationException("Добровольный сбор не может влиять на репутацию")
        }
        if (deadlineHoursAhead < MIN_REPUTATION_DEADLINE_HOURS) {
            // Anti-"сбор-засада": participants must get a real window to answer
            // (creation DM + the 24h-before reminder DM).
            throw ValidationException("Для важного сбора дедлайн должен быть не раньше чем через 24 часа")
        }
        val recentCount = skladchinaRepository.countReputationAffectingCreatedSince(
            clubId, now.minusDays(REPUTATION_RATE_LIMIT_WINDOW_DAYS)
        )
        if (recentCount >= REPUTATION_RATE_LIMIT_MAX) {
            // The only real anti-farm AND anti-griefing mechanism: caps farming at
            // +30/week/club per friend and griefing at -120/week per ignored victim.
            throw ValidationException(
                "Лимит важных сборов: не больше $REPUTATION_RATE_LIMIT_MAX за " +
                    "$REPUTATION_RATE_LIMIT_WINDOW_DAYS дней в одном клубе. " +
                    "Создайте сбор без влияния на репутацию или попробуйте позже"
            )
        }
    }

    companion object {
        private const val MIN_DEADLINE_HOURS = 1L
        private const val MAX_DEADLINE_DAYS = 90L

        // "Важный сбор" gates (docs/backlog/skladchina-reputation-redesign.md § Валидации):
        private const val MIN_REPUTATION_DEADLINE_HOURS = 24L   // anti-ambush; 48h rejected (breaks "бронь на завтра")
        private const val REPUTATION_RATE_LIMIT_MAX = 3         // per club, rolling window
        private const val REPUTATION_RATE_LIMIT_WINDOW_DAYS = 7L
    }
}
