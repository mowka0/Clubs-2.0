package com.clubs.chatlink

import com.clubs.club.Club
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import java.time.OffsetDateTime
import java.util.UUID

// Общие билдеры для тестов chatlink-модуля: минимально заполненные Club и ChatLink.

fun chatLinkTestClub(
    clubId: UUID = UUID.randomUUID(),
    ownerId: UUID = UUID.randomUUID(),
    name: String = "Партия",
    subscriptionPrice: Int = 0,
    isActive: Boolean = true
): Club = Club(
    id = clubId,
    ownerId = ownerId,
    name = name,
    description = "desc",
    category = ClubCategory.board_games,
    accessType = AccessType.closed,
    city = "Москва",
    district = null,
    memberLimit = 20,
    subscriptionPrice = subscriptionPrice,
    avatarUrl = null,
    rules = null,
    applicationQuestion = null,
    inviteLink = null,
    memberCount = 5,
    isActive = isActive,
    createdAt = OffsetDateTime.now(),
    updatedAt = OffsetDateTime.now()
)

fun chatLinkFixture(
    clubId: UUID = UUID.randomUUID(),
    chatId: Long = -100123L,
    botStatus: BotChatStatus = BotChatStatus.ADMINISTRATOR,
    canPinMessages: Boolean = true,
    canInviteUsers: Boolean = true,
    doorEnabled: Boolean = false,
    doorInviteLink: String? = null,
    livePinEnabled: Boolean = false,
    skladchinaStatusEnabled: Boolean = false,
    linkedByUserId: UUID = UUID.randomUUID()
): ChatLink = ChatLink(
    clubId = clubId,
    chatId = chatId,
    chatTitle = "Партия — чат",
    linkedByUserId = linkedByUserId,
    linkedAt = OffsetDateTime.now(),
    botStatus = botStatus,
    canPinMessages = canPinMessages,
    canInviteUsers = canInviteUsers,
    doorEnabled = doorEnabled,
    doorInviteLink = doorInviteLink,
    livePinEnabled = livePinEnabled,
    skladchinaStatusEnabled = skladchinaStatusEnabled
)
