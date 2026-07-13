package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.ClubRoleGuard
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
 * Действия участника + организатора над активной складчиной: отметка оплаты, отказ (и флоу
 * заявки/резолюции REQUIRES_APPROVAL), пометка/снятие пометки организатором, перераспределение
 * дефицита. Каждая мутация выполняется в своей @Transactional и просит [SkladchinaLifecycleService]
 * автозакрыть складчину, когда не остаётся ни одного `pending`-участника. Выделено из бывшего
 * god-`SkladchinaService` по зоне ответственности.
 */
@Service
class SkladchinaPaymentService(
    private val skladchinaRepository: SkladchinaRepository,
    private val clubRepository: ClubRepository,
    private val clubRoleGuard: ClubRoleGuard,
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
            // Идемпотентно — молча возвращаем текущее состояние.
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
            // F5-03: участник вышел из `pending` между нашим чтением и этим UPDATE
            // (конкурентное закрытие истекло/освободило его) — отказываем вместо того,
            // чтобы перезаписать терминальный статус, под который уже выпущена ledger-запись.
            throw ConflictException("Сбор уже закрыт — изменить ответ нельзя. Обновите экран")
        }
        log.info("Skladchina mark-paid: id={} userId={} amount={}", skladchinaId, callerId, effectiveAmountKopecks)

        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
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
        // V28: шаблоны REQUIRES_APPROVAL (split_bill) не допускают мгновенный свободный отказ —
        // участник должен подать заявку, которую резолвит организатор (см. requestDecline).
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
            // F5-03: та же гонка, что и в markPaid — конкурентное закрытие уже резолвило этого участника.
            throw ConflictException("Сбор уже закрыт — изменить ответ нельзя. Обновите экран")
        }
        log.info("Skladchina declined: id={} userId={}", skladchinaId, callerId)
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
        lifecycleService.maybeAutoCloseAfterStateChange(skladchinaId)
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * V28: участник открывает заявку на отказ с причиной (только для шаблонов REQUIRES_APPROVAL,
     * например split_bill). Участник остаётся `pending`, пока организатор не резолвит заявку.
     * Идемпотентно, если заявка уже открыта; отклонённый путь нельзя переоткрыть (участник должен оплатить).
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
            return queryService.getDetail(skladchinaId, callerId) // идемпотентно — заявка уже открыта
        }

        val now = OffsetDateTime.now()
        val updated = skladchinaRepository.requestDecline(
            skladchinaId, callerId, note.take(DECLINE_NOTE_MAX), now
        )
        if (updated == 0) throw ConflictException("Сбор уже закрыт — обновите экран")
        log.info("Skladchina decline-request: id={} userId={}", skladchinaId, callerId)

        // #5: у организатора всегда должно быть полное окно на резолюцию заявки. Если дедлайн
        // ближе, чем это окно, — отодвигаем его так, чтобы было ровно 48ч от текущего момента
        // (extendDeadline только сдвигает дедлайн ВПЕРЁД). Иначе заявка могла бы истечь (→ −40)
        // до того, как кто-то успеет на неё ответить.
        val deadlineExtended = skladchinaRepository.extendDeadline(
            skladchinaId, now.plusHours(DECLINE_RESOLUTION_WINDOW_HOURS)
        )
        // Сдвиг дедлайна виден в «живом статусе сбора» (строка «⏳ До») — перерисовать.
        if (deadlineExtended > 0) {
            eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
        }

        // #6: уведомляем организатора (ЛС + кнопка) ПОСЛЕ коммита — он решает approve/reject.
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
     * V28/V29: организатор резолвит заявку участника на отказ. Approve → `declined` (освобождён от
     * оплаты); reject → путь отказа закрыт (`decline_rejected`), участник остаётся `pending` и должен
     * оплатить. Отклонение ТРЕБУЕТ причину (#7) — организатор обязан обосновать, почему участник
     * всё же должен заплатить; после этого отклонённому участнику отправляется ЛС с этой причиной
     * и кнопкой на пул. Только для создателя. Approve последнего pending-участника может автозакрыть складчину.
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
            eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
            lifecycleService.maybeAutoCloseAfterStateChange(skladchinaId)
        } else {
            // #7: отклонение должно быть обосновано — без причины организатор не может отказать.
            val reason = rejectReason?.trim().orEmpty()
            if (reason.isEmpty()) throw ValidationException("Укажите причину, по которой участник должен оплатить")
            val updated = skladchinaRepository.rejectDeclineRequest(skladchinaId, targetUserId, reason.take(DECLINE_NOTE_MAX))
            if (updated == 0) throw ConflictException("Сбор уже закрыт — обновите экран")
            log.info("Skladchina decline-rejected: id={} target={} by={}", skladchinaId, targetUserId, callerId)

            // Уведомляем отклонённого участника (ЛС + кнопка) ПОСЛЕ коммита — он всё ещё должен оплатить.
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
     * A-2: организатор отмечает участника как оплатившего ("получил наличкой"). Только для
     * фиксированных режимов — назначенная доля записывается одним тапом; у voluntary нет
     * канонической суммы, поэтому участник отмечает её сам. В важной складчине это начисляет +10
     * при закрытии точно так же, как самостоятельная отметка: организатор ручается за наличные
     * (решение PO 2026-06-15), а фарминг ограничен рейт-лимитом 3-важных-на-клуб-в-неделю.
     * Идемпотентно, если участник уже оплатил.
     */
    @Transactional
    fun organizerMarkPaid(skladchinaId: UUID, callerId: UUID, targetUserId: UUID): SkladchinaDetailDto {
        val skladchina = requireActiveAsCreator(skladchinaId, callerId)
        requireFixedMode(skladchina.paymentMode)

        val participant = skladchinaRepository.findParticipant(skladchinaId, targetUserId)
            ?: throw NotFoundException("Participant not found in this skladchina")
        if (participant.status == SkladchinaParticipantStatus.paid) {
            return queryService.getDetail(skladchinaId, callerId) // идемпотентно
        }
        if (participant.status != SkladchinaParticipantStatus.pending) {
            throw ValidationException("Можно отметить оплату только у ожидающего участника")
        }
        val share = participant.expectedAmountKopecks
            ?: throw ValidationException("Сумма участника не назначена")

        val updated = skladchinaRepository.setParticipantPaid(skladchinaId, targetUserId, share, OffsetDateTime.now())
        if (updated == 0) {
            // F5-03: конкурентное закрытие истекло/освободило участника между чтением и UPDATE.
            throw ConflictException("Сбор уже закрыт — изменить нельзя. Обновите экран")
        }
        log.info("Skladchina organizer-mark-paid: id={} target={} by={} amount={}",
            skladchinaId, targetUserId, callerId, share)
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
        lifecycleService.maybeAutoCloseAfterStateChange(skladchinaId)
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * A-2 (toggle): организатор возвращает оплатившего участника обратно в `pending` — отмена
     * случайного тапа. Только для фиксированных режимов (симметрично отметке). Очищает
     * declared_amount/paid_at. НЕ автозакрывает (снятие отметки только увеличивает `pending`,
     * никогда не опустошает его). Безопасно, поскольку репутация применяется только при закрытии —
     * пока складчина активна, нет ledger-записи, которой это могло бы противоречить.
     * Идемпотентно, если участник уже pending.
     */
    @Transactional
    fun organizerUnmarkPaid(skladchinaId: UUID, callerId: UUID, targetUserId: UUID): SkladchinaDetailDto {
        val skladchina = requireActiveAsCreator(skladchinaId, callerId)
        requireFixedMode(skladchina.paymentMode)

        val participant = skladchinaRepository.findParticipant(skladchinaId, targetUserId)
            ?: throw NotFoundException("Participant not found in this skladchina")
        if (participant.status == SkladchinaParticipantStatus.pending) {
            return queryService.getDetail(skladchinaId, callerId) // идемпотентно
        }
        if (participant.status != SkladchinaParticipantStatus.paid) {
            throw ValidationException("Снять отметку можно только у оплатившего участника")
        }

        val updated = skladchinaRepository.revertParticipantToPending(skladchinaId, targetUserId)
        if (updated == 0) {
            throw ConflictException("Сбор уже закрыт — изменить нельзя. Обновите экран")
        }
        log.info("Skladchina organizer-unmark: id={} target={} by={}", skladchinaId, targetUserId, callerId)
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
        return queryService.getDetail(skladchinaId, callerId)
    }

    /** Загружает складчину для орг-мутации (resolve-decline / mark-paid / unmark): должна существовать,
     *  вызывающий — создатель ИЛИ менеджер клуба (У-1, co-organizers), статус — активна. */
    private fun requireActiveAsCreator(skladchinaId: UUID, callerId: UUID): Skladchina {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.creatorId != callerId && !clubRoleGuard.hasCapability(skladchina.clubId, callerId, ClubCapability.MANAGE_SKLADCHINA)) {
            throw ForbiddenException("Only the creator or a club manager can manage this skladchina")
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
     * Фиксированные режимы: у участника всё равно нет выбора (Фаза A полностью убрала это
     * поле из UI — A-1), поэтому сервер записывает собственную назначенную долю дословно и
     * ИГНОРИРУЕТ клиентское значение (теперь оно null). Найдено на staging 2026-06-12: UI
     * округляет копейки до целых рублей (33333 → "333" → 33300), поэтому прежняя строгая
     * проверка `declared == expected` отклоняла каждую честную оплату неделимой доли.
     * Серверный авторитетный учёт держит `collected` точным и всё так же убивает усилитель
     * "заяви ≥ цели, чтобы захлопнуть складчину" из F5-02.
     * Voluntary: заявленная сумма И ЕСТЬ данные — обязательна (null/≤0 → 400), только sanity-cap.
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
        // Sanity-cap для voluntary: 100 000 ₽ — гигиена статистики, а не защита от злоупотреблений.
        private const val DECLARED_AMOUNT_MAX_KOPECKS = 10_000_000L
        // Максимальная длина причины отказа/отклонения заявки (символов).
        private const val DECLINE_NOTE_MAX = 500
        // #5: гарантированное число часов организатору на резолюцию заявки на отказ.
        private const val DECLINE_RESOLUTION_WINDOW_HOURS = 48L
    }
}
