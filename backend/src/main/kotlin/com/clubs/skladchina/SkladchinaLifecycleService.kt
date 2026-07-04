package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.ReputationAxis
import com.clubs.generated.jooq.enums.ReputationSource
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.reputation.LedgerEntry
import com.clubs.reputation.ReputationPolicy
import com.clubs.reputation.ReputationService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Закрывающая сторона движка складчины: ручное закрытие, закрытие шедулером/автозакрытие, вычисление
 * финального статуса и репутационные дельты, применяемые при закрытии. Выделено из бывшего
 * god-`SkladchinaService` по ответственности.
 *
 * [maybeAutoCloseAfterStateChange] и [closeInternal] живут в ЭТОМ бине намеренно: действие участника
 * (SkladchinaPaymentService) вызывает maybeAutoClose внутри своего @Transactional, а
 * maybeAutoClose → closeInternal здесь — SELF-вызов (без прокси), поэтому closeInternal присоединяется
 * к транзакции вызывающего, и пойманная прикладная ошибка никогда не помечает её rollback-only (F5-18).
 * Перенос любого из них через границу бинов изменил бы эту семантику отката.
 */
@Service
class SkladchinaLifecycleService(
    private val skladchinaRepository: SkladchinaRepository,
    private val clubRepository: ClubRepository,
    private val reputationService: ReputationService,
    private val eventPublisher: ApplicationEventPublisher,
    private val queryService: SkladchinaQueryService
) {
    private val log = LoggerFactory.getLogger(SkladchinaLifecycleService::class.java)

    /**
     * Триггер автозакрытия, срабатывающий после mark-paid / decline / organizer-mark-paid.
     * Закрывает ТОЛЬКО когда каждый участник в терминальном статусе (не осталось `pending`).
     *
     * Фаза A (A-4): достижение цели БОЛЬШЕ НЕ закрывает принудительно (это было источником
     * сложности F5-02). Деньги теперь декорация — приложение трекер, а не платёжная система, —
     * поэтому досрочно собранная цель просто лежит; закрытие — по дедлайну / вручную /
     * когда ответили все. Org-unmark сюда никогда не доходит (он только увеличивает `pending`).
     *
     * F5-18: сбой закрытия/репутации ловится и логируется ЗДЕСЬ, чтобы собственный
     * markPaid/decline участника никогда не отдавал из-за него 500 (NFR skladchina.md). Замечание
     * о границах: maybeAutoClose выполняется в транзакции вызывающего, а closeInternal — self-вызов,
     * поэтому catch защищает от прикладных сбоев; ошибка уровня БД внутри closeInternal всё равно
     * прерывает общую Postgres-транзакцию.
     */
    fun maybeAutoCloseAfterStateChange(skladchinaId: UUID) {
        val skladchina = skladchinaRepository.findById(skladchinaId) ?: return
        if (skladchina.status != SkladchinaStatus.active) return

        val noPendingLeft = skladchinaRepository.countParticipantsByStatus(
            skladchinaId, SkladchinaParticipantStatus.pending
        ) == 0
        if (!noPendingLeft) return

        try {
            closeInternal(skladchinaId, closedBy = null, manualClose = false)
        } catch (e: Exception) {
            log.error(
                "Auto-close failed for skladchina {} — keeping the participant's state change",
                skladchinaId, e
            )
        }
    }

    @Transactional
    fun closeManually(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.creatorId != callerId) {
            throw ForbiddenException("Only creator can close skladchina")
        }
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is already closed")
        }
        closeInternal(skladchinaId, closedBy = callerId, manualClose = true)
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * Внутренний хелпер, используемый и ручным закрытием (создатель), и автозакрытием
     * (scheduler / достигнутая цель). Атомарно захватывает закрытие, резолвит pending-участников,
     * идемпотентно применяет репутационные дельты, уведомляет организатора.
     *
     * F5-12: переключение статуса — атомарный claim (`UPDATE … WHERE status = 'active'`, паттерн
     * claimEvent) — конкурентный закрывающий (scheduler × автозакрытие × ручной) проигрывает claim
     * и делает no-op, поэтому участники резолвятся один раз и SkladchinaClosedEvent стреляет ровно
     * один раз.
     *
     * F5-02: pending-участники получают `expired_no_response` (-40) ТОЛЬКО когда закрытие происходит
     * в момент дедлайна или после него. Раннее закрытие (цель достигнута / все ответили / вручную)
     * переводит их в `released` — обещание было «ответить до дедлайна», а дедлайн так и не наступил,
     * поэтому строка в ledger не создаётся (financeKind(released) = null).
     *
     * @Transactional нужен, чтобы путь scheduler/автозакрытия (SkladchinaScheduler -> closeInternal —
     * межбиновый вызов без объемлющей транзакции) коммитил resolve/статус/репутацию атомарно. Уже
     * транзакционные действия участников self-вызывают это через maybeAutoCloseAfterStateChange и
     * просто выполняются внутри своей текущей транзакции. Атомарность гарантирует, что сбой записи
     * в ledger откатывает и claim, и отметки reputation_applied — ретрай может восстановиться
     * (нет осиротевших участников).
     */
    @Transactional
    fun closeInternal(skladchinaId: UUID, closedBy: UUID?, manualClose: Boolean) {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) {
            log.warn("closeInternal called on non-active skladchina {}: status={}", skladchinaId, skladchina.status)
            return
        }
        val club = clubRepository.findById(skladchina.clubId)
            ?: throw NotFoundException("Club not found")

        val collected = skladchinaRepository.sumCollectedKopecks(skladchinaId)
        val finalStatus = computeFinalStatus(skladchina, collected, manualClose)
        val closedAt = OffsetDateTime.now()

        if (!skladchinaRepository.claimClose(skladchinaId, finalStatus, closedBy, closedAt)) {
            log.info("Skladchina close claim lost: id={} — already closed concurrently, no-op", skladchinaId)
            return
        }

        val deadlineReached = !closedAt.isBefore(skladchina.deadline)
        if (deadlineReached) {
            skladchinaRepository.expirePendingParticipants(skladchinaId)
        } else {
            skladchinaRepository.releasePendingParticipants(skladchinaId)
        }

        val totalParticipants = skladchinaRepository.countParticipants(skladchinaId)
        val paidCount = skladchinaRepository.countParticipantsByStatus(skladchinaId, SkladchinaParticipantStatus.paid)
        log.info("Skladchina closed: id={} status={} collected={} paid={}/{} pendingResolvedAs={}",
            skladchinaId, finalStatus, collected, paidCount, totalParticipants,
            if (deadlineReached) "expired_no_response" else "released")

        if (skladchina.affectsReputation) {
            applyReputationDeltas(skladchinaId, skladchina.clubId, club.ownerId, closedAt)
        }

        // Только ЭТО закрытие могло породить строки expired_no_response (единственный победитель
        // claim, статусы никогда не покидают терминальные состояния), поэтому запрос даёт точный
        // список для DM «репутация снижена на 40».
        val expiredUserIds = if (deadlineReached && skladchina.affectsReputation) {
            skladchinaRepository.findParticipants(skladchinaId)
                .filter { it.status == SkladchinaParticipantStatus.expired_no_response }
                .map { it.userId }
        } else {
            emptyList()
        }

        eventPublisher.publishEvent(
            SkladchinaClosedEvent(
                skladchinaId = skladchinaId,
                creatorId = skladchina.creatorId,
                clubName = club.name,
                title = skladchina.title,
                finalStatus = finalStatus,
                collectedKopecks = collected,
                totalGoalKopecks = skladchina.totalGoalKopecks,
                paidCount = paidCount,
                participantCount = totalParticipants,
                affectsReputation = skladchina.affectsReputation,
                expiredParticipantUserIds = expiredUserIds
            )
        )
    }

    private fun computeFinalStatus(
        skladchina: Skladchina,
        collected: Long,
        manualClose: Boolean
    ): SkladchinaStatus {
        val goal = skladchina.totalGoalKopecks
        return when {
            manualClose && (goal == null || collected < goal) -> SkladchinaStatus.cancelled
            goal == null && collected > 0 -> SkladchinaStatus.closed_success     // добровольный сбор с любыми платежами
            goal != null && collected >= goal -> SkladchinaStatus.closed_success
            goal != null && collected.toDouble() / goal >= SUCCESS_THRESHOLD -> SkladchinaStatus.closed_success
            else -> SkladchinaStatus.closed_failed
        }
    }

    /**
     * Направляет исходы складчины в ось finance репутационного ledger
     * (идемпотентно — ON CONFLICT + guard reputation_applied на каждого участника).
     * Веса и статусы без строки (declined / released) живут в ReputationPolicy.
     * Анти-фарм правило 1: владелец клуба не набирает очки в собственном клубе.
     * occurredAt = closed_at складчины. reputation_applied проставляется КАЖДОМУ
     * зарезолвленному участнику, включая тех, у кого строки нет, — это означает
     * «репутационное решение по этому участнику принято», а не «в ledger есть строка».
     */
    private fun applyReputationDeltas(
        skladchinaId: UUID,
        clubId: UUID,
        ownerId: UUID,
        occurredAt: OffsetDateTime
    ) {
        val participants = skladchinaRepository.findParticipants(skladchinaId)
        val entries = mutableListOf<LedgerEntry>()
        val toMark = mutableListOf<UUID>()
        participants.forEach { p ->
            if (p.reputationApplied) return@forEach
            val kind = ReputationPolicy.financeKind(p.status)
            if (kind != null && p.userId != ownerId) {
                entries += LedgerEntry(
                    userId = p.userId,
                    clubId = clubId,
                    axis = ReputationAxis.finance,
                    kind = kind,
                    points = ReputationPolicy.pointsFor(kind),
                    occurredAt = occurredAt,
                    sourceType = ReputationSource.skladchina,
                    sourceId = skladchinaId
                )
            }
            toMark += p.userId
        }
        if (entries.isNotEmpty()) reputationService.appendAndRecompute(entries)
        // Отмечаем ПОСЛЕ записи в ledger, чтобы reputation_applied никогда не опережал запись.
        // closeInternal — @Transactional, поэтому отметки и запись коммитятся атомарно
        // (или откатываются вместе) — упавшая запись оставляет reputation_applied=false для ретрая.
        toMark.forEach { skladchinaRepository.markReputationApplied(skladchinaId, it) }
    }

    companion object {
        private const val SUCCESS_THRESHOLD = 0.80     // fixed-режим: собрано ≥80% цели к дедлайну → успех
    }
}
