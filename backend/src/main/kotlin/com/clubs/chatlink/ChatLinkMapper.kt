package com.clubs.chatlink

import com.clubs.generated.jooq.tables.records.ClubChatLinksRecord
import org.springframework.stereotype.Component

@Component
class ChatLinkMapper {

    fun recordToDomain(record: ClubChatLinksRecord): ChatLink = ChatLink(
        clubId = record.clubId!!,
        chatId = record.chatId!!,
        chatTitle = record.chatTitle,
        linkedByUserId = record.linkedByUserId!!,
        linkedAt = record.linkedAt!!,
        botStatus = BotChatStatus.fromLiteral(record.botStatus!!),
        canPinMessages = record.canPinMessages!!,
        canInviteUsers = record.canInviteUsers!!,
        canRestrictMembers = record.canRestrictMembers!!,
        canPromoteMembers = record.canPromoteMembers!!,
        doorEnabled = record.doorEnabled!!,
        doorInviteLink = record.doorInviteLink,
        livePinEnabled = record.livePinEnabled!!,
        skladchinaStatusEnabled = record.skladchinaStatusEnabled!!,
        strictModeEnabled = record.strictModeEnabled!!,
        awardTitlesEnabled = record.awardTitlesEnabled!!
    )

    fun toStatusDto(link: ChatLink?, startGroupUrl: String): ChatLinkStatusDto = ChatLinkStatusDto(
        linked = link != null,
        chatTitle = link?.chatTitle,
        linkedAt = link?.linkedAt,
        botStatus = link?.botStatus?.literal,
        canPinMessages = link?.canPinMessages ?: false,
        canInviteUsers = link?.canInviteUsers ?: false,
        canRestrictMembers = link?.canRestrictMembers ?: false,
        canPromoteMembers = link?.canPromoteMembers ?: false,
        doorEnabled = link?.doorEnabled ?: false,
        doorInviteLink = link?.doorInviteLink,
        livePinEnabled = link?.livePinEnabled ?: false,
        skladchinaStatusEnabled = link?.skladchinaStatusEnabled ?: false,
        strictModeEnabled = link?.strictModeEnabled ?: false,
        awardTitlesEnabled = link?.awardTitlesEnabled ?: false,
        startGroupUrl = startGroupUrl
    )
}
