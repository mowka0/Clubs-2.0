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
 * Сторона создания движка складчины: валидирует запрос, делегирует подбор участников + расчёт суммы
 * стратегии шаблона, сохраняет пул и анонсирует его (DM). Выделен из бывшего god-`SkladchinaService`
 * по ответственности; движок владеет клубом/владельцем, границами дедлайна, гейтами репутации,
 * персистентностью и созданным событием, стратегия — шаблон-специфичными частями.
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

        // Шаблон владеет подбором участников + расчётом суммы + собственной валидацией;
        // движок владеет клубом/владельцем, границами дедлайна, гейтами репутации, персистентностью и DM.
        val resolution = strategy.resolveCreation(clubId, creatorId, request)

        // #8: VERIFIED-шаблон (split_bill, оба режима) ВСЕГДА влияет на репутацию — его якорь
        // посещаемости И ЕСТЬ анти-фарм-защита, поэтому он обходит гейты «важного сбора» (rate limit /
        // 24h-окно / блок voluntary-режима), которые существуют только чтобы контролировать
        // организаторский тумблер. Кастомный тумблер по-прежнему проходит через гейты.
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
     * Гейты для тумблера «важный сбор» (affects_reputation = true), согласно
     * редизайну 2026-06-12. Сообщения показываются пользователю (форма создания организатора).
     */
    private fun validateReputationGates(clubId: UUID, mode: SkladchinaMode, deadlineHoursAhead: Long, now: OffsetDateTime) {
        if (mode == SkladchinaMode.voluntary) {
            // «Добровольный сбор со штрафом за молчание» — оксюморон, тумблер работает только с
            // фиксированными режимами.
            throw ValidationException("Добровольный сбор не может влиять на репутацию")
        }
        if (deadlineHoursAhead < MIN_REPUTATION_DEADLINE_HOURS) {
            // Анти-«сбор-засада»: у участников должно быть реальное окно, чтобы ответить
            // (DM при создании + DM-напоминание за 24 часа).
            throw ValidationException("Для важного сбора дедлайн должен быть не раньше чем через 24 часа")
        }
        val recentCount = skladchinaRepository.countReputationAffectingCreatedSince(
            clubId, now.minusDays(REPUTATION_RATE_LIMIT_WINDOW_DAYS)
        )
        if (recentCount >= REPUTATION_RATE_LIMIT_MAX) {
            // Единственный реальный механизм анти-фарма И анти-грифинга: ограничивает фарм
            // до +30/неделю/клуб на друга и грифинг до -120/неделю на игнорируемую жертву.
            throw ValidationException(
                "Лимит важных сборов: не больше $REPUTATION_RATE_LIMIT_MAX за " +
                    "$REPUTATION_RATE_LIMIT_WINDOW_DAYS дней в одном клубе. " +
                    "Создайте сбор без влияния на репутацию или попробуйте позже"
            )
        }
    }

    companion object {
        private const val MIN_DEADLINE_HOURS = 1L  // минимальный отступ дедлайна сбора от текущего момента
        private const val MAX_DEADLINE_DAYS = 90L  // максимальный горизонт дедлайна сбора вперёд

        // Гейты «важного сбора» (docs/backlog/skladchina-reputation-redesign.md § Валидации):
        private const val MIN_REPUTATION_DEADLINE_HOURS = 24L   // анти-засада; 48ч отвергнуты (ломает «бронь на завтра»)
        private const val REPUTATION_RATE_LIMIT_MAX = 3         // на клуб, скользящее окно
        private const val REPUTATION_RATE_LIMIT_WINDOW_DAYS = 7L // ширина скользящего окна в днях
    }
}
