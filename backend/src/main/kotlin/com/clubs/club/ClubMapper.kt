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
        memberCount = record.memberCount ?: 0,
        activityRating = record.activityRating ?: 0,
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
        activityRating = club.activityRating,
        isActive = club.isActive
    )
}
