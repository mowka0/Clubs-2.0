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
        // Живой счётчик заполняют read-пути репозитория (findById/findByInviteCode/findByIds)
        // через copy(memberCount = countLiveMembers(...)). У только что созданного клуба 0 живых участников.
        memberCount = 0,
        isActive = record.isActive ?: true,
        telegramGroupId = record.telegramGroupId,
        paymentLink = record.paymentLink,
        paymentMethodNote = record.paymentMethodNote,
        createdAt = record.createdAt!!,
        updatedAt = record.updatedAt!!
    )

    // includeRequisites гейтит СБП-реквизиты: как платить видят только члены клуба (active/frozen)
    // и владелец — pending-заявитель / посетитель не должны (de-Stars: взнос = участник→организатор).
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
