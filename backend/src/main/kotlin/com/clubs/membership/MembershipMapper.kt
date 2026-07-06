package com.clubs.membership

import com.clubs.award.AwardDto
import com.clubs.generated.jooq.tables.records.MembershipsRecord
import com.clubs.reputation.ClubTrust
import com.clubs.reputation.ReputationPolicy
import com.clubs.reputation.TrustPolicy
import org.springframework.stereotype.Component

@Component
class MembershipMapper {

    fun toDomain(record: MembershipsRecord): Membership = Membership(
        id = record.id!!,
        userId = record.userId,
        clubId = record.clubId,
        status = record.status!!,
        role = record.role!!,
        joinedAt = record.joinedAt!!,
        subscriptionExpiresAt = record.subscriptionExpiresAt,
        organizerNote = record.organizerNote,
        duesClaimedAt = record.duesClaimedAt,
        duesClaimMethod = record.duesClaimMethod,
        duesProofUrl = record.duesProofUrl,
        createdAt = record.createdAt!!,
        updatedAt = record.updatedAt!!
    )

    fun toDto(membership: Membership): MembershipDto = MembershipDto(
        id = membership.id,
        userId = membership.userId,
        clubId = membership.clubId,
        status = membership.status.literal,
        role = membership.role.literal,
        joinedAt = membership.joinedAt,
        subscriptionExpiresAt = membership.subscriptionExpiresAt,
        duesClaimedAt = membership.duesClaimedAt,
        duesClaimMethod = membership.duesClaimMethod
    )

    // forOrganizer закрывает поля доступа/взносов: только дашборд организатора видит статус доступа
    // участника и дату оплаты до; обычные участники получают null (ростер никогда не палит, кто не заплатил).
    // canSeeScores — асимметричная видимость (reputation-path-back.md): оценочные метрики (trust,
    // обещания, подтверждения) видят только организатор и сам участник о себе; чужим — null,
    // неотличимый от «Новичка» (неоднозначность по дизайну). Награды публичны (R3) — любому зрителю.
    fun toMemberListItemDto(
        info: ClubMemberInfo,
        trust: Int?,
        awards: List<AwardDto>,
        forOrganizer: Boolean,
        canSeeScores: Boolean
    ): MemberListItemDto {
        val show = ReputationPolicy.isShown(info.outcomeCount) && canSeeScores
        return MemberListItemDto(
            userId = info.userId,
            firstName = info.firstName ?: "",
            lastName = info.lastName,
            avatarUrl = info.avatarUrl,
            role = info.role.literal,
            joinedAt = info.joinedAt,
            trust = if (show) trust else null,
            promiseFulfillmentPct = if (show) info.promiseFulfillmentPct else null,
            totalConfirmations = if (show) info.totalConfirmations else null,
            awards = awards,
            accessStatus = if (forOrganizer) info.status.literal else null,
            subscriptionExpiresAt = if (forOrganizer) info.subscriptionExpiresAt else null,
            duesClaimedAt = if (forOrganizer) info.duesClaimedAt else null,
            duesClaimMethod = if (forOrganizer) info.duesClaimMethod else null
        )
    }

    fun toOrganizerDuesDto(member: OrganizerDuesMember): OrganizerDuesMemberDto = OrganizerDuesMemberDto(
        userId = member.userId,
        firstName = member.firstName ?: "",
        lastName = member.lastName,
        avatarUrl = member.avatarUrl,
        telegramUsername = member.telegramUsername,
        clubId = member.clubId,
        clubName = member.clubName,
        clubAvatarUrl = member.clubAvatarUrl,
        joinedAt = member.joinedAt,
        subscriptionExpiresAt = member.subscriptionExpiresAt,
        duesClaimedAt = member.duesClaimedAt,
        duesClaimMethod = member.duesClaimMethod,
        accessStatus = member.accessStatus
    )

    fun toUserClubReputationDto(
        info: UserClubReputationInfo,
        clubTrust: ClubTrust?,
        nearestEvent: com.clubs.club.NearestEventDto? = null,
        awards: List<AwardDto> = emptyList()
    ): UserClubReputationDto {
        val show = ReputationPolicy.isShown(info.outcomeCount)
        val trust = if (show) clubTrust?.trust else null
        // «Путь назад» — только при видимой просадке в живом клубе: trust показан, ниже надёжной
        // зоны и клуб активен (в «Истории» возвращаться некуда). См. reputation-path-back.md AC-2.
        val showPathBack = trust != null && trust < TrustPolicy.RELIABLE_THRESHOLD && info.active
        return UserClubReputationDto(
            clubId = info.clubId,
            clubName = info.clubName,
            clubAvatarUrl = info.clubAvatarUrl,
            category = info.category.literal,
            role = info.role.literal,
            joinedAt = info.joinedAt,
            trust = trust,
            promiseFulfillmentPct = if (show) info.promiseFulfillmentPct else null,
            totalConfirmations = if (show) info.totalConfirmations else null,
            totalAttendances = if (show) info.totalAttendances else null,
            spontaneityCount = if (show) info.spontaneityCount else null,
            projectedNext1 = if (showPathBack) clubTrust?.projectedNext1 else null,
            projectedNext2 = if (showPathBack) clubTrust?.projectedNext2 else null,
            meetingsToReliable = if (showPathBack) clubTrust?.meetingsToReliable else null,
            skladchinaPaid = if (show) clubTrust?.skladchinaPaid else null,
            skladchinaTotal = if (show) clubTrust?.skladchinaTotal else null,
            nearestEvent = nearestEvent,
            awards = awards
        )
    }
}
