package com.clubs.membership

import com.clubs.generated.jooq.tables.records.MembershipsRecord
import com.clubs.reputation.ReputationPolicy
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
        subscriptionExpiresAt = membership.subscriptionExpiresAt
    )

    // forOrganizer gates the access/dues fields: only the organizer dashboard sees a member's access
    // state and paid-through date; regular members get null (the roster never leaks who hasn't paid).
    fun toMemberListItemDto(info: ClubMemberInfo, trust: Int?, forOrganizer: Boolean): MemberListItemDto {
        val show = ReputationPolicy.isShown(info.outcomeCount)
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
            accessStatus = if (forOrganizer) info.status.literal else null,
            subscriptionExpiresAt = if (forOrganizer) info.subscriptionExpiresAt else null
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
        subscriptionExpiresAt = member.subscriptionExpiresAt
    )

    fun toUserClubReputationDto(info: UserClubReputationInfo, trust: Int?): UserClubReputationDto {
        val show = ReputationPolicy.isShown(info.outcomeCount)
        return UserClubReputationDto(
            clubId = info.clubId,
            clubName = info.clubName,
            clubAvatarUrl = info.clubAvatarUrl,
            category = info.category.literal,
            role = info.role.literal,
            joinedAt = info.joinedAt,
            trust = if (show) trust else null,
            promiseFulfillmentPct = if (show) info.promiseFulfillmentPct else null,
            totalConfirmations = if (show) info.totalConfirmations else null,
            totalAttendances = if (show) info.totalAttendances else null,
            spontaneityCount = if (show) info.spontaneityCount else null
        )
    }
}
