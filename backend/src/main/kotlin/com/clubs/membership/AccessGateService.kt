package com.clubs.membership

import com.clubs.application.ApplicationRepository
import com.clubs.bot.NotificationService
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.event.EventResponseRepository
import com.clubs.event.EventRosterChangedEvent
import com.clubs.event.WaitlistPromotedEvent
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.skladchina.SkladchinaProgressChangedEvent
import com.clubs.skladchina.SkladchinaRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Шлюз доступа под контролем организатора (de-Stars, Slice 2). Организатор открывает или приостанавливает
 * доступ участника платного клуба к контенту (`active` ↔ `frozen`) и фиксирует офлайн-оплату взноса. Деньги
 * идут участник→организатор вне платформы (honor-system, как в складчине) — эти действия меняют только
 * статус membership / отметки об оплате, но никогда не двигают деньги или репутацию.
 *
 * Проверка владельца объявлена декларативно на контроллере (@RequiresOrganizer); этот сервис защищает каждый
 * переход статуса тем же паттерном `WHERE status = expected` + rows-affected, что и org-toggle складчины
 * (0 строк → 409). Собственным membership организатора управлять нельзя.
 */
@Service
class AccessGateService(
    private val membershipRepository: MembershipRepository,
    private val mapper: MembershipMapper,
    // На сколько один подтверждённый взнос открывает доступ (honor-system, помесячное членство, по умолчанию 30 дней).
    @Value("\${membership.access-period-days:30}") private val accessPeriodDays: Long,
    // Origin хранилища, который наш загрузчик добавляет к скриншотам ("{base}/uploads/..."); в проде пусто → URL
    // возвращаются root-relative ("/uploads/..."). Используется для проверки, что доказательство оплаты — НАША загрузка.
    @Value("\${s3.base-url:}") private val storageBaseUrl: String,
    private val userRepository: UserRepository,
    private val clubRepository: ClubRepository,
    private val applicationRepository: ApplicationRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val skladchinaRepository: SkladchinaRepository,
    private val notificationService: NotificationService,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(AccessGateService::class.java)

    @Transactional
    fun freezeAccess(clubId: UUID, targetUserId: UUID, callerId: UUID): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        if (membership.status == MembershipStatus.frozen) return mapper.toDto(membership) // идемпотентно
        if (membership.status != MembershipStatus.active) {
            throw ValidationException("Заморозить можно только активного участника")
        }
        guardApplied(membershipRepository.freezeAccess(membership.id))
        log.info("Access frozen: clubId={} targetUserId={} by={}", clubId, targetUserId, callerId)
        notifyFrozen(clubId, targetUserId)
        return mapper.toDto(membership.copy(status = MembershipStatus.frozen))
    }

    // DM участнику, которому организатор только что закрыл доступ, «на лучших усилиях»: «доступ закрыт — оплатите взнос»
    // с inline-кнопкой deep-link на страницу клуба (где живёт «Оплатить взнос»). Никогда не отменяет freeze.
    private fun notifyFrozen(clubId: UUID, targetUserId: UUID) {
        try {
            val member = userRepository.findById(targetUserId) ?: return
            val clubName = clubRepository.findById(clubId)?.name ?: "клуб"
            notificationService.sendAccessFrozenDM(member.telegramId, clubName, clubId)
        } catch (e: Exception) {
            log.warn("Failed to DM frozen member (non-fatal): clubId={} targetUserId={} error={}", clubId, targetUserId, e.message)
        }
    }

    @Transactional
    fun unfreezeAccess(clubId: UUID, targetUserId: UUID, callerId: UUID): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        if (membership.status == MembershipStatus.active) return mapper.toDto(membership) // идемпотентно
        if (membership.status != MembershipStatus.frozen) {
            throw ValidationException("Разморозить можно только замороженного участника")
        }
        guardApplied(membershipRepository.unfreezeAccess(membership.id))
        log.info("Access unfrozen: clubId={} targetUserId={} by={}", clubId, targetUserId, callerId)
        eventPublisher.publishEvent(MembershipAccessOpenedEvent(clubId, targetUserId))
        return mapper.toDto(membership.copy(status = MembershipStatus.active))
    }

    @Transactional
    fun markDuesPaid(clubId: UUID, targetUserId: UUID, callerId: UUID): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        if (membership.status !in DUES_PAYABLE_STATUSES) {
            throw ValidationException("Отметить взнос можно только у действующего участника")
        }
        // Honor-system помесячное членство: подтверждение оплаты взноса открывает доступ и продлевает его на
        // один период. Продлеваем от ТЕКУЩЕГО срока истечения (или от текущего момента, что позже), чтобы ранняя
        // оплата никогда не теряла уже оплаченные дни — например, «до 28 июня» + оплата → «до 28 июля». Планировщик
        // позже сам переводит просроченный доступ в `expired`, поэтому дата здесь — единственный источник истины.
        val now = OffsetDateTime.now()
        val base = membership.subscriptionExpiresAt?.takeIf { it.isAfter(now) } ?: now
        val newExpiresAt = base.plusDays(accessPeriodDays)
        guardApplied(membershipRepository.markDuesPaid(membership.id, callerId, newExpiresAt))
        log.info("Dues marked paid: clubId={} targetUserId={} by={} accessUntil={}", clubId, targetUserId, callerId, newExpiresAt)
        eventPublisher.publishEvent(MembershipAccessOpenedEvent(clubId, targetUserId))
        // markDuesPaid открывает доступ (active) вне зависимости от предыдущего frozen-состояния, до newExpiresAt.
        return mapper.toDto(membership.copy(status = MembershipStatus.active, subscriptionExpiresAt = newExpiresAt))
    }

    @Transactional
    fun unmarkDues(clubId: UUID, targetUserId: UUID, callerId: UUID): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        // Идемпотентно: 0 строк означает лишь то, что нечего было очищать — не гонка, а no-op.
        // Снятие отметки никогда не меняет статус доступа (симметрично со складчиной, где снятие
        // отметки не закрывает доступ автоматически).
        membershipRepository.unmarkDues(membership.id)
        log.info("Dues unmarked: clubId={} targetUserId={} by={}", clubId, targetUserId, callerId)
        return mapper.toDto(membership)
    }

    // Member admin profile (S1): организатор вручную задаёт окончание окна доступа («своя дата»).
    @Transactional
    fun setAccessUntil(clubId: UUID, targetUserId: UUID, until: OffsetDateTime, callerId: UUID): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        if (!until.isAfter(OffsetDateTime.now())) {
            throw ValidationException("Дата окончания доступа должна быть в будущем")
        }
        guardApplied(membershipRepository.setAccessUntil(membership.id, until))
        log.info("Access window set: clubId={} targetUserId={} by={} until={}", clubId, targetUserId, callerId, until)
        eventPublisher.publishEvent(MembershipAccessOpenedEvent(clubId, targetUserId))
        return mapper.toDto(membership.copy(status = MembershipStatus.active, subscriptionExpiresAt = until))
    }

    // Member admin profile (S1): организатор задаёт/очищает приватную заметку. Пусто → null.
    @Transactional
    fun updateNote(clubId: UUID, targetUserId: UUID, note: String?, callerId: UUID): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        val clean = note?.trim()?.takeIf { it.isNotEmpty() }
        membershipRepository.updateOrganizerNote(membership.id, clean)
        log.info("Organizer note updated: clubId={} targetUserId={} by={} present={}", clubId, targetUserId, callerId, clean != null)
        return mapper.toDto(membership.copy(organizerNote = clean))
    }

    // De-Stars: УЧАСТНИК (callerId) заявляет, что оплатил офлайн-взнос. Без гейта организатора —
    // вызывающий действует над своим собственным membership. Создаёт заявку (claim), которую проверяет
    // организатор; доступ всё равно открывается только когда организатор нажмёт «Взнос получен»
    // (honor-system сохраняется). sbp требует скриншот; cash — просто утверждение (без доказательства).
    // Claim доступен: frozen (первый взнос), expired (просрочка продления) и active — но active только
    // в окне раннего продления (T-3 дня до конца подписки, membership-lifecycle.md §7).
    @Transactional
    fun claimDues(clubId: UUID, callerId: UUID, method: String, proofUrl: String?): MembershipDto {
        val membership = membershipRepository.findByUserAndClub(callerId, clubId)
            ?: throw NotFoundException("Вы не состоите в этом клубе")
        when (membership.status) {
            MembershipStatus.active -> {
                // Раннее продление: окно нужно, чтобы «оплатил» нельзя было заявить за месяц вперёд —
                // орг не сможет осмысленно проверить такой перевод, а claim провисит до истечения.
                val expiresAt = membership.subscriptionExpiresAt
                    ?: throw ValidationException("У этого членства нет платной подписки")
                if (expiresAt.isAfter(OffsetDateTime.now().plusDays(RENEWAL_CLAIM_WINDOW_DAYS))) {
                    throw ValidationException("Продлить подписку можно за $RENEWAL_CLAIM_WINDOW_DAYS дня до её окончания")
                }
            }
            in CLAIMABLE_STATUSES -> Unit // frozen/expired — доступ закрыт, claim всегда уместен
            else -> throw ValidationException("Заявить об оплате можно только пока доступ закрыт")
        }
        val normalizedMethod = when (method.trim().lowercase()) {
            CLAIM_SBP -> CLAIM_SBP
            CLAIM_CASH -> CLAIM_CASH
            else -> throw ValidationException("Неизвестный способ оплаты")
        }
        val cleanProof = proofUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedMethod == CLAIM_SBP) {
            if (cleanProof == null) throw ValidationException("Прикрепите скриншот оплаты")
            // Доказательство должно быть изображением от нашего собственного загрузчика — иначе клиент мог бы
            // подсунуть произвольный/внешний/`javascript:` URL, который при проверке организатором рендерится
            // как кликабельная ссылка.
            if (!isUploadedImageUrl(cleanProof)) throw ValidationException("Некорректная ссылка на скриншот")
        }
        // У cash никогда нет скриншота, даже если клиент его прислал.
        val proof = if (normalizedMethod == CLAIM_SBP) cleanProof else null
        guardApplied(membershipRepository.claimDues(membership.id, normalizedMethod, proof))
        log.info("Dues claim submitted: clubId={} userId={} method={} hasProof={}", clubId, callerId, normalizedMethod, proof != null)
        notifyOrganizerOfClaim(clubId, callerId, normalizedMethod)
        return mapper.toDto(
            membership.copy(duesClaimedAt = OffsetDateTime.now(), duesClaimMethod = normalizedMethod, duesProofUrl = proof)
        )
    }

    // DM организатору клуба «на лучших усилиях» о том, что участник оплатил офлайн и ждёт допуска.
    // Никогда не отменяет claim — заявка уже закоммичена и в любом случае видна в «Ждут оплаты».
    private fun notifyOrganizerOfClaim(clubId: UUID, memberUserId: UUID, method: String) {
        try {
            val club = clubRepository.findById(clubId) ?: return
            val organizer = userRepository.findById(club.ownerId) ?: return
            val member = userRepository.findById(memberUserId)
            val memberName = member?.let {
                if (it.lastName.isNullOrBlank()) it.firstName else "${it.firstName} ${it.lastName}"
            } ?: "Участник"
            notificationService.sendDuesClaimedDM(organizer.telegramId, memberName, club.name, method)
        } catch (e: Exception) {
            log.warn("Failed to DM organizer of dues claim (non-fatal): clubId={} memberUserId={} error={}", clubId, memberUserId, e.message)
        }
    }

    // De-Stars B+C: организатор отклоняет платное вступление (вместо «Взнос получен») — участник оплатил,
    // но организатор его не допускает. Удаляет из клуба; возврат денег — офлайн-обязанность организатора
    // (платформа вне денежного потока). Только для frozen — уже допущенным участником управляют через
    // freeze, а не reject.
    @Transactional
    fun rejectMember(clubId: UUID, targetUserId: UUID, callerId: UUID, reason: String?): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        if (membership.status != MembershipStatus.frozen) {
            throw ValidationException("Отклонить вступление можно только пока доступ не открыт")
        }
        membershipRepository.cancel(membership.id)
        // Также очищаем approved/pending заявку (аналогично /leave) — иначе осиротевшая заявка `approved`
        // оставит пользователя застрявшим на «Заявка одобрена» без возможности подать заявку заново.
        val cascaded = applicationRepository.deleteActiveByUserAndClub(targetUserId, clubId)
        log.info("Join rejected (refund): clubId={} targetUserId={} by={} hasReason={} cascadedApplications={}", clubId, targetUserId, callerId, reason != null, cascaded)
        notifyRejected(targetUserId, reason)
        eventPublisher.publishEvent(MembershipAccessRevokedEvent(clubId, targetUserId))
        return mapper.toDto(membership.copy(status = MembershipStatus.cancelled))
    }

    // Кик организатором (member admin): удаляет любого участника (active или frozen) из клуба за проступок.
    // В отличие от «Закрыть доступ» (freeze = обратимая пауза, участник остаётся) это отменяет membership и
    // очищает окно оплаты, так что доступ теряется немедленно. Причина обязательна и уходит участнику в DM.
    // Только владелец (гейт на контроллере); организатора удалить нельзя. Возврат денег (если участник платный) —
    // офлайн-решение организатора, как и в reject — платформа вне денежного потока.
    @Transactional
    fun removeMember(clubId: UUID, targetUserId: UUID, callerId: UUID, reason: String): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        if (membership.status == MembershipStatus.cancelled) {
            throw ValidationException("Участник уже не в клубе")
        }
        val cleanReason = reason.trim()
        if (cleanReason.length < MIN_REASON_LENGTH) {
            throw ValidationException("Причина должна быть не короче $MIN_REASON_LENGTH символов")
        }
        guardApplied(membershipRepository.remove(membership.id))
        // Очищаем approved/pending заявку, чтобы удалённый участник мог заново подать заявку без осиротевшей
        // «Заявка одобрена». Аналогично /leave + reject.
        val cascaded = applicationRepository.deleteActiveByUserAndClub(targetUserId, clubId)
        // Каскад активностей (PO 2026-07-08): кикнутый не должен удерживать подтверждённую бронь —
        // место освобождается и достаётся первому из листа ожидания, как при добровольном выходе
        // (MembershipService.leaveFreeClub). Отличие — БЕЗ репутационных штрафов за брошенные
        // обязательства: бронь рушит организатор киком, а не участник выходом. Дублирование с
        // leaveFreeClub осознанное: тот путь меняется по правилам штрафов, этот — нет.
        val freedEventIds = eventResponseRepository
            .findConfirmedActiveEventObligations(targetUserId, clubId)
            .map { it.eventId }
            .sorted()
        freedEventIds.forEach { eventResponseRepository.lockEventSlots(it) }
        // Живой статус сбора: кикнутый не должен публично висеть «должником» в «Ждём:» —
        // перерисовываем пост каждой затронутой складчины.
        skladchinaRepository.deleteParticipantFromActiveSkladchinasInClub(targetUserId, clubId)
            .forEach { eventPublisher.publishEvent(SkladchinaProgressChangedEvent(it)) }
        val cascadedResponses = eventResponseRepository.deleteByUserAndClubAndActiveEvents(targetUserId, clubId)
        freedEventIds.forEach { eventId ->
            val promotedUserId = eventResponseRepository.promoteFirstWaitlisted(eventId)
            // Живой закреп: бронь освободилась (и, возможно, перезанята из очереди) — перерисовать статус.
            eventPublisher.publishEvent(EventRosterChangedEvent(eventId))
            if (promotedUserId != null) {
                eventPublisher.publishEvent(WaitlistPromotedEvent(eventId, promotedUserId))
            }
        }
        log.info(
            "Member removed (kick): clubId={} targetUserId={} by={} cascadedApplications={} cascadedResponses={} freedSlots={}",
            clubId, targetUserId, callerId, cascaded, cascadedResponses, freedEventIds.size
        )
        notifyRemoved(targetUserId, clubId, cleanReason)
        eventPublisher.publishEvent(MembershipAccessRevokedEvent(clubId, targetUserId))
        return mapper.toDto(membership.copy(status = MembershipStatus.cancelled, subscriptionExpiresAt = null))
    }

    // DM удалённому участнику с причиной от организатора «на лучших усилиях». Никогда не отменяет удаление.
    private fun notifyRemoved(targetUserId: UUID, clubId: UUID, reason: String) {
        try {
            val telegramId = userRepository.findById(targetUserId)?.telegramId ?: return
            val clubName = clubRepository.findById(clubId)?.name ?: "клуб"
            notificationService.sendDirectMessage(
                telegramId,
                "Организатор удалил вас из клуба «$clubName».\nПричина: $reason"
            )
        } catch (e: Exception) {
            log.warn("Failed to DM removed member (non-fatal): targetUserId={} error={}", targetUserId, e.message)
        }
    }

    // DM «на лучших усилиях» — участник оплатил вне платформы, поэтому сообщаем, что ждать возврата. Никогда не отменяет reject.
    private fun notifyRejected(targetUserId: UUID, reason: String?) {
        try {
            val telegramId = userRepository.findById(targetUserId)?.telegramId ?: return
            val base = "Организатор отклонил ваше вступление в платный клуб и вернёт перевод."
            val text = reason?.trim()?.takeIf { it.isNotEmpty() }?.let { "$base\nПричина: $it" } ?: base
            notificationService.sendDirectMessage(telegramId, text)
        } catch (e: Exception) {
            log.warn("Failed to DM rejected member (non-fatal): targetUserId={} error={}", targetUserId, e.message)
        }
    }

    private fun loadManageableMember(clubId: UUID, targetUserId: UUID): Membership {
        val membership = membershipRepository.findByUserAndClub(targetUserId, clubId)
            ?: throw NotFoundException("Участник не найден в этом клубе")
        if (membership.role == MembershipRole.organizer) {
            throw ValidationException("Нельзя управлять доступом организатора")
        }
        return membership
    }

    // 0 строк = membership покинул ожидаемый статус между нашим чтением и защищённым UPDATE
    // (параллельный leave / другое управляющее действие) — отказываем, а не сообщаем об изменении, которого не было.
    private fun guardApplied(rowsAffected: Int) {
        if (rowsAffected == 0) throw ConflictException("Статус участника изменился — обновите экран")
    }

    /**
     * True только для URL скриншота, произведённого НАШИМ загрузчиком: "{s3.base-url}/uploads/{uuid}.{ext}" —
     * base-url в проде пуст, поэтому URL root-relative "/uploads/...". Отрезает ровно настроенный origin,
     * затем проверяет, что остаток — путь вида "uploads/<name>.<imgext>". Это блокирует javascript:/data:
     * URL И произвольные внешние хосты (например, evil.com/uploads/x.png) от попадания в кликабельную
     * ссылку организатора — доказательство должно приходить из нашего собственного хранилища.
     */
    private fun isUploadedImageUrl(url: String): Boolean {
        val prefix = storageBaseUrl.trimEnd('/')
        val relative = when {
            prefix.isNotEmpty() && url.startsWith("$prefix/") -> url.removePrefix("$prefix/")
            prefix.isEmpty() && url.startsWith("/") -> url.removePrefix("/")
            else -> return false
        }
        return UPLOADS_PATH.matches(relative)
    }

    companion object {
        // Статусы «без доступа», из которых участник может заявить об оплате взноса в любой момент:
        // frozen = ждёт первого взноса, expired = просрочил продление. active обрабатывается отдельно
        // (раннее продление, только в окне ниже).
        private val CLAIMABLE_STATUSES = setOf(MembershipStatus.frozen, MembershipStatus.expired)
        // Окно раннего продления (в днях до конца подписки), в котором active-участник может заявить
        // оплату. Синхронизировано с DM «истекает через 3 дня» (SubscriptionScheduler) и фронтовой
        // секцией «Подписка истекает» на «Моих клубах».
        private const val RENEWAL_CLAIM_WINDOW_DAYS = 3L
        // Статусы, у которых организатор может отметить «Взнос получен»: действующий участник (active —
        // раннее продление), ждущий первого взноса (frozen) или должник по продлению (expired).
        private val DUES_PAYABLE_STATUSES =
            setOf(MembershipStatus.active, MembershipStatus.frozen, MembershipStatus.expired)
        // Способ оплаты «СБП» (перевод по номеру телефона).
        const val CLAIM_SBP = "sbp"
        // Способ оплаты «наличными».
        const val CLAIM_CASH = "cash"
        // Отражает RemoveMemberRequest @Size(min=5); перепроверяется после trim в removeMember.
        private const val MIN_REASON_LENGTH = 5
        // Путь внутри хранилища, куда пишет наш загрузчик: "uploads/{uuid}.{ext}" (StorageController).
        private val UPLOADS_PATH = Regex("^uploads/[\\w.-]+\\.(jpg|jpeg|png)$", RegexOption.IGNORE_CASE)
    }
}
