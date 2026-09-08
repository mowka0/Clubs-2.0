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
import com.clubs.common.util.isUploadedImageUrl
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Действия участника + организатора над активной складчиной: отметка оплаты и её снятие, отказ
 * (и флоу заявки/резолюции REQUIRES_APPROVAL), пометка/снятие пометки организатором, спор о
 * неподтверждённой оплате. Каждая мутация выполняется в своей @Transactional. Выделено из бывшего
 * god-`SkladchinaService` по зоне ответственности.
 *
 * V89: закрытие сбора сюда не входит — деньги сводит организатор списком
 * ([SkladchinaLifecycleService.confirmAndClose]), поэтому действие участника больше не может
 * закрыть сбор как побочный эффект.
 */
@Service
class SkladchinaPaymentService(
    private val skladchinaRepository: SkladchinaRepository,
    private val clubRepository: ClubRepository,
    private val clubRoleGuard: ClubRoleGuard,
    private val templateRegistry: SkladchinaTemplateRegistry,
    private val queryService: SkladchinaQueryService,
    private val lifecycleService: SkladchinaLifecycleService,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${s3.base-url:}") private val storageBaseUrl: String
) {
    private val log = LoggerFactory.getLogger(SkladchinaPaymentService::class.java)

    @Transactional
    fun markPaid(skladchinaId: UUID, callerId: UUID, declaredAmountKopecks: Long?): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is not active")
        }
        requireBeforeDeadline(skladchina)
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

        return queryService.getDetail(skladchinaId, callerId)
    }

    @Transactional
    fun decline(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is not active")
        }
        requireBeforeDeadline(skladchina)
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
        lifecycleService.maybeCloseWhenSettled(skladchinaId)
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
        requireBeforeDeadline(skladchina)
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
            lifecycleService.maybeCloseWhenSettled(skladchinaId)
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
        if (participant.status in UNMARKABLE_STATUSES) {
            return queryService.getDetail(skladchinaId, callerId) // идемпотентно (в т.ч. по наличным)
        }
        if (participant.status != SkladchinaParticipantStatus.pending) {
            throw ValidationException("Можно отметить оплату только у ожидающего участника")
        }
        val share = participant.expectedAmountKopecks
            ?: throw ValidationException("Сумма участника не назначена")

        val now = OffsetDateTime.now()
        val updated = skladchinaRepository.setParticipantPaid(skladchinaId, targetUserId, share, now)
        if (updated == 0) {
            // F5-03: конкурентное закрытие истекло/освободило участника между чтением и UPDATE.
            throw ConflictException("Сбор уже закрыт — изменить нельзя. Обновите экран")
        }
        // Наличные организатор пересчитал руками — сверять их второй раз незачем, отметка сразу
        // становится подтверждённой оплатой (иначе он бы тут же нажимал «Засчитать» самому себе).
        skladchinaRepository.confirmParticipantPayment(skladchinaId, targetUserId, now)
        log.info("Skladchina organizer-mark-paid: id={} target={} by={} amount={}",
            skladchinaId, targetUserId, callerId, share)
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
        lifecycleService.maybeCloseWhenSettled(skladchinaId)
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
        if (participant.status !in UNMARKABLE_STATUSES) {
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

    /**
     * V89: участник снимает СВОЮ отметку оплаты («ошибся, платил не по этому сбору»). Разрешено,
     * пока сбор идёт и срок не наступил: после дедлайна начинается сверка, и состав заявок должен
     * быть стабильным, иначе организатор сверяет один список, а закрывает другой.
     * Идемпотентно, если участник уже `pending`.
     */
    @Transactional
    fun unmarkOwnPayment(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Сбор уже закрыт — отметку не изменить")
        }
        if (!skladchina.deadline.isAfter(OffsetDateTime.now())) {
            throw ValidationException("Срок сбора истёк — отметку снимает организатор")
        }
        val participant = skladchinaRepository.findParticipant(skladchinaId, callerId)
            ?: throw ForbiddenException("Not a participant of this skladchina")
        if (participant.status == SkladchinaParticipantStatus.pending) {
            return queryService.getDetail(skladchinaId, callerId) // идемпотентно
        }
        if (participant.status != SkladchinaParticipantStatus.paid) {
            throw ValidationException("Снять можно только собственную отметку об оплате")
        }

        val updated = skladchinaRepository.revertParticipantToPending(skladchinaId, callerId)
        if (updated == 0) throw ConflictException("Сбор уже закрыт — обновите экран")
        log.info("Skladchina self-unmark: id={} userId={}", skladchinaId, callerId)
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * V89: участник оспаривает отклонение оплаты, приложив чек. Спорить можно ТОЛЬКО с чеком —
     * фото или скриншот из банка: организатор сверяет с выпиской, а слово против слова разбирать
     * нечем. Окно — [SkladchinaConfirmationPolicy.RECEIPT_WINDOW_HOURS] от момента отклонения;
     * пока спор открыт, −40 не списывается. Повторно приложить чек можно, пока организатор не решил.
     */
    @Transactional
    fun disputePayment(
        skladchinaId: UUID,
        callerId: UUID,
        receiptUrl: String,
        note: String?
    ): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        val participant = skladchinaRepository.findParticipant(skladchinaId, callerId)
            ?: throw ForbiddenException("Not a participant of this skladchina")

        if (participant.status != SkladchinaParticipantStatus.payment_rejected) {
            throw ValidationException("Оспорить можно только неподтверждённую оплату")
        }
        if (participant.disputeTerminal) {
            throw ValidationException("Организатор уже рассмотрел ваш чек — решение окончательное")
        }
        val rejectedAt = participant.paymentRejectedAt
            ?: throw ValidationException("Оспорить можно только неподтверждённую оплату")
        val deadline = rejectedAt.plusHours(SkladchinaConfirmationPolicy.RECEIPT_WINDOW_HOURS)
        if (OffsetDateTime.now().isAfter(deadline)) {
            throw ValidationException("Срок на чек истёк")
        }

        val cleanUrl = receiptUrl.trim()
        // Чек открывается организатором как ссылка, поэтому принимаем только картинку из нашего
        // загрузчика: иначе сюда подставился бы javascript:/data:-URL или чужой хост.
        if (!isUploadedImageUrl(cleanUrl, storageBaseUrl)) {
            throw ValidationException("Приложите фото или скриншот чека")
        }
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }?.take(RECEIPT_NOTE_MAX)

        val updated = skladchinaRepository.attachPaymentReceipt(
            skladchinaId, callerId, cleanUrl, cleanNote, OffsetDateTime.now()
        )
        if (updated == 0) throw ConflictException("Решение по вашей оплате изменилось — обновите экран")
        log.info("Skladchina payment disputed: id={} userId={}", skladchinaId, callerId)

        val clubName = clubRepository.findById(skladchina.clubId)?.name ?: ""
        eventPublisher.publishEvent(
            SkladchinaPaymentDisputedEvent(
                skladchinaId = skladchinaId,
                creatorId = skladchina.creatorId,
                disputerUserId = callerId,
                clubName = clubName,
                title = skladchina.title,
                note = cleanNote
            )
        )
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * V89: решение организатора по оплате участника. Работает и **по ходу сбора** (заявку можно
     * сверить сразу, не дожидаясь закрытия — просьба PO 2026-09-08), и при разборе присланного чека.
     *
     * Засчитал → `payment_confirmed`; не засчитал → `payment_rejected`, и у участника открывается
     * окно на чек. Отказ **по чеку** окончателен (`dispute_terminal`) — арбитра над организатором
     * нет, как и в спорах о явке, иначе спор можно было бы гонять по кругу.
     *
     * Репутация пишется только по закрытому сбору: пока сбор идёт, решение живёт в статусе
     * участника и может быть пересмотрено, а очки начисляет закрытие.
     */
    @Transactional
    fun resolveParticipantPayment(
        skladchinaId: UUID,
        callerId: UUID,
        targetUserId: UUID,
        accept: Boolean,
        reason: String? = null
    ): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.creatorId != callerId &&
            !clubRoleGuard.hasCapability(skladchina.clubId, callerId, ClubCapability.MANAGE_SKLADCHINA)
        ) {
            throw ForbiddenException("Only the creator or a club manager can manage this skladchina")
        }
        val participant = skladchinaRepository.findParticipant(skladchinaId, targetUserId)
            ?: throw NotFoundException("Participant not found in this skladchina")
        if (participant.status !in RESOLVABLE_PAYMENT_STATUSES) {
            throw ValidationException("У этого участника нечего сверять — оплата не заявлена")
        }
        // Идемпотентность: повторный тап по тому же решению ничего не меняет (и не должен
        // передвигать окно на чек, начиная отсчёт заново).
        val alreadyResolvedTheSameWay =
            (accept && participant.status == SkladchinaParticipantStatus.payment_confirmed) ||
                (!accept && participant.status == SkladchinaParticipantStatus.payment_rejected)
        if (alreadyResolvedTheSameWay) return queryService.getDetail(skladchinaId, callerId)
        val wasDispute = participant.status == SkladchinaParticipantStatus.payment_disputed

        val now = OffsetDateTime.now()
        val updated = if (accept) {
            skladchinaRepository.confirmParticipantPayment(skladchinaId, targetUserId, now)
        } else {
            skladchinaRepository.rejectParticipantPayment(
                skladchinaId, targetUserId, now,
                reason?.trim()?.takeIf { it.isNotEmpty() }?.take(REJECT_NOTE_MAX)
                    ?: participant.paymentRejectNote,
                terminal = wasDispute
            )
        }
        if (updated == 0) throw ConflictException("Решение по этой оплате изменилось — обновите экран")
        log.info("Skladchina payment resolved: id={} target={} by={} accepted={} fromDispute={}",
            skladchinaId, targetUserId, callerId, accept, wasDispute)

        // Пока сбор идёт, очки не начисляются: решение ещё можно пересмотреть, а репутацию
        // эмитит закрытие. По закрытому сбору решение окончательное — применяем сразу.
        if (skladchina.status != SkladchinaStatus.active) {
            lifecycleService.applyDeferredReputation(skladchinaId, targetUserId)
        }
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
        // Разобрана последняя заявка и все ответили — отдельный шаг «закрыть» не нужен.
        lifecycleService.maybeCloseWhenSettled(skladchinaId)

        val clubName = clubRepository.findById(skladchina.clubId)?.name ?: ""
        if (wasDispute) {
            eventPublisher.publishEvent(
                SkladchinaPaymentDisputeResolvedEvent(
                    skladchinaId = skladchinaId,
                    participantUserId = targetUserId,
                    clubName = clubName,
                    title = skladchina.title,
                    accepted = accept,
                    affectsReputation = skladchina.affectsReputation
                )
            )
        } else if (!accept) {
            // Отклонение по ходу сбора: участник узнаёт сразу, окно на чек тикает с этой секунды.
            eventPublisher.publishEvent(
                SkladchinaPaymentRejectedEvent(
                    skladchinaId = skladchinaId,
                    participantUserId = targetUserId,
                    clubName = clubName,
                    title = skladchina.title,
                    reason = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(REJECT_NOTE_MAX),
                    receiptDeadline = now.plusHours(SkladchinaConfirmationPolicy.RECEIPT_WINDOW_HOURS),
                    affectsReputation = skladchina.affectsReputation
                )
            )
        }
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * V89: после дедлайна ответы участников закрыты. Раньше это обеспечивал шедулер — он закрывал
     * сбор ровно в срок; теперь сбор живёт до сверки организатором, и без этой проверки можно было
     * бы «оплатить» через неделю после срока и уйти от −40, пока организатор не смотрит.
     */
    private fun requireBeforeDeadline(skladchina: Skladchina) {
        if (!skladchina.deadline.isAfter(OffsetDateTime.now())) {
            throw ValidationException("Срок сбора истёк — ответ принимает организатор")
        }
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
        // Максимальная длина комментария участника к чеку (символов).
        private const val RECEIPT_NOTE_MAX = 500
        // Максимальная длина причины «платёж не найден» (символов).
        private const val REJECT_NOTE_MAX = 500
        // Статусы, по которым организатору есть что решать: заявленная оплата (сверка по ходу или
        // при закрытии), оспоренная чеком, а также уже вынесенное решение — его можно пересмотреть,
        // пока сбор не закрыт и очки не начислены.
        // Отметку об оплате можно снять и после того, как она стала подтверждённой: наличные
        // организатор подтверждает сам, и его же ошибка не должна требовать «отклонения» с чеком.
        private val UNMARKABLE_STATUSES = setOf(
            SkladchinaParticipantStatus.paid,
            SkladchinaParticipantStatus.payment_confirmed
        )
        private val RESOLVABLE_PAYMENT_STATUSES = setOf(
            SkladchinaParticipantStatus.paid,
            SkladchinaParticipantStatus.payment_confirmed,
            SkladchinaParticipantStatus.payment_rejected,
            SkladchinaParticipantStatus.payment_disputed
        )
    }
}
