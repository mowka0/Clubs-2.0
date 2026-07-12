package com.clubs.membership

import com.clubs.application.ApplicationRepository
import com.clubs.award.AwardService
import com.clubs.common.auth.ClubManagerGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.interest.InterestRepository
import com.clubs.reputation.ReputationPolicy
import com.clubs.reputation.ReputationRepository
import com.clubs.reputation.TrustService
import com.clubs.user.MemberProfileDto
import com.clubs.user.UserRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class MemberService(
    private val membershipRepository: MembershipRepository,
    private val userRepository: UserRepository,
    private val reputationRepository: ReputationRepository,
    private val trustService: TrustService,
    private val interestRepository: InterestRepository,
    private val awardService: AwardService,
    private val applicationRepository: ApplicationRepository,
    private val mapper: MembershipMapper,
    private val clubManagerGuard: ClubManagerGuard
) {

    fun getClubMembers(clubId: UUID, callerId: UUID): List<MemberListItemDto> {
        // Один запрос даёт и гейт доступа, и роль смотрящего. status==active зеркалит
        // MembershipAccess.hasAccess (frozen-смотрящий доступа не имеет). Менеджер (владелец или
        // активный со-орг — co-organizers, точка 40) дополнительно видит состояние доступа каждого
        // участника + дату «оплачено до» (de-Stars дашборд); обычные — нет.
        val caller = membershipRepository.findByUserAndClub(callerId, clubId)
        if (caller == null || caller.status != MembershipStatus.active) {
            throw ForbiddenException("Not a member of this club")
        }
        val forOrganizer = clubManagerGuard.isActiveManagerMembership(caller)
        // Trust всех участников — одним батч-чтением ledger.
        val trustByUser = trustService.trustForClubMembers(clubId)
        // Клубные награды для чипов в ростере (R3): один запрос с группировкой по участнику — без N+1.
        val awardsByUser = awardService.getClubAwardsByMember(clubId)
        val members = membershipRepository.findClubMembersWithUserInfo(clubId, includeWithoutAccess = forOrganizer)
            .map {
                mapper.toMemberListItemDto(
                    it,
                    trustByUser[it.userId],
                    awardsByUser[it.userId] ?: emptyList(),
                    forOrganizer,
                    // Асимметричная видимость (reputation-path-back.md AC-3): оценочные метрики видит
                    // организатор (все) и участник (только свою строку); чужие — null.
                    canSeeScores = forOrganizer || it.userId == callerId
                )
            }
        // Организатору — рабочий порядок по Trust (он принимает решения по числам). Участнику —
        // нейтральный порядок по давности вступления: публичного рейтинга («дна таблицы») больше нет.
        // Организатор клуба в обоих случаях первым (он не копит Trust в своём клубе — анти-фарм №1 —
        // и по чистому Trust ушёл бы в конец).
        // Порядок ролей: владелец → со-орги → участники (co-organizers, вторичный ключ по роли).
        val order = if (forOrganizer) {
            compareByDescending<MemberListItemDto> { it.role == "organizer" }
                .thenByDescending { it.role == "co_organizer" }
                .thenByDescending { it.trust ?: Int.MIN_VALUE }
        } else {
            compareByDescending<MemberListItemDto> { it.role == "organizer" }
                .thenByDescending { it.role == "co_organizer" }
                .thenBy { it.joinedAt ?: OffsetDateTime.MAX }
        }
        return members.sortedWith(order)
    }

    /**
     * Кросс-клубовый список «Ждут оплаты» для [callerId]: все участники без доступа (`frozen` —
     * первый взнос, `expired` — просрочка продления) по managed-клубам (владение ИЛИ активный
     * со-орг — co-organizers У-5). Не-менеджер получает пустой список (скоуп внутри запроса),
     * поэтому отдельный authz-гейт на эндпоинте не нужен.
     */
    fun getOrganizerAwaitingDues(callerId: UUID): List<OrganizerDuesMemberDto> =
        membershipRepository.findAwaitingDuesMembersByManager(callerId).map(mapper::toOrganizerDuesDto)

    fun getMemberProfile(clubId: UUID, userId: UUID, callerId: UUID): MemberProfileDto {
        val caller = membershipRepository.findByUserAndClub(callerId, clubId)
        if (caller == null || caller.status != MembershipStatus.active) {
            throw ForbiddenException("Not a member of this club")
        }
        // Менеджер (владелец или активный со-орг — co-organizers, точка 41) видит орг-поля карточки.
        val callerIsOrganizer = clubManagerGuard.isActiveManagerMembership(caller)
        val user = userRepository.findById(userId) ?: throw NotFoundException("User not found")
        val membership = membershipRepository.findByUserAndClub(userId, clubId)
        val reputation = reputationRepository.findByUserAndClub(userId, clubId)
        // «Право на ошибку»: реальный индекс показываем только когда накоплен track record; ниже
        // порога (или нет строки, или владелец в своём клубе) весь блок подавляется,
        // а фронтенд рисует «Новичок» / организаторскую подачу (по роли).
        // + Асимметричная видимость (reputation-path-back.md AC-5): оценочные метрики чужой карточки
        // видят только организатор и сам участник о себе. Чужому зрителю — null, что неотличимо
        // от «Новичка» (неоднозначность по дизайну). bio/интересы/награды не гейтятся (позитив публичен).
        val canSeeScores = callerIsOrganizer || userId == callerId
        val show = canSeeScores && reputation != null && ReputationPolicy.isShown(reputation.outcomeCount)
        // Одно чтение ledger питает оба клубных кольца (Trust + складчина); ниже гейта — null.
        val summary = if (show) trustService.clubSummary(userId, clubId) else null
        return MemberProfileDto(
            userId = userId,
            clubId = clubId,
            firstName = user.firstName,
            username = user.telegramUsername,
            avatarUrl = user.avatarUrl,
            bio = user.bio,
            interests = interestRepository.findUserInterestNames(userId),
            // Клубные награды (S2) — видны каждому участнику (R3), поэтому организаторского гейта нет.
            awards = awardService.getMemberAwards(clubId, userId),
            role = (membership?.role ?: MembershipRole.member).literal,
            trust = summary?.trust,
            promiseFulfillmentPct = if (show) reputation!!.promiseFulfillmentPct else null,
            totalConfirmations = if (show) reputation!!.totalConfirmations else null,
            totalAttendances = if (show) reputation!!.totalAttendances else null,
            spontaneityCount = if (show) reputation!!.spontaneityCount else null,
            skladchinaPaid = summary?.skladchinaPaid,
            skladchinaTotal = summary?.skladchinaTotal,
            // Только организатору: конец оплаченного окна доступа участника. null для обычных смотрящих
            // и бесплатных membership'ов (нет истечения). Питает «Подписка активна до …» на карточке.
            subscriptionExpiresAt = if (callerIsOrganizer) membership?.subscriptionExpiresAt else null,
            // Только организатору: приватная заметка (member admin S1). null для обычных смотрящих.
            organizerNote = if (callerIsOrganizer) membership?.organizerNote else null,
            // Только организатору: claim участника об оплате + скриншот платежа (de-Stars). null для обычных.
            duesClaimedAt = if (callerIsOrganizer) membership?.duesClaimedAt else null,
            duesClaimMethod = if (callerIsOrganizer) membership?.duesClaimMethod else null,
            duesProofUrl = if (callerIsOrganizer) membership?.duesProofUrl else null,
            // Только организатору: ответ из заявки на вступление (закрытые клубы). null для открытых / без вопроса.
            applicationAnswer = if (callerIsOrganizer) {
                applicationRepository.findActiveByUserAndClub(userId, clubId)?.answerText
            } else null
        )
    }
}
