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
 * V89: действия участника больше НИКОГДА не закрывают сбор — ни «все ответили», ни наступивший
 * дедлайн. Закрытие всегда проходит через сверку денег организатором ([confirmAndClose]), а сбор,
 * до которого организатор не дошёл, шедулер закрывает нейтрально ([neutrallyCloseAbandoned]).
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
     * V89: сверка оплат при закрытии. Организатор снимает галки с тех, от кого денег не пришло
     * ([rejectedUserIds]), остальные заявленные оплаты засчитываются — и сбор закрывается.
     * Пустой список = «подтвердить всех» (этим же путём работает старый `POST /close`).
     *
     * Отклонённый участник НЕ получает −40 сразу: у него есть окно на чек
     * ([SkladchinaConfirmationPolicy.RECEIPT_WINDOW_HOURS]), поэтому его репутационное решение
     * откладывается (`reputation_applied` остаётся false, см. [applyReputationDeltas]).
     */
    @Transactional
    fun confirmAndClose(
        skladchinaId: UUID,
        callerId: UUID,
        rejectedUserIds: Set<UUID>,
        rejectNotes: Map<UUID, String>
    ): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        // У-1 (co-organizers): сверять деньги может создатель ИЛИ менеджер клуба.
        if (skladchina.creatorId != callerId &&
            !clubRoleGuard.hasCapability(skladchina.clubId, callerId, ClubCapability.MANAGE_SKLADCHINA)
        ) {
            throw ForbiddenException("Only the creator or a club manager can close skladchina")
        }
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is already closed")
        }

        val now = OffsetDateTime.now()
        // Сверять можно только оплаты: id, которого нет среди них (посторонний, отказавшийся,
        // молчун), в списке отклонённых просто игнорируется. Уже сверенные по ходу сбора строки
        // тоже здесь — галка в списке остаётся последним словом организатора. Спор с чеком не
        // трогаем: он разбирается отдельно и не должен закрыться «заодно».
        val (rejected, confirmed) = skladchinaRepository.findParticipants(skladchinaId)
            .filter { it.status in CONFIRMABLE_AT_CLOSE_STATUSES }
            .partition { it.userId in rejectedUserIds }
        rejected.forEach { p ->
            skladchinaRepository.rejectParticipantPayment(
                skladchinaId, p.userId, now,
                rejectNotes[p.userId]?.trim()?.takeIf { it.isNotEmpty() }?.take(REJECT_NOTE_MAX),
                terminal = false
            )
        }
        confirmed.forEach { p -> skladchinaRepository.confirmParticipantPayment(skladchinaId, p.userId, now) }
        log.info("Skladchina payments confirmed: id={} by={} confirmed={} rejected={}",
            skladchinaId, callerId, confirmed.size, rejected.size)

        // Отклонённому нужно узнать об этом вовремя: окно на чек тикает с этой секунды.
        val clubName = clubRepository.findById(skladchina.clubId)?.name ?: ""
        rejected.forEach { p ->
            eventPublisher.publishEvent(
                SkladchinaPaymentRejectedEvent(
                    skladchinaId = skladchinaId,
                    participantUserId = p.userId,
                    clubName = clubName,
                    title = skladchina.title,
                    reason = rejectNotes[p.userId]?.trim()?.takeIf { it.isNotEmpty() }?.take(REJECT_NOTE_MAX),
                    receiptDeadline = now.plusHours(SkladchinaConfirmationPolicy.RECEIPT_WINDOW_HOURS),
                    affectsReputation = skladchina.affectsReputation
                )
            )
        }

        // Ручным (и потому «отменяющим при недоборе») считается только закрытие ДО дедлайна:
        // сверка после срока — обычный финал сбора, его итог считается по собранной сумме.
        closeInternal(skladchinaId, closedBy = callerId, manualClose = now.isBefore(skladchina.deadline))
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * `POST /close` — «подтвердить всех заявивших и закрыть»: частный случай [confirmAndClose]
     * с пустым списком отклонённых. Отдельная кнопка «Закрыть сбор» у организатора ведёт на тот же
     * экран сверки, поэтому расходиться этим двум путям незачем.
     */
    @Transactional
    fun closeManually(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto =
        confirmAndClose(skladchinaId, callerId, emptySet(), emptyMap())

    /**
     * V89: зовём организатора сверить деньги — сбор дождался всех ответов или своего срока.
     * Штамп `confirmation_requested_at` ставится атомарно и только один раз: проигравший гонку
     * (или повторный проход шедулера) молча ничего не делает, второго DM не будет.
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

    /**
     * Организатор так и не пришёл сверять деньги (прошло
     * [SkladchinaConfirmationPolicy.ABANDONED_CONFIRMATION_DAYS] дней после дедлайна) — сбор
     * закрывается НЕЙТРАЛЬНО: ни плюсов, ни минусов никому, включая промолчавших. Решение PO:
     * цена отсутствия организатора не перекладывается на участников. Прямая аналогия с
     * `neutrallyFinalizeUnmarkedBefore` у встреч.
     *
     * Заявленные оплаты остаются в статусе `paid` — сбор так и закрылся несверенным, и собранная
     * сумма продолжает отражать то, что люди заявили.
     */
    @Transactional
    fun neutrallyCloseAbandoned(skladchinaId: UUID) {
        val skladchina = skladchinaRepository.findById(skladchinaId) ?: return
        if (skladchina.status != SkladchinaStatus.active) return
        val club = clubRepository.findById(skladchina.clubId) ?: return

        val collected = skladchinaRepository.sumCollectedKopecks(skladchinaId)
        val closedAt = OffsetDateTime.now()
        val finalStatus = computeFinalStatus(skladchina, collected, manualClose = false)
        if (!skladchinaRepository.claimClose(skladchinaId, finalStatus, closedBy = null, closedAt = closedAt)) {
            log.info("Neutral close claim lost: id={} — already closed concurrently, no-op", skladchinaId)
            return
        }
        skladchinaRepository.releasePendingParticipants(skladchinaId)
        // Репутационное решение принято по всем — оно нулевое, строк в леджере не появляется.
        skladchinaRepository.markReputationAppliedForAll(skladchinaId)

        val paidCount = skladchinaRepository.countPaidLike(skladchinaId)
        val totalParticipants = skladchinaRepository.countParticipants(skladchinaId)
        log.info("Skladchina closed neutrally (organizer never confirmed): id={} status={} paid={}/{}",
            skladchinaId, finalStatus, paidCount, totalParticipants)

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
                closedWithoutConfirmation = true
            )
        )
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
