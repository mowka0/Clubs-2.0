package com.clubs.membership

import com.clubs.application.ApplicationRepository
import com.clubs.award.AwardService
import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.event.EventResponseRepository
import com.clubs.event.EventRosterChangedEvent
import com.clubs.event.WaitlistPromotedEvent
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.reputation.ExitObligation
import com.clubs.reputation.ReputationService
import com.clubs.reputation.TrustService
import com.clubs.skladchina.SkladchinaProgressChangedEvent
import com.clubs.skladchina.SkladchinaRepository
import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MembershipService(
    private val membershipRepository: MembershipRepository,
    private val clubRepository: ClubRepository,
    private val mapper: MembershipMapper,
    private val membershipActivator: MembershipActivator,
    private val eventResponseRepository: EventResponseRepository,
    private val skladchinaRepository: SkladchinaRepository,
    private val applicationRepository: ApplicationRepository,
    private val trustService: TrustService,
    private val reputationService: ReputationService,
    private val eventPublisher: ApplicationEventPublisher,
    private val awardService: AwardService
) {

    private val log = LoggerFactory.getLogger(MembershipService::class.java)

    @Transactional
    fun joinOpenClub(clubId: UUID, userId: UUID): MembershipDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")

        if (club.accessType != AccessType.`open`) {
            throw ValidationException("Club is not open for joining")
        }

        val existing = membershipRepository.findActiveByUserAndClub(userId, clubId)
        if (existing != null) throw ConflictException("Already a member")

        val activeCount = membershipRepository.countActiveByClubId(clubId)
        if (activeCount >= club.memberLimit) throw ValidationException("Club is full")

        return joinClubMembership(club, userId, "open")
    }

    @Transactional
    fun cancelMembership(clubId: UUID, userId: UUID): MembershipDto {
        val membership = membershipRepository.findActiveByUserAndClub(userId, clubId)
            ?: throw NotFoundException("Membership not found")
        if (membership.status == MembershipStatus.cancelled) throw ValidationException("Membership already cancelled")

        membershipRepository.cancel(membership.id)
        // Зеркалит /leave: очищает активную заявку, чтобы отменённый участник не застревал на
        // осиротевшей «Заявка одобрена» и мог подать заявку заново.
        val cascadedApplications = applicationRepository.deleteActiveByUserAndClub(userId, clubId)

        log.info("Membership cancelled: clubId={} userId={} cascadedApplications={}", clubId, userId, cascadedApplications)
        publishRevokedIfNoLiveWindow(membership, clubId, userId)
        return mapper.toDto(membership.copy(status = MembershipStatus.cancelled))
    }

    /**
     * Операция выхода из клуба. Поведение ветвится по типу клуба:
     *  - **Бесплатный** (subscriptionPrice <= 0): каскадная очистка активных обязательств
     *    (RSVP на события + участие в складчинах), перевод членства в
     *    `cancelled`. Владелец выйти не может.
     *  - **Платный** (subscriptionPrice > 0): просто перевод членства в `cancelled`.
     *    `subscription_expires_at` сохраняется — пользователь сохраняет доступ до
     *    истечения срока. Каскад намеренно пропускается: существующие
     *    RSVP/участие в складчинах остаются в силе до истечения срока.
     *
     * Каскад НИКОГДА не трогает `user_club_reputation`, `transactions` и
     * завершённые события/складчины — сохраняет сквозной по клубам агрегат
     * репутации и финансовый аудиторский след.
     */
    @Transactional
    fun leaveClub(clubId: UUID, userId: UUID): MembershipDto {
        val membership = membershipRepository.findActiveByUserAndClub(userId, clubId)
            ?: throw NotFoundException("Membership not found")

        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")

        if (club.ownerId == userId) {
            log.warn("Owner attempted to leave own club: clubId={} userId={}", clubId, userId)
            throw ValidationException("Owner cannot leave the club")
        }

        return if (hasActivePaidAccess(club, membership)) {
            leavePaidClub(membership, clubId, userId)
        } else {
            leaveFreeClub(membership, clubId, userId)
        }
    }

    /**
     * «Выход» — это мягкая отмена подписки (без штрафа, без каскада) для любого, кто ещё держит
     * оплаченный период — даже если клуб с тех пор переключился на бесплатный. Только по-настоящему
     * бесплатное членство (без активного оплаченного периода) идёт по жёсткому пути «выход с
     * обязательствами». Маршрутизация по `subscription_expires_at` членства (а не только по текущей
     * цене клуба) критична: иначе платный участник клуба, переключённого с платного на бесплатный,
     * был бы оштрафован и лишён броней, которые он ещё вправе посещать до истечения подписки.
     */
    private fun hasActivePaidAccess(club: Club, membership: Membership): Boolean =
        club.subscriptionPrice > 0 || (membership.subscriptionExpiresAt?.isAfter(OffsetDateTime.now()) == true)

    @Transactional
    fun joinByInviteCode(code: String, userId: UUID): MembershipDto {
        val club = clubRepository.findByInviteCode(code) ?: throw NotFoundException("Invite link not found")
        val clubId = club.id

        val existing = membershipRepository.findActiveByUserAndClub(userId, clubId)
        if (existing != null) throw ConflictException("Already a member")

        val activeCount = membershipRepository.countActiveByClubId(clubId)
        if (activeCount >= club.memberLimit) throw ValidationException("Club is full")

        return joinClubMembership(club, userId, "invite")
    }

    fun getUserMemberships(userId: UUID): List<MembershipDto> =
        membershipRepository.findByUserId(userId).map(mapper::toDto)

    /**
     * Репутация аутентифицированного пользователя для таба «Профиль»: all-history глобальный
     * агрегат ("надёжен в N из M клубов") + Trust по каждому клубу, разбитый на активные клубы и
     * "История" (клубы, из которых вышел, но по которым остался след репутации). Trust вычисляется
     * на чтении из ledger ([TrustService]); список клубов + разбивку активные/История даёт membership.
     */
    @Transactional(readOnly = true)
    fun getMyReputation(userId: UUID): MyReputationDto {
        val trust = trustService.computeForUser(userId)
        // Полный ClubTrust (не только число): маппер берёт из него и Trust, и проекцию «пути назад»,
        // и счётчики сборов — всё из одного вычисления computeForUser.
        val trustByClub = trust.perClub.associateBy { it.clubId }
        val (active, history) = membershipRepository.findUserClubsWithReputation(userId).partition { it.active }
        // CTA «Ближайшая встреча» в раскрытой карточке «Моих клубов»: только для активных клубов
        // (в «Истории» идти некуда). Один батч-вызов на все клубы.
        val nearestEvents = clubRepository.findNearestEvents(active.map { it.clubId })
        // Клубные награды вызывающего — чипы в раскрытых карточках (один запрос на все клубы).
        val awardsByClub = awardService.getUserAwardsByClub(userId)
        return MyReputationDto(
            global = GlobalTrustDto(
                reliableClubs = trust.global.reliableClubs,
                trackRecordClubs = trust.global.trackRecordClubs,
                score = trust.global.score
            ),
            activeClubs = active.map {
                mapper.toUserClubReputationDto(
                    it, trustByClub[it.clubId], nearestEvents[it.clubId], awardsByClub[it.clubId] ?: emptyList()
                )
            },
            historyClubs = history.map { mapper.toUserClubReputationDto(it, trustByClub[it.clubId]) },
            // «Статистика»: сырые посещения по всем клубам (вне репутации), один дешёвый COUNT (V61).
            visits = eventResponseRepository.countUserVisits(userId).let {
                MyVisitsDto(totalEventsAttended = it.totalEventsAttended, openEventsAttended = it.openEventsAttended)
            }
        )
    }

    private fun leavePaidClub(membership: Membership, clubId: UUID, userId: UUID): MembershipDto {
        membershipRepository.cancel(membership.id)
        val cascadedApplications = applicationRepository.deleteActiveByUserAndClub(userId, clubId)
        log.info(
            "User cancelled paid subscription via /leave: clubId={} userId={} cascadedApplications={}",
            clubId, userId, cascadedApplications
        )
        publishRevokedIfNoLiveWindow(membership, clubId, userId)
        return mapper.toDto(membership.copy(status = MembershipStatus.cancelled))
    }

    /**
     * Добровольный уход закрывает чат-путь (decline заявки + бан строгого режима, слайс 5)
     * СРАЗУ, только если у человека нет живого оплаченного окна: cancelled-в-периоде сохраняет
     * доступ — и чат — до конца периода, а истечение окна ловит шедулер
     * (findCancelledExpiredBetween).
     */
    private fun publishRevokedIfNoLiveWindow(membership: Membership, clubId: UUID, userId: UUID) {
        if (membership.subscriptionExpiresAt?.isAfter(OffsetDateTime.now()) != true) {
            eventPublisher.publishEvent(MembershipAccessRevokedEvent(clubId, userId))
        }
    }

    /**
     * Выход из бесплатного клуба — это тоже «выход с обязательствами» (P1b дыра B): открытые
     * обязательства перечисляются и штрафуются ДО того, как каскад удалит их исходные строки, иначе
     * оставить подтверждённую бронь позади было бы бесплатно. Штраф (internal) + каскад + продвижение
     * waitlist — всё выполняется в единой транзакции [leaveClub], чтобы закоммититься атомарно.
     */
    private fun leaveFreeClub(membership: Membership, clubId: UUID, userId: UUID): MembershipDto {
        // Перечислить ДО любого удаления — каскад ниже удалит именно эти исходные строки.
        val eventObligations = eventResponseRepository.findConfirmedActiveEventObligations(userId, clubId)
        val skladchinaObligations = skladchinaRepository.findPendingReputationObligations(userId, clubId)

        // Сначала штрафы: подтверждённая бронь → no_show (−200), складчина с ожидающей репутацией →
        // skladchina_expired (−40). Идемпотентно через UNIQUE в ledger — более поздний естественный
        // исход для того же источника конфликтует, и строка выхода побеждает, так что двойной выход
        // никогда не засчитывается дважды. Открытые встречи (V62) из штрафов исключены — их бронь
        // не дефицитна и отказ свободен, — но в каскаде/перерисовке закрепа ниже они участвуют.
        reputationService.penalizeExit(
            userId, clubId,
            eventObligations.filterNot { it.isOpenEvent }.map { ExitObligation(it.eventId, it.eventDatetime) },
            skladchinaObligations.map { ExitObligation(it.skladchinaId, it.deadline) }
        )

        // Держим блокировку слотов по каждому событию (отсортировано → без deadlock, снимается при
        // commit) на протяжении delete + promotion, чтобы продвижение waitlist никогда не гонялось
        // с параллельным confirm/decline.
        val freedEventIds = eventObligations.map { it.eventId }.sorted()
        freedEventIds.forEach { eventResponseRepository.lockEventSlots(it) }

        val cascadedSkladchinas = skladchinaRepository.deleteParticipantFromActiveSkladchinasInClub(userId, clubId)
        // Живой статус сбора: состав складчины изменился — перерисовать пост в чате (ушедший
        // не должен оставаться в «Ждём:»).
        cascadedSkladchinas.forEach { eventPublisher.publishEvent(SkladchinaProgressChangedEvent(it)) }
        val cascadedEventResponses = eventResponseRepository.deleteByUserAndClubAndActiveEvents(userId, clubId)
        // Каждый освободившийся подтверждённый слот продвигает следующего из waitlist, чтобы выход не
        // сокращал ростер. Повышённому шлём DM (WaitlistPromotedEvent → AFTER_COMMIT), как и при отказе.
        val promotedWaitlist = freedEventIds.count { eventId ->
            val promotedUserId = eventResponseRepository.promoteFirstWaitlisted(eventId)
            // Живой закреп: бронь освободилась (и, возможно, занята из очереди) — перерисовать статус.
            eventPublisher.publishEvent(EventRosterChangedEvent(eventId))
            if (promotedUserId != null) {
                eventPublisher.publishEvent(WaitlistPromotedEvent(eventId, promotedUserId))
                true
            } else {
                false
            }
        }
        val cascadedApplications = applicationRepository.deleteActiveByUserAndClub(userId, clubId)

        membershipRepository.cancel(membership.id)

        log.info(
            "User left free club: clubId={} userId={} eventNoShows={} skladchinaExpiries={} promotedWaitlist={} " +
                "cascadedSkladchinas={} cascadedEventResponses={} cascadedApplications={}",
            clubId, userId, eventObligations.size, skladchinaObligations.size, promotedWaitlist,
            cascadedSkladchinas.size, cascadedEventResponses, cascadedApplications
        )
        publishRevokedIfNoLiveWindow(membership, clubId, userId)
        return mapper.toDto(membership.copy(status = MembershipStatus.cancelled))
    }

    /**
     * Превью перед выходом для диалога подтверждения: сколько открытых обязательств вызывающий
     * нарушит выходом (и, соответственно, потеряет за них надёжность). Величины штрафов остаются
     * на сервере (internal, H8) — возвращаются только количества. Платные клубы сохраняют
     * обязательства в силе до истечения срока, поэтому ничего не нарушают (нули).
     */
    @Transactional(readOnly = true)
    fun getLeavePreview(clubId: UUID, userId: UUID): LeavePreviewDto {
        val membership = membershipRepository.findActiveByUserAndClub(userId, clubId)
            ?: throw NotFoundException("Membership not found")
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId == userId) throw ValidationException("Owner cannot leave the club")
        // Та же маршрутизация, что и в leaveClub: у кого есть активный оплаченный период — идёт
        // по мягкой отмене (обязательства не нарушаются до истечения), поэтому превью — сплошные нули.
        if (hasActivePaidAccess(club, membership)) return LeavePreviewDto(0, 0, 0)

        // Превью показывает только то, что БУДЕТ оштрафовано (см. док выше «потеряет надёжность»):
        // бронь открытой встречи (V62) удаляется каскадом, но репутацию не трогает — в счёт не входит.
        val events = eventResponseRepository.findConfirmedActiveEventObligations(userId, clubId)
            .count { !it.isOpenEvent }
        val skladchinas = skladchinaRepository.findPendingReputationObligations(userId, clubId).size
        return LeavePreviewDto(
            eventObligations = events,
            skladchinaObligations = skladchinas,
            totalObligations = events + skladchinas
        )
    }

    /**
     * Присоединяет к уже провалидированному клубу (de-Stars, Slice 2). Платный клуб переводит
     * участника в `frozen` — он числится и занимает слот, но не имеет доступа к контенту, пока
     * организатор не подтвердит офлайн-взнос (AccessGateService.markDuesPaid). Бесплатный клуб —
     * сразу в `active`. Инвойса Stars нет.
     */
    private fun joinClubMembership(club: Club, userId: UUID, source: String): MembershipDto {
        val clubId = club.id
        val membership = if (club.subscriptionPrice > 0) {
            membershipActivator.activateFrozen(userId, clubId)
        } else {
            membershipActivator.activateFree(userId, clubId)
        }
        log.info(
            "Joined {} club via {}: clubId={} userId={} status={}",
            if (club.subscriptionPrice > 0) "paid" else "free", source, clubId, userId, membership.status.literal
        )
        return mapper.toDto(membership)
    }
}
