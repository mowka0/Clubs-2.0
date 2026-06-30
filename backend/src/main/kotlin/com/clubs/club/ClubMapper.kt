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
        paymentLink = record.paymentLink,
        paymentMethodNote = record.paymentMethodNote,
        createdAt = record.createdAt!!,
        updatedAt = record.updatedAt!!
    )

    // includeRequisites gates the SBP payment details: only club members (active/frozen) and the owner
    // see how to pay — a pending applicant / visitor must not (de-Stars: dues = member→organizer).
    fun toDetailDto(club: Club, includeRequisites: Boolean = false): ClubDetailDto = ClubDetailDto(
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
        isActive = club.isActive,
        paymentLink = if (includeRequisites) club.paymentLink else null,
        paymentMethodNote = if (includeRequisites) club.paymentMethodNote else null
    )
}
