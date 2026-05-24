package com.clubs.membership

import com.clubs.generated.jooq.tables.records.MembershipsRecord
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

    fun toMemberListItemDto(info: ClubMemberInfo): MemberListItemDto = MemberListItemDto(
        userId = info.userId,
        firstName = info.firstName ?: "",
        lastName = info.lastName,
        avatarUrl = info.avatarUrl,
        role = info.role.literal,
        joinedAt = info.joinedAt,
        reliabilityIndex = info.reliabilityIndex,
        promiseFulfillmentPct = info.promiseFulfillmentPct
    )

    fun toUserClubReputationDto(info: UserClubReputationInfo): UserClubReputationDto = UserClubReputationDto(
        clubId = info.clubId,
        clubName = info.clubName,
        clubAvatarUrl = info.clubAvatarUrl,
        category = info.category.literal,
        role = info.role.literal,
        reliabilityIndex = info.reliabilityIndex
    )
}
