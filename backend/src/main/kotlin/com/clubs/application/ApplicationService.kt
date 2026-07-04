package com.clubs.application

import com.clubs.bot.NotificationService
import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.RateLimitException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ApplicationStatus
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.interest.InterestRepository
import com.clubs.membership.MembershipActivator
import com.clubs.membership.MembershipDto
import com.clubs.membership.MembershipMapper
import com.clubs.membership.MembershipRepository
import com.clubs.reputation.ApplicantSignal
import com.clubs.reputation.ApplicantSignalService
import com.clubs.reputation.PeerStatsAggregate
import com.clubs.reputation.ReputationRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

// Лимит заявок от одного пользователя в сутки — защита от спама заявками
private const val MAX_APPLICATIONS_PER_DAY = 5

@Service
class ApplicationService(
    private val applicationRepository: ApplicationRepository,
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val mapper: ApplicationMapper,
    private val notificationService: NotificationService,
    private val userRepository: UserRepository,
    private val reputationRepository: ReputationRepository,
    private val applicantSignalService: ApplicantSignalService,
    private val interestRepository: InterestRepository,
    private val membershipMapper: MembershipMapper,
    private val membershipActivator: MembershipActivator
) {

    private val log = LoggerFactory.getLogger(ApplicationService::class.java)

    @Transactional
    fun submitApplication(clubId: UUID, userId: UUID, request: SubmitApplicationRequest): ApplicationDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")

        if (club.accessType != AccessType.closed) {
            throw ValidationException("Club does not accept applications")
        }

        if (club.applicationQuestion != null && request.answerText.isNullOrBlank()) {
            throw ValidationException("Answer is required for this club")
        }

        val existingMembership = membershipRepository.findActiveByUserAndClub(userId, clubId)
        if (existingMembership != null) throw ConflictException("Already a member")

        // De-Stars: одобренная заявка обычно идёт в паре с frozen/active membership (approve
        // его создаёт). Если membership потом отменили (removed / rejected / left), одобренная
        // заявка становится осиротевшей — самовосстанавливаемся, чтобы пользователь мог подать
        // заявку заново, а не застрял на «Заявка одобрена». По-настоящему pending-заявка
        // по-прежнему блокирует дубликат.
        val activeApp = applicationRepository.findActiveByUserAndClub(userId, clubId)
        if (activeApp != null) {
            val priorMembership = membershipRepository.findByUserAndClub(userId, clubId)
            val orphanedApproval = activeApp.status == ApplicationStatus.approved &&
                priorMembership?.status == MembershipStatus.cancelled
            if (orphanedApproval) {
                applicationRepository.deleteActiveByUserAndClub(userId, clubId)
                log.info("Cleared orphaned approved application on re-apply: clubId={} userId={}", clubId, userId)
            } else {
                throw ConflictException("Application already exists")
            }
        }

        val todayCount = applicationRepository.countTodayByUser(userId)
        if (todayCount >= MAX_APPLICATIONS_PER_DAY) throw RateLimitException("Too many applications today")

        val application = applicationRepository.create(userId, clubId, request.answerText)
        log.info("Application submitted: id={} clubId={} userId={}", application.id, clubId, userId)

        dispatchApplicationCreatedDm(club, userId)

        return mapper.toDto(application)
    }

    /**
     * Уведомление организатора о новой заявке — best-effort. Ошибки здесь НЕ должны
     * прерывать транзакцию submitApplication — sendApplicationCreatedDM работает
     * @Async (fire-and-forget), а try/catch на отдельное сообщение живёт в
     * NotificationService.sendDm. Дополнительно оборачиваем lookup'ы защитой, чтобы
     * промах в БД / NPE никогда не испортил основной happy path.
     */
    private fun dispatchApplicationCreatedDm(club: Club, applicantId: UUID) {
        try {
            val organizer = userRepository.findById(club.ownerId)
            if (organizer == null) {
                log.warn("Skipping application-created DM: organizer not found ownerId={} clubId={}", club.ownerId, club.id)
                return
            }
            val applicant = userRepository.findById(applicantId)
            val applicantName = applicant?.let { buildDisplayName(it.firstName, it.lastName) } ?: "Новый пользователь"

            notificationService.sendApplicationCreatedDM(
                organizerTelegramId = organizer.telegramId,
                applicantDisplayName = applicantName,
                clubName = club.name
            )
            log.info(
                "DM dispatched for application-created: clubId={} organizerTelegramId={}",
                club.id, organizer.telegramId
            )
        } catch (e: Exception) {
            log.warn(
                "Failed to dispatch application-created DM (non-fatal): clubId={} applicantId={} error={}",
                club.id, applicantId, e.message
            )
        }
    }

    private fun buildDisplayName(firstName: String, lastName: String?): String =
        if (lastName.isNullOrBlank()) firstName else "$firstName $lastName"

    @Transactional
    fun approveApplication(applicationId: UUID, organizerId: UUID): ApplicationDto {
        val application = applicationRepository.findById(applicationId)
            ?: throw NotFoundException("Application not found")

        val club = clubRepository.findById(application.clubId)
            ?: throw NotFoundException("Club not found")

        if (club.ownerId != organizerId) throw ForbiddenException("Forbidden")

        if (application.status != ApplicationStatus.pending) {
            throw ValidationException("Application is not pending")
        }

        val activeCount = membershipRepository.countActiveByClubId(application.clubId)
        if (activeCount >= club.memberLimit) throw ValidationException("Club is full")

        // De-Stars (Slice 2): approve сразу создаёт membership — без инвойса Stars. В платном клубе
        // заявитель попадает в `frozen` (доступ закрыт, пока организатор не подтвердит офф-платформенный
        // взнос через AccessGateService.markDuesPaid); в бесплатном клубе — сразу в `active`.
        if (club.subscriptionPrice > 0) {
            membershipActivator.activateFrozen(application.userId, application.clubId)
        } else {
            membershipActivator.activateFree(application.userId, application.clubId)
        }
        log.info(
            "Membership created on application approve: applicationId={} clubId={} userId={} paid={}",
            applicationId, application.clubId, application.userId, club.subscriptionPrice > 0
        )

        val updated = applicationRepository.updateStatus(applicationId, ApplicationStatus.approved)
        log.info("Application approved: id={} clubId={} userId={} organizerId={}", applicationId, application.clubId, application.userId, organizerId)

        dispatchApplicationApprovedDm(club, application.userId)

        return mapper.toDto(updated)
    }

    /**
     * DM заявителю об одобрении заявки — best-effort. Для платного клуба это напоминание
     * «оплатите вступление» (теперь он в `frozen`); для бесплатного — обычное приветствие.
     * Зеркалит [dispatchApplicationCreatedDm]: ошибки НЕ должны прерывать транзакцию approve.
     */
    private fun dispatchApplicationApprovedDm(club: Club, applicantId: UUID) {
        try {
            val applicant = userRepository.findById(applicantId)
            if (applicant == null) {
                log.warn("Skipping application-approved DM: applicant not found applicantId={} clubId={}", applicantId, club.id)
                return
            }
            notificationService.sendApplicationApprovedDM(
                applicantTelegramId = applicant.telegramId,
                clubName = club.name,
                clubId = club.id,
                paid = club.subscriptionPrice > 0
            )
        } catch (e: Exception) {
            log.warn("Failed to dispatch application-approved DM (non-fatal): clubId={} applicantId={} error={}", club.id, applicantId, e.message)
        }
    }

    @Transactional
    fun rejectApplication(applicationId: UUID, organizerId: UUID, reason: String?): ApplicationDto {
        val application = applicationRepository.findById(applicationId)
            ?: throw NotFoundException("Application not found")

        val club = clubRepository.findById(application.clubId)
            ?: throw NotFoundException("Club not found")

        if (club.ownerId != organizerId) throw ForbiddenException("Forbidden")

        if (application.status != ApplicationStatus.pending) {
            throw ValidationException("Application is not pending")
        }

        // DTO @NotBlank/@Size отсеивает пустые/короткие причины ещё до этого места для
        // отказов, инициированных человеком. Nullable-параметр оставляет возможность
        // для будущих системных отказов (например, от scheduler'а) без изменения контракта.
        // Defense in depth: перепроверяем длину ПОСЛЕ trim. "  ab " проходит @Size(min=5),
        // но после trim остаётся 2 символа — для человеческих отказов считаем это невалидным.
        val storedReason = reason?.trim()?.ifEmpty { null }
        if (reason != null && (storedReason == null || storedReason.length < 5)) {
            throw ValidationException("Reason must be at least 5 characters after trim")
        }
        val updated = applicationRepository.updateStatus(applicationId, ApplicationStatus.rejected, storedReason)
        log.info(
            "Application rejected: id={} clubId={} userId={} organizerId={}",
            applicationId, application.clubId, application.userId, organizerId
        )
        return mapper.toDto(updated)
    }

    /**
     * Самостоятельный отзыв заявки: пользователь закрывает СВОЮ pending-заявку — подал по ошибке
     * или передумал до решения организатора. Отменить может только сам заявитель, и только пока
     * статус ещё `pending` (одобренная заявка уже создала membership → это «выход из клуба»;
     * терминальный статус неизменяем). Переводит заявку в `cancelled` — это не активный статус,
     * так что пользователь сможет подать заявку заново (частичный индекс V42 работает только
     * по pending/approved).
     */
    @Transactional
    fun cancelApplication(applicationId: UUID, callerId: UUID): ApplicationDto {
        val application = applicationRepository.findById(applicationId)
            ?: throw NotFoundException("Application not found")
        if (application.userId != callerId) throw ForbiddenException("Forbidden")
        if (application.status != ApplicationStatus.pending) {
            throw ValidationException("Можно отменить только заявку на рассмотрении")
        }
        val updated = applicationRepository.updateStatus(applicationId, ApplicationStatus.cancelled)
        log.info("Application cancelled by applicant: id={} clubId={} userId={}", applicationId, application.clubId, callerId)
        return mapper.toDto(updated)
    }

    fun getClubApplications(clubId: UUID, organizerId: UUID, status: String?): List<ApplicationDto> {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != organizerId) throw ForbiddenException("Forbidden")

        val statusEnum = status?.let {
            ApplicationStatus.values().find { e -> e.literal == it }
                ?: throw ValidationException("Invalid status: $it")
        }

        return applicationRepository.findByClubId(clubId, statusEnum).map(mapper::toDto)
    }

    fun getMyApplications(userId: UUID): List<ApplicationDto> =
        applicationRepository.findByUserId(userId).map(mapper::toDto)

    /**
     * Кросс-клубовый инбокс организатора: все pending-заявки по всем клубам, которыми
     * владеет вызывающий, обогащённые данными заявителя + peer-stats + краткой инфой о клубе.
     *
     * Контракт по производительности (docs/modules/applications-inbox.md § Non-functional):
     * ≤5 SQL-запросов независимо от числа заявок N.
     */
    @Transactional(readOnly = true)
    fun getMyPendingApplications(organizerId: UUID): List<PendingApplicationDto> {
        val clubIds = clubRepository.findIdsByOwnerId(organizerId)
        if (clubIds.isEmpty()) return emptyList()

        val applications = applicationRepository.findPendingByClubIds(clubIds)
        if (applications.isEmpty()) return emptyList()

        val applicantIds = applications.map { it.userId }.toSet()
        val applicantClubIds = applications.map { it.clubId }.toSet()

        val applicantsById = userRepository.findByIds(applicantIds).associateBy { it.id!! }
        val peerStatsByUser = reputationRepository.aggregateByUserIds(applicantIds)
        val signalsByUser = applicantSignalService.signalsFor(applicantIds)
        val interestsByUser = interestRepository.findUserInterestNamesByUserIds(applicantIds)
        val clubsById = clubRepository.findByIds(applicantClubIds).associateBy { it.id }

        val now = OffsetDateTime.now()
        return applications.mapNotNull { application ->
            val applicantRecord = applicantsById[application.userId] ?: return@mapNotNull null
            val club = clubsById[application.clubId] ?: return@mapNotNull null
            val interests = interestsByUser[application.userId].orEmpty()
            mapper.toPendingDto(
                application = application,
                applicant = mapper.toApplicantInfo(applicantRecord, interests),
                peerStats = mapper.toPeerStats(
                    peerStatsByUser[application.userId] ?: PeerStatsAggregate.EMPTY,
                    signalsByUser[application.userId] ?: ApplicantSignal.EMPTY
                ),
                club = mapper.toClubBrief(club),
                now = now
            )
        }
    }

    /**
     * Счётчик pending-заявок для точки на табе «Мои клубы»: pending-заявки по всем клубам
     * вызывающего. (De-Stars Slice 2: счётчики «ожидает оплаты Stars» убраны — approve сразу
     * создаёт membership, так что такого состояния больше не существует.) Скоуп ограничен
     * вызывающим пользователем — риска IDOR нет.
     */
    @Transactional(readOnly = true)
    fun getMyClubsActionCounts(userId: UUID): PendingApplicationsCountDto {
        val ownedClubIds = clubRepository.findIdsByOwnerId(userId)
        val inboxCount = applicationRepository.countPendingByClubIds(ownedClubIds)
        // De-Stars: участники «оплатил и ждёт» (frozen + взнос заявлен) тоже требуют решения
        // организатора, поэтому они тоже зажигают точку «Мои клубы» вместе с инбоксом заявок.
        val awaitingDuesCount = membershipRepository.countClaimedFrozenByOwner(userId)
        return PendingApplicationsCountDto(inboxCount = inboxCount, awaitingDuesCount = awaitingDuesCount)
    }

    /**
     * Завершает membership для бесплатного клуба по одобренной заявке, застрявшей в состоянии
     * «approved-без-membership» (legacy-данные с тех времён, когда approve не всегда создавал
     * membership). Вызвать может только сам заявитель; применимо только к бесплатным клубам
     * (`subscription_price <= 0`) — платные клубы вступают как `frozen` при approve и открываются
     * организатором (AccessGateService).
     *
     * Делегирует в [MembershipActivator.activateFree], который обрабатывает и свежий INSERT
     * (строки вообще нет), и реактивацию (cancelled / expired строка из прошлого жизненного
     * цикла — UNIQUE(user_id, club_id) не даёт сделать второй INSERT). Идемпотентно на уровне
     * заявки — повторный вызов после успеха вернёт 400 ("Already a member").
     */
    @Transactional
    fun completeFreeMembership(applicationId: UUID, callerUserId: UUID): MembershipDto {
        val application = applicationRepository.findById(applicationId)
            ?: throw NotFoundException("Application not found")
        if (application.userId != callerUserId) {
            throw ForbiddenException("Forbidden")
        }
        if (application.status != ApplicationStatus.approved) {
            throw ValidationException("Application is not approved")
        }
        val club = clubRepository.findById(application.clubId)
            ?: throw NotFoundException("Club not found")
        if (club.subscriptionPrice > 0) {
            throw ValidationException("Club is not free — the organizer opens access after the dues")
        }
        val existingMembership = membershipRepository.findActiveByUserAndClub(callerUserId, application.clubId)
        if (existingMembership != null) {
            throw ValidationException("Already a member")
        }

        val membership = membershipActivator.activateFree(callerUserId, application.clubId)
        log.info(
            "Free membership completed for stuck application: applicationId={} userId={} clubId={}",
            applicationId, callerUserId, application.clubId
        )
        return membershipMapper.toDto(membership)
    }
}
