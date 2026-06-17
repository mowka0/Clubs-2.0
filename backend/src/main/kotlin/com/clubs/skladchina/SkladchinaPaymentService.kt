package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.skladchina.template.DeclinePolicy
import com.clubs.skladchina.template.SkladchinaTemplateRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Participant + organizer payment actions on an active skladchina: mark-paid, decline (and the
 * REQUIRES_APPROVAL request/resolve flow), organizer mark/un-mark, deficit redistribution. Each
 * mutation runs in its own @Transactional and asks [SkladchinaLifecycleService] to auto-close when
 * no `pending` participant remains. Split out of the former god-`SkladchinaService` by responsibility.
 */
@Service
class SkladchinaPaymentService(
    private val skladchinaRepository: SkladchinaRepository,
    private val clubRepository: ClubRepository,
    private val templateRegistry: SkladchinaTemplateRegistry,
    private val queryService: SkladchinaQueryService,
    private val lifecycleService: SkladchinaLifecycleService,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(SkladchinaPaymentService::class.java)

    @Transactional
    fun markPaid(skladchinaId: UUID, callerId: UUID, declaredAmountKopecks: Long?): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is not active")
        }
        val participant = skladchinaRepository.findParticipant(skladchinaId, callerId)
            ?: throw ForbiddenException("Not a participant of this skladchina")

        if (participant.status == SkladchinaParticipantStatus.paid) {
            // Idempotent — silently return current state.
            return queryService.getDetail(skladchinaId, callerId)
        }
        if (participant.status == SkladchinaParticipantStatus.declined) {
            throw ValidationException("You have already declined this skladchina")
        }

        val effectiveAmountKopecks = resolveDeclaredAmount(
            skladchina.paymentMode, participant.expectedAmountKopecks, declaredAmountKopecks
        )

        val updated = skladchinaRepository.setParticipantPaid(
            skladchinaId, callerId, effectiveAmountKopecks, OffsetDateTime.now()
        )
        if (updated == 0) {
            // F5-03: the participant left `pending` between our read and this UPDATE
            // (a concurrent close expired/released them) — refuse instead of
            // overwriting the terminal status its ledger row was emitted for.
            throw ConflictException("Сбор уже закрыт — изменить ответ нельзя. Обновите экран")
        }
        log.info("Skladchina mark-paid: id={} userId={} amount={}", skladchinaId, callerId, effectiveAmountKopecks)

        lifecycleService.maybeAutoCloseAfterStateChange(skladchinaId)

        return queryService.getDetail(skladchinaId, callerId)
    }

    @Transactional
    fun decline(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is not active")
        }
        // V28: REQUIRES_APPROVAL templates (split_bill) don't allow an instant free decline —
        // the participant must submit a request the organizer resolves (see requestDecline).
        if (templateRegistry.forType(skladchina.template).declinePolicy == DeclinePolicy.REQUIRES_APPROVAL) {
            throw ValidationException("Для этого сбора отказ оформляется заявкой с причиной")
        }
        val participant = skladchinaRepository.findParticipant(skladchinaId, callerId)
            ?: throw ForbiddenException("Not a participant of this skladchina")
        if (participant.status == SkladchinaParticipantStatus.declined) {
            throw ValidationException("Already declined")
        }
        if (participant.status == SkladchinaParticipantStatus.paid) {
            throw ValidationException("Already paid, cannot decline")
        }
        val updated = skladchinaRepository.setParticipantDeclined(skladchinaId, callerId, OffsetDateTime.now())
        if (updated == 0) {
            // F5-03: same race as markPaid — concurrent close already resolved this participant.
            throw ConflictException("Сбор уже закрыт — изменить ответ нельзя. Обновите экран")
        }
        log.info("Skladchina declined: id={} userId={}", skladchinaId, callerId)
        lifecycleService.maybeAutoCloseAfterStateChange(skladchinaId)
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * V28: a participant opens a decline request with a reason (REQUIRES_APPROVAL templates only,
     * e.g. split_bill). The participant stays `pending` until the organizer resolves it. Idempotent
     * when a request is already open; a rejected path can't be reopened (the participant must pay).
     */
    @Transactional
    fun requestDecline(skladchinaId: UUID, callerId: UUID, reason: String): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) throw ValidationException("Skladchina is not active")
        if (templateRegistry.forType(skladchina.template).declinePolicy != DeclinePolicy.REQUIRES_APPROVAL) {
            throw ValidationException("Этот сбор не поддерживает заявки на отказ")
        }
        val note = reason.trim()
        if (note.isEmpty()) throw ValidationException("Укажите причину отказа")

        val participant = skladchinaRepository.findParticipant(skladchinaId, callerId)
            ?: throw ForbiddenException("Not a participant of this skladchina")
        if (participant.status != SkladchinaParticipantStatus.pending) {
            throw ValidationException("Заявку на отказ можно подать только до оплаты или ответа")
        }
        if (participant.declineRejected) {
            throw ValidationException("Ваш отказ отклонён — нужно оплатить счёт")
        }
        if (participant.declineRequestedAt != null) {
            return queryService.getDetail(skladchinaId, callerId) // idempotent — request already open
        }

        val now = OffsetDateTime.now()
        val updated = skladchinaRepository.requestDecline(
            skladchinaId, callerId, note.take(DECLINE_NOTE_MAX), now
        )
        if (updated == 0) throw ConflictException("Сбор уже закрыт — обновите экран")
        log.info("Skladchina decline-request: id={} userId={}", skladchinaId, callerId)

        // #5: the organizer must always have a full window to resolve the request. If the deadline
        // is closer than that, push it out so there are exactly 48h from now (extendDeadline only
        // ever moves it OUT). Otherwise the request could expire (→ −40) before anyone can answer.
        skladchinaRepository.extendDeadline(skladchinaId, now.plusHours(DECLINE_RESOLUTION_WINDOW_HOURS))

        // #6: notify the organizer (DM + button) AFTER commit — they decide approve/reject.
        val clubName = clubRepository.findById(skladchina.clubId)?.name ?: ""
        eventPublisher.publishEvent(
            SkladchinaDeclineRequestedEvent(
                skladchinaId = skladchinaId,
                creatorId = skladchina.creatorId,
                requesterUserId = callerId,
                clubName = clubName,
                title = skladchina.title,
                reason = note.take(DECLINE_NOTE_MAX)
            )
        )
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * V28/V29: the organizer resolves a participant's decline request. Approve → `declined`
     * (excused); reject → decline path closed (`decline_rejected`), participant stays `pending` and
     * must pay. Rejecting REQUIRES a reason (#7) — the organizer must justify why the participant
     * still owes; the rejected participant is then DM'd that reason with a button to the pool.
     * Creator-only. Approving the last pending participant can auto-close the skladchina.
     */
    @Transactional
    fun resolveDecline(
        skladchinaId: UUID,
        callerId: UUID,
        targetUserId: UUID,
        approve: Boolean,
        rejectReason: String?
    ): SkladchinaDetailDto {
        val skladchina = requireActiveAsCreator(skladchinaId, callerId)
        val participant = skladchinaRepository.findParticipant(skladchinaId, targetUserId)
            ?: throw NotFoundException("Participant not found in this skladchina")
        if (participant.status != SkladchinaParticipantStatus.pending || participant.declineRequestedAt == null) {
            throw ValidationException("Нет открытой заявки на отказ у этого участника")
        }

        if (approve) {
            val updated = skladchinaRepository.setParticipantDeclined(skladchinaId, targetUserId, OffsetDateTime.now())
            if (updated == 0) throw ConflictException("Сбор уже закрыт — обновите экран")
            log.info("Skladchina decline-approved: id={} target={} by={}", skladchinaId, targetUserId, callerId)
            lifecycleService.maybeAutoCloseAfterStateChange(skladchinaId)
        } else {
            // #7: a rejection must be justified — without a reason the organizer can't refuse.
            val reason = rejectReason?.trim().orEmpty()
            if (reason.isEmpty()) throw ValidationException("Укажите причину, по которой участник должен оплатить")
            val updated = skladchinaRepository.rejectDeclineRequest(skladchinaId, targetUserId, reason.take(DECLINE_NOTE_MAX))
            if (updated == 0) throw ConflictException("Сбор уже закрыт — обновите экран")
            log.info("Skladchina decline-rejected: id={} target={} by={}", skladchinaId, targetUserId, callerId)

            // Notify the rejected participant (DM + button) AFTER commit — they must still pay.
            val clubName = clubRepository.findById(skladchina.clubId)?.name ?: ""
            eventPublisher.publishEvent(
                SkladchinaDeclineRejectedEvent(
                    skladchinaId = skladchinaId,
                    participantUserId = targetUserId,
                    clubName = clubName,
                    title = skladchina.title,
                    reason = reason.take(DECLINE_NOTE_MAX)
                )
            )
        }
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * A-2: the organizer marks a participant paid ("получил наличкой"). Fixed modes only —
     * the assigned share is recorded in one tap; voluntary has no canonical amount, so the
     * participant marks it themselves. In an important skladchina this accrues +10 at close
     * exactly like a self-mark: the organizer vouches for the cash (PO decision 2026-06-15),
     * and farming is bounded by the 3-important-per-club-per-week rate limit. Idempotent
     * when the participant is already paid.
     */
    @Transactional
    fun organizerMarkPaid(skladchinaId: UUID, callerId: UUID, targetUserId: UUID): SkladchinaDetailDto {
        val skladchina = requireActiveAsCreator(skladchinaId, callerId)
        requireFixedMode(skladchina.paymentMode)

        val participant = skladchinaRepository.findParticipant(skladchinaId, targetUserId)
            ?: throw NotFoundException("Participant not found in this skladchina")
        if (participant.status == SkladchinaParticipantStatus.paid) {
            return queryService.getDetail(skladchinaId, callerId) // idempotent
        }
        if (participant.status != SkladchinaParticipantStatus.pending) {
            throw ValidationException("Можно отметить оплату только у ожидающего участника")
        }
        val share = participant.expectedAmountKopecks
            ?: throw ValidationException("Сумма участника не назначена")

        val updated = skladchinaRepository.setParticipantPaid(skladchinaId, targetUserId, share, OffsetDateTime.now())
        if (updated == 0) {
            // F5-03: a concurrent close expired/released the participant between read and UPDATE.
            throw ConflictException("Сбор уже закрыт — изменить нельзя. Обновите экран")
        }
        log.info("Skladchina organizer-mark-paid: id={} target={} by={} amount={}",
            skladchinaId, targetUserId, callerId, share)
        lifecycleService.maybeAutoCloseAfterStateChange(skladchinaId)
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * A-2 (toggle): the organizer reverts a paid participant back to `pending` — undoing a
     * mis-tap. Fixed modes only (symmetric with the mark). Clears declared_amount/paid_at.
     * Does NOT auto-close (un-marking only grows `pending`, never empties it). Safe because
     * reputation is applied only at close — while active there is no ledger row to contradict.
     * Idempotent when the participant is already pending.
     */
    @Transactional
    fun organizerUnmarkPaid(skladchinaId: UUID, callerId: UUID, targetUserId: UUID): SkladchinaDetailDto {
        val skladchina = requireActiveAsCreator(skladchinaId, callerId)
        requireFixedMode(skladchina.paymentMode)

        val participant = skladchinaRepository.findParticipant(skladchinaId, targetUserId)
            ?: throw NotFoundException("Participant not found in this skladchina")
        if (participant.status == SkladchinaParticipantStatus.pending) {
            return queryService.getDetail(skladchinaId, callerId) // idempotent
        }
        if (participant.status != SkladchinaParticipantStatus.paid) {
            throw ValidationException("Снять отметку можно только у оплатившего участника")
        }

        val updated = skladchinaRepository.revertParticipantToPending(skladchinaId, targetUserId)
        if (updated == 0) {
            throw ConflictException("Сбор уже закрыт — изменить нельзя. Обновите экран")
        }
        log.info("Skladchina organizer-unmark: id={} target={} by={}", skladchinaId, targetUserId, callerId)
        return queryService.getDetail(skladchinaId, callerId)
    }

    /** Loads a skladchina for an organizer-only mutation: must exist, caller must be creator, must be active. */
    private fun requireActiveAsCreator(skladchinaId: UUID, callerId: UUID): Skladchina {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.creatorId != callerId) {
            throw ForbiddenException("Only the organizer can manage this skladchina")
        }
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is not active")
        }
        return skladchina
    }

    private fun requireFixedMode(mode: SkladchinaMode) {
        if (mode == SkladchinaMode.voluntary) {
            throw ValidationException("Отметка оплаты организатором доступна только для сборов с фиксированными суммами")
        }
    }

    /**
     * Fixed modes: the participant has no choice anyway (Phase A removed the UI field
     * entirely — A-1), so the server records its own assigned share verbatim and
     * IGNORES the client value (which is now null). Found on staging 2026-06-12: the
     * UI rounds kopecks to whole rubles (33333 → "333" → 33300), so the previous
     * strict `declared == expected` check rejected every honest payment of a
     * non-divisible share. Server-authoritative recording keeps `collected` exact and
     * still kills the "declare ≥ goal to slam the skladchina shut" amplifier of F5-02.
     * Voluntary: the declared amount IS the data — required (null/≤0 → 400), sanity cap only.
     */
    private fun resolveDeclaredAmount(mode: SkladchinaMode, expectedAmountKopecks: Long?, declaredAmountKopecks: Long?): Long =
        when (mode) {
            SkladchinaMode.fixed_equal, SkladchinaMode.fixed_individual ->
                expectedAmountKopecks
                    ?: throw ValidationException("Сумма участника не назначена — обратитесь к организатору")
            SkladchinaMode.voluntary -> {
                val declared = declaredAmountKopecks
                    ?: throw ValidationException("Укажите сумму оплаты")
                if (declared <= 0) throw ValidationException("Сумма должна быть положительной")
                if (declared > DECLARED_AMOUNT_MAX_KOPECKS) {
                    throw ValidationException(
                        "Сумма не может превышать ${DECLARED_AMOUNT_MAX_KOPECKS / 100} ₽"
                    )
                }
                declared
            }
        }

    companion object {
        // Voluntary sanity cap: 100 000 ₽ — statistics hygiene, not an anti-abuse bound.
        private const val DECLARED_AMOUNT_MAX_KOPECKS = 10_000_000L
        private const val DECLINE_NOTE_MAX = 500
        // #5: guarantee the organizer this many hours to resolve a decline request.
        private const val DECLINE_RESOLUTION_WINDOW_HOURS = 48L
    }
}
