package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.ClubRoleGuard
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
 * V89: сбор закрывается ровно двумя способами — набранная подтверждёнными деньгами цель
 * ([maybeCloseWhenGoalReached]) и рука организатора ([closeManually]). Ни отметка участника, ни
 * «все ответили», ни наступивший срок закрытием не считаются; сбор, до которого организатор так и
 * не дошёл, шедулер закрывает нейтрально ([neutrallyCloseAbandoned]).
 */
@Service
class SkladchinaLifecycleService(
    private val skladchinaRepository: SkladchinaRepository,
    private val clubRepository: ClubRepository,
    private val clubRoleGuard: ClubRoleGuard,
    private val reputationService: ReputationService,
    private val eventPublisher: ApplicationEventPublisher,
    private val queryService: SkladchinaQueryService
) {
    private val log = LoggerFactory.getLogger(SkladchinaLifecycleService::class.java)

    /**
     * V89 (правка PO 2026-09-08): «Засчитать всех» — организатор одним действием подтверждает все
     * заявленные оплаты, которые ещё не разобрал. Экрана сверки с галками больше нет: решение по
     * каждому участнику принимается кнопками в его строке, а это — массовый вариант того же
     * действия, чтобы сбор на десять человек не требовал десяти тапов.
     *
     * Если подтверждённых денег после этого хватает на цель, сбор закрывается сам
     * ([maybeCloseWhenGoalReached]); иначе он ждёт руки организатора.
     */
    @Transactional
    fun confirmAllClaimed(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = requireManager(skladchinaId, callerId)
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Сбор уже закрыт")
        }
        val now = OffsetDateTime.now()
        val claimed = skladchinaRepository.findParticipants(skladchinaId)
            .filter { it.status == SkladchinaParticipantStatus.paid }
        claimed.forEach { p -> skladchinaRepository.confirmParticipantPayment(skladchinaId, p.userId, now) }
        log.info("Skladchina confirm-all: id={} by={} confirmed={}", skladchinaId, callerId, claimed.size)

        maybeCloseWhenGoalReached(skladchinaId)
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * Сбор закрывается сам ровно в одном случае — **цель набрана подтверждёнными деньгами**
     * (решение PO 2026-09-08). Второй и последний способ закрыть сбор — рука организатора
     * ([closeManually]); «все ответили» и наступивший срок закрытием больше не считаются, они лишь
     * зовут организатора разобрать оплаты.
     *
     * Фаза A когда-то убрала автозакрытие по цели из-за усилителя F5-02 («заяви сумму больше цели
     * — и сбор захлопнется»). Сейчас его нет: цель набирается только теми деньгами, которые
     * организатор сверил, поэтому решение всё равно принимает он.
     *
     * Допуск тот же, что и у итогового статуса ([GOAL_TOLERANCE_KOPECKS]): доли округляются, и
     * недобор до 3 ₽ не должен держать сбор открытым.
     */
    fun maybeCloseWhenGoalReached(skladchinaId: UUID) {
        val skladchina = skladchinaRepository.findById(skladchinaId) ?: return
        if (skladchina.status != SkladchinaStatus.active) return
        val goal = skladchina.totalGoalKopecks ?: return
        val confirmed = skladchinaRepository.sumConfirmedKopecks(skladchinaId)
        if (confirmed <= 0 || confirmed < goal - GOAL_TOLERANCE_KOPECKS) return

        try {
            closeInternal(skladchinaId, closedBy = null, manualClose = false)
        } catch (e: Exception) {
            // Сбой закрытия не должен рушить действие, которое его вызвало: сбор останется
            // активным, а следующее решение организатора попробует снова.
            log.error("Close-on-goal failed for skladchina {}", skladchinaId, e)
        }
    }

    /**
     * V89: зовём организатора разобрать оплаты — сбор дождался всех ответов или своего срока,
     * но заявки ещё висят. Сам по себе этот момент сбор НЕ закрывает (решение PO 2026-09-08):
     * закрытие — либо набранная цель, либо рука организатора. Штамп `confirmation_requested_at`
     * ставится атомарно и только один раз, поэтому второго DM не будет.
     */
    @Transactional
    fun requestConfirmation(skladchinaId: UUID) {
        val skladchina = skladchinaRepository.findById(skladchinaId) ?: return
        if (skladchina.status != SkladchinaStatus.active) return
        if (skladchinaRepository.markConfirmationRequested(skladchinaId, OffsetDateTime.now()) == 0) return
        val club = clubRepository.findById(skladchina.clubId) ?: return

        val claimedCount = skladchinaRepository.countPaidLike(skladchinaId)
        val participantCount = skladchinaRepository.countParticipants(skladchinaId)
        log.info("Skladchina confirmation requested: id={} claimed={}/{}", skladchinaId, claimedCount, participantCount)
        eventPublisher.publishEvent(
            SkladchinaConfirmationRequestedEvent(
                skladchinaId = skladchinaId,
                creatorId = skladchina.creatorId,
                clubName = club.name,
                title = skladchina.title,
                claimedCount = claimedCount,
                participantCount = participantCount
            )
        )
    }

    /** Общая проверка прав на управление сбором: создатель ИЛИ менеджер клуба (У-1). */
    private fun requireManager(skladchinaId: UUID, callerId: UUID): Skladchina {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.creatorId != callerId &&
            !clubRoleGuard.hasCapability(skladchina.clubId, callerId, ClubCapability.MANAGE_SKLADCHINA)
        ) {
            throw ForbiddenException("Only the creator or a club manager can manage this skladchina")
        }
        return skladchina
    }

    /**
     * `POST /close` — «закрыть сбор сейчас, как есть». Заявки, которые организатор так и не
     * разобрал, закрываются нейтрально (`released`): в итог они не идут, но и наказывать за них
     * некого — решения по ним нет. Кнопка нужна для «сбор больше не актуален», обычный финал
     * наступает сам, когда разобрана последняя заявка.
     */
    @Transactional
    fun closeManually(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = requireManager(skladchinaId, callerId)
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is already closed")
        }
        val now = OffsetDateTime.now()
        // Ручное закрытие ДО дедлайна отменяет сбор при недоборе — прежнее поведение; после
        // дедлайна это обычный финал, и итог считается по собранному.
        closeInternal(skladchinaId, closedBy = callerId, manualClose = now.isBefore(skladchina.deadline))
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * Организатор так и не пришёл разбирать оплаты (прошло
     * [SkladchinaConfirmationPolicy.ABANDONED_CONFIRMATION_DAYS] дней после дедлайна) — сбор
     * закрывается НЕЙТРАЛЬНО: молчуны и неразобранные заявки уходят в `released` без строк в
     * леджере. То, что организатор успел подтвердить, своё получает: он прямо сказал «деньги
     * дошли», отнимать за это очки не за что (правка PO 2026-09-08).
     */
    @Transactional
    fun neutrallyCloseAbandoned(skladchinaId: UUID) {
        closeInternal(skladchinaId, closedBy = null, manualClose = false, neutral = true)
    }

    /**
     * Отложенное репутационное решение по одному участнику: минус за неоспоренное отклонение,
     * плюс за спор, решённый в его пользу, ноль за спор, который организатор не разобрал.
     * Вызывается после решения спора и из шедулера по истечении окон. Идемпотентно — `reputation_applied`
     * и UNIQUE-ключ леджера `(user_id, source_type, source_id)` не дают записать дважды.
     */
    @Transactional
    fun applyDeferredReputation(skladchinaId: UUID, userId: UUID) {
        val skladchina = skladchinaRepository.findById(skladchinaId) ?: return
        // Очки даёт только закрытие: по идущему сбору решение организатора ещё можно пересмотреть.
        if (skladchina.status == SkladchinaStatus.active) return
        val participant = skladchinaRepository.findParticipant(skladchinaId, userId) ?: return
        if (participant.reputationApplied) return
        val club = clubRepository.findById(skladchina.clubId) ?: return

        val kind = ReputationPolicy.financeKind(participant.status)
        if (skladchina.affectsReputation && kind != null && userId != club.ownerId) {
            reputationService.appendAndRecompute(
                listOf(
                    LedgerEntry(
                        userId = userId,
                        clubId = skladchina.clubId,
                        axis = ReputationAxis.finance,
                        kind = kind,
                        points = ReputationPolicy.pointsFor(kind),
                        // Событие произошло тогда, когда закрылся сбор, а не когда сработал шедулер:
                        // иначе строка датировалась бы разбором спора и «свежела» без причины.
                        occurredAt = skladchina.closedAt ?: OffsetDateTime.now(),
                        sourceType = ReputationSource.skladchina,
                        sourceId = skladchinaId
                    )
                )
            )
        }
        skladchinaRepository.markReputationApplied(skladchinaId, userId)
        log.info("Skladchina deferred reputation applied: id={} userId={} status={} kind={}",
            skladchinaId, userId, participant.status, kind)
    }

    /**
     * Внутренний хелпер закрытия: атомарно захватывает закрытие, резолвит pending-участников,
     * идемпотентно применяет репутационные дельты, уведомляет организатора.
     *
     * F5-12: переключение статуса — атомарный claim (`UPDATE … WHERE status = 'active'`, паттерн
     * claimEvent) — конкурентный закрывающий (сверка × нейтральное закрытие × каскад) проигрывает claim
     * и делает no-op, поэтому участники резолвятся один раз и SkladchinaClosedEvent стреляет ровно
     * один раз.
     *
     * F5-02: pending-участники получают `expired_no_response` (-40) ТОЛЬКО когда закрытие происходит
     * в момент дедлайна или после него. Раннее закрытие (организатор свёл деньги досрочно)
     * переводит их в `released` — обещание было «ответить до дедлайна», а дедлайн так и не наступил,
     * поэтому строка в ledger не создаётся (financeKind(released) = null).
     *
     * @Transactional: closeInternal вызывается из [confirmAndClose] (self-вызов внутри уже открытой
     * транзакции сверки) и из каскадов. Атомарность гарантирует, что сбой записи в ledger откатывает
     * и claim, и отметки reputation_applied — ретрай может восстановиться (нет осиротевших участников).
     */
    @Transactional
    fun closeInternal(skladchinaId: UUID, closedBy: UUID?, manualClose: Boolean, neutral: Boolean = false) {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) {
            log.warn("closeInternal called on non-active skladchina {}: status={}", skladchinaId, skladchina.status)
            return
        }
        val club = clubRepository.findById(skladchina.clubId)
            ?: throw NotFoundException("Club not found")

        // Итог считается по деньгам, которые организатор СВЕРИЛ: заявка без его решения — это
        // обещание, а не деньги (решение PO 2026-09-08). Исключение — нейтральное закрытие
        // брошенного сбора: там решений нет вовсе, и итог честнее считать по заявленному.
        val collected = if (neutral) {
            skladchinaRepository.sumCollectedKopecks(skladchinaId)
        } else {
            skladchinaRepository.sumConfirmedKopecks(skladchinaId)
        }
        val finalStatus = computeFinalStatus(skladchina, collected, manualClose)
        val closedAt = OffsetDateTime.now()

        if (!skladchinaRepository.claimClose(skladchinaId, finalStatus, closedBy, closedAt)) {
            log.info("Skladchina close claim lost: id={} — already closed concurrently, no-op", skladchinaId)
            return
        }

        val deadlineReached = !closedAt.isBefore(skladchina.deadline)
        if (deadlineReached && !neutral) {
            skladchinaRepository.expirePendingParticipants(skladchinaId)
        } else {
            // Нейтральное закрытие не наказывает даже молчунов: организатор не пришёл разбирать,
            // и цена его отсутствия не перекладывается на участников.
            skladchinaRepository.releasePendingParticipants(skladchinaId)
        }
        // Заявки, до которых организатор не дошёл, закрываются нейтрально — ни очков, ни денег в итог.
        skladchinaRepository.releaseUnsettledClaims(skladchinaId)

        val totalParticipants = skladchinaRepository.countParticipants(skladchinaId)
        val paidCount = skladchinaRepository.countPaidLike(skladchinaId)
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
                expiredParticipantUserIds = expiredUserIds,
                closedWithoutConfirmation = neutral
            )
        )
    }

    private fun computeFinalStatus(
        skladchina: Skladchina,
        collected: Long,
        manualClose: Boolean
    ): SkladchinaStatus {
        val goal = skladchina.totalGoalKopecks
        // Мелкий недобор — не провал: доли округляются, а люди переводят «833 вместо 833,33».
        // Нехватку до 3 ₽ считаем целью, достигнутой (при нулевом сборе успеха нет в любом случае).
        val goalReached = goal != null && collected > 0 && collected >= goal - GOAL_TOLERANCE_KOPECKS
        return when {
            manualClose && !goalReached -> SkladchinaStatus.cancelled
            goal == null && collected > 0 -> SkladchinaStatus.closed_success     // добровольный сбор с любыми платежами
            goalReached -> SkladchinaStatus.closed_success
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
            // V89: у отклонённой оплаты решение отложено — участник ещё может прислать чек.
            // reputation_applied намеренно НЕ ставится: его поставит applyDeferredReputation.
            if (p.status in DEFERRED_OUTCOME_STATUSES) return@forEach
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
        // Статусы, чьё репутационное решение принимается ПОЗЖЕ закрытия: у отклонённой оплаты
        // открыто окно на чек, у оспоренной — ждём организатора.
        private val DEFERRED_OUTCOME_STATUSES = setOf(
            SkladchinaParticipantStatus.payment_rejected,
            SkladchinaParticipantStatus.payment_disputed
        )
        // Максимальная длина причины «платёж не найден» (символов) — как у причины отказа.
        private const val REJECT_NOTE_MAX = 500
        // Статусы, которые список сверки приводит к галкам при закрытии: заявленная оплата и уже
        // вынесенные по ходу сбора решения (их можно переиграть до самого закрытия).
        private val CONFIRMABLE_AT_CLOSE_STATUSES = setOf(
            SkladchinaParticipantStatus.paid,
            SkladchinaParticipantStatus.payment_confirmed,
            SkladchinaParticipantStatus.payment_rejected
        )
        private const val SUCCESS_THRESHOLD = 0.80     // fixed-режим: собрано ≥80% цели к дедлайну → успех
        private const val GOAL_TOLERANCE_KOPECKS = 300L // прощаемый недобор до цели (3 ₽): округление долей
    }
}
