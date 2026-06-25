package com.clubs.club

import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.tables.records.ClubsRecord
import org.springframework.stereotype.Component

@Component
class ClubMapper {

    fun toDomain(record: ClubsRecord): Club = Club(
        id = record.id!!,
        ownerId = record.ownerId,
        name = record.name,
        description = record.description,
        category = record.category,
        accessType = record.accessType ?: AccessType.`open`,
        city = record.city,
        district = record.district,
        memberLimit = record.memberLimit,
        subscriptionPrice = record.subscriptionPrice ?: 0,
        avatarUrl = record.avatarUrl,
        rules = record.rules,
        applicationQuestion = record.applicationQuestion,
        inviteLink = record.inviteLink,
        // Live count is filled in by the repository read paths (findById/findByInviteCode/findByIds)
        // via copy(memberCount = countLiveMembers(...)). A freshly created club has 0 live members.
        memberCount = 0,
        isActive = record.isActive ?: true,
        telegramGroupId = record.telegramGroupId,
        createdAt = record.createdAt!!,
        updatedAt = record.updatedAt!!
    )

    fun toDetailDto(club: Club): ClubDetailDto = ClubDetailDto(
        id = club.id,
        ownerId = club.ownerId,
        name = club.name,
        description = club.description,
        category = club.category.literal,
        accessType = club.accessType.literal,
        city = club.city,
        district = club.district,
        memberLimit = club.memberLimit,
        subscriptionPrice = club.subscriptionPrice,
        avatarUrl = club.avatarUrl,
        rules = club.rules,
        applicationQuestion = club.applicationQuestion,
        inviteLink = club.inviteLink,
        memberCount = club.memberCount,
        isActive = club.isActive
    )
}
