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
 * V89 (редакция PO 2026-09-09): сбор закрывается, когда по каждому участнику есть решение
 * ([maybeCloseWhenSettled]) — либо рукой организатора ([closeManually]), если сбор потерял смысл.
 * Наступивший срок закрытием не считается: он лишь закрывает ответы участников и зовёт
 * организатора свести сбор. Не свёл за неделю — [autoSettleAbandoned] сводит за него.
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
     * Если после этого нерешённых участников не осталось, сбор закрывается сам
     * ([maybeCloseWhenSettled]).
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

        maybeCloseWhenSettled(skladchinaId)
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * Сбор закрывается сам, когда **не осталось участников без решения** (редакция PO 2026-09-09):
     * все либо оплатили и сверены, либо отказались, либо получили «не дошёл». Вызывается после
     * каждой мутации статуса участника — двумя счётчиками, поэтому дёшево.
     *
     * Набранная цель отдельным поводом для закрытия быть перестала: сбор, где деньги собраны, а
     * половина участников не разобрана, закрывать рано — организатору ещё выносить по ним решение.
     * Спор (`payment_disputed`) закрытию не мешает: у него свой таймер, и исход добивается
     * шедулером уже по закрытому сбору.
     */
    fun maybeCloseWhenSettled(skladchinaId: UUID) {
        val skladchina = skladchinaRepository.findById(skladchinaId) ?: return
        if (skladchina.status != SkladchinaStatus.active) return
        if (skladchinaRepository.countParticipantsPending(skladchinaId) > 0) return
        if (skladchinaRepository.countClaimedUnsettled(skladchinaId) > 0) return

        try {
            closeInternal(skladchinaId, closedBy = null, manualClose = false)
        } catch (e: Exception) {
            // Сбой закрытия не должен рушить действие, которое его вызвало: сбор останется
            // активным, а следующее решение организатора попробует снова.
            log.error("Close-when-settled failed for skladchina {}", skladchinaId, e)
        }
    }

    /**
     * V89: зовём организатора свести сбор — срок вышел (или все уже ответили), а решения по
     * участникам ещё не по всем. Сам по себе этот момент сбор НЕ закрывает: закрытие наступит,
     * когда организатор разберёт последнего. Штамп `confirmation_requested_at` ставится атомарно
     * и только один раз, поэтому второго DM не будет.
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
        // Пост в чате клуба показывает «⏳ До <срок>» — в этот момент строка должна смениться на
        // «срок вышел», иначе она провисит прошедшей датой до первого действия организатора.
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
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
     * `POST /close` — одна кнопка с двумя смыслами, по обе стороны от срока:
     *
     * - **до срока** это «сбор больше не актуален»: недобор делает сбор отменённым, а всё
     *   нерешённое закрывается нейтрально — обещание было «ответить до срока», и срок не наступил;
     * - **после срока** это «свести сбор» — тот же итог, к которому через неделю пришёл бы
     *   [autoSettleAbandoned]: заявкам верим, молчание стоит −40. Организатор, считающий заявку
     *   пустой, говорит это явно кнопкой «Не дошёл» в строке участника.
     */
    @Transactional
    fun closeManually(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = requireManager(skladchinaId, callerId)
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is already closed")
        }
        val now = OffsetDateTime.now()
        val beforeDeadline = now.isBefore(skladchina.deadline)
        if (!beforeDeadline) settleUnresolvedAnswers(skladchinaId, now)
        closeInternal(skladchinaId, closedBy = callerId, manualClose = beforeDeadline)
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * Организатор так и не свёл сбор (прошло
     * [SkladchinaConfirmationPolicy.ABANDONED_CONFIRMATION_DAYS] дней после дедлайна) — сводим за
     * него по правилу «кто ответил, тому верим» (решение PO 2026-09-09):
     *
     * - заявленная оплата засчитывается (`payment_confirmed`, +10) — у организатора были неделя и
     *   DM, чтобы сказать «не дошло»;
     * - неразобранная заявка на отказ считается одобренной (`declined`, ноль) — человек ответил,
     *   дальше был не его ход;
     * - молчание остаётся молчанием: [closeInternal] переведёт его в `expired_no_response` (−40).
     *
     * Так цена −40 за неответ перестала зависеть от расторопности организатора — она наступает
     * максимум через неделю после срока в любом случае.
     */
    @Transactional
    fun autoSettleAbandoned(skladchinaId: UUID) {
        val now = OffsetDateTime.now()
        settleUnresolvedAnswers(skladchinaId, now)
        closeInternal(skladchinaId, closedBy = null, manualClose = false, autoSettled = true)
    }

    /**
     * Сведение ответов, по которым организатор решения не принял: «кто ответил, тому верим».
     * Заявленная оплата становится подтверждённой, неразобранная заявка на отказ — одобренной.
     * Молчунов не трогает: их переводит [closeInternal] — в `expired_no_response`, если срок
     * наступил. Общее для руки организатора после срока и для [autoSettleAbandoned].
     */
    private fun settleUnresolvedAnswers(skladchinaId: UUID, now: OffsetDateTime) {
        skladchinaRepository.findParticipants(skladchinaId).forEach { p ->
            when {
                p.status == SkladchinaParticipantStatus.paid ->
                    skladchinaRepository.confirmParticipantPayment(skladchinaId, p.userId, now)
                p.status == SkladchinaParticipantStatus.pending && p.declineRequestedAt != null ->
                    skladchinaRepository.setParticipantDeclined(skladchinaId, p.userId, now)
            }
        }
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
     * [autoSettled] — сбор свёл шедулер, а не человек: влияет только на текст DM организатору.
     *
     * @Transactional: closeInternal вызывается из [confirmAndClose] (self-вызов внутри уже открытой
     * транзакции сверки) и из каскадов. Атомарность гарантирует, что сбой записи в ledger откатывает
     * и claim, и отметки reputation_applied — ретрай может восстановиться (нет осиротевших участников).
     */
    @Transactional
    fun closeInternal(skladchinaId: UUID, closedBy: UUID?, manualClose: Boolean, autoSettled: Boolean = false) {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) {
            log.warn("closeInternal called on non-active skladchina {}: status={}", skladchinaId, skladchina.status)
            return
        }
        val club = clubRepository.findById(skladchina.clubId)
            ?: throw NotFoundException("Club not found")

        // Итог считается по деньгам, которые организатор СВЕРИЛ: заявка без его решения — это
        // обещание, а не деньги (решение PO 2026-09-08). Брошенный сбор к этому моменту уже сведён
        // ([autoSettleAbandoned] засчитал заявки), поэтому отдельной ветки «считать по заявленному»
        // здесь больше нет.
        val collected = skladchinaRepository.sumConfirmedKopecks(skladchinaId)
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
                autoSettled = autoSettled
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
