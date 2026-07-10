package com.clubs.chatlink

import com.clubs.generated.jooq.tables.references.CLUB_CHAT_LINKS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqChatLinkRepository(
    private val dsl: DSLContext,
    private val mapper: ChatLinkMapper
) : ChatLinkRepository {

    override fun findByClubId(clubId: UUID): ChatLink? =
        dsl.selectFrom(CLUB_CHAT_LINKS)
            .where(CLUB_CHAT_LINKS.CLUB_ID.eq(clubId))
            .fetchOne()
            ?.let(mapper::recordToDomain)

    override fun findByChatId(chatId: Long): ChatLink? =
        dsl.selectFrom(CLUB_CHAT_LINKS)
            .where(CLUB_CHAT_LINKS.CHAT_ID.eq(chatId))
            .fetchOne()
            ?.let(mapper::recordToDomain)

    override fun insert(link: ChatLink): ChatLink {
        val record = dsl.insertInto(CLUB_CHAT_LINKS)
            .set(CLUB_CHAT_LINKS.CLUB_ID, link.clubId)
            .set(CLUB_CHAT_LINKS.CHAT_ID, link.chatId)
            .set(CLUB_CHAT_LINKS.CHAT_TITLE, link.chatTitle)
            .set(CLUB_CHAT_LINKS.LINKED_BY_USER_ID, link.linkedByUserId)
            .set(CLUB_CHAT_LINKS.LINKED_AT, link.linkedAt)
            .set(CLUB_CHAT_LINKS.BOT_STATUS, link.botStatus.literal)
            .set(CLUB_CHAT_LINKS.CAN_PIN_MESSAGES, link.canPinMessages)
            .set(CLUB_CHAT_LINKS.CAN_INVITE_USERS, link.canInviteUsers)
            .set(CLUB_CHAT_LINKS.CAN_RESTRICT_MEMBERS, link.canRestrictMembers)
            .set(CLUB_CHAT_LINKS.CAN_MANAGE_TAGS, link.canManageTags)
            .set(CLUB_CHAT_LINKS.DOOR_ENABLED, link.doorEnabled)
            .set(CLUB_CHAT_LINKS.DOOR_INVITE_LINK, link.doorInviteLink)
            .returning()
            .fetchOne()!!
        return mapper.recordToDomain(record)
    }

    override fun updateBotState(clubId: UUID, botStatus: BotChatStatus, canPinMessages: Boolean, canInviteUsers: Boolean, canRestrictMembers: Boolean, canManageTags: Boolean) {
        dsl.update(CLUB_CHAT_LINKS)
            .set(CLUB_CHAT_LINKS.BOT_STATUS, botStatus.literal)
            .set(CLUB_CHAT_LINKS.CAN_PIN_MESSAGES, canPinMessages)
            .set(CLUB_CHAT_LINKS.CAN_INVITE_USERS, canInviteUsers)
            .set(CLUB_CHAT_LINKS.CAN_RESTRICT_MEMBERS, canRestrictMembers)
            .set(CLUB_CHAT_LINKS.CAN_MANAGE_TAGS, canManageTags)
            .set(CLUB_CHAT_LINKS.UPDATED_AT, OffsetDateTime.now())
            .where(CLUB_CHAT_LINKS.CLUB_ID.eq(clubId))
            .execute()
    }

    override fun updateChatTitle(clubId: UUID, chatTitle: String?) {
        dsl.update(CLUB_CHAT_LINKS)
            .set(CLUB_CHAT_LINKS.CHAT_TITLE, chatTitle)
            .set(CLUB_CHAT_LINKS.UPDATED_AT, OffsetDateTime.now())
            .where(CLUB_CHAT_LINKS.CLUB_ID.eq(clubId))
            .execute()
    }

    override fun updateDoor(clubId: UUID, doorEnabled: Boolean, doorInviteLink: String?) {
        dsl.update(CLUB_CHAT_LINKS)
            .set(CLUB_CHAT_LINKS.DOOR_ENABLED, doorEnabled)
            .set(CLUB_CHAT_LINKS.DOOR_INVITE_LINK, doorInviteLink)
            .set(CLUB_CHAT_LINKS.UPDATED_AT, OffsetDateTime.now())
            .where(CLUB_CHAT_LINKS.CLUB_ID.eq(clubId))
            .execute()
    }

    override fun updateInviteLink(clubId: UUID, doorInviteLink: String) {
        dsl.update(CLUB_CHAT_LINKS)
            .set(CLUB_CHAT_LINKS.DOOR_INVITE_LINK, doorInviteLink)
            .set(CLUB_CHAT_LINKS.UPDATED_AT, OffsetDateTime.now())
            .where(CLUB_CHAT_LINKS.CLUB_ID.eq(clubId))
            .execute()
    }

    override fun updateLivePin(clubId: UUID, livePinEnabled: Boolean) {
        dsl.update(CLUB_CHAT_LINKS)
            .set(CLUB_CHAT_LINKS.LIVE_PIN_ENABLED, livePinEnabled)
            .set(CLUB_CHAT_LINKS.UPDATED_AT, OffsetDateTime.now())
            .where(CLUB_CHAT_LINKS.CLUB_ID.eq(clubId))
            .execute()
    }

    override fun updateSkladchinaStatus(clubId: UUID, skladchinaStatusEnabled: Boolean) {
        dsl.update(CLUB_CHAT_LINKS)
            .set(CLUB_CHAT_LINKS.SKLADCHINA_STATUS_ENABLED, skladchinaStatusEnabled)
            .set(CLUB_CHAT_LINKS.UPDATED_AT, OffsetDateTime.now())
            .where(CLUB_CHAT_LINKS.CLUB_ID.eq(clubId))
            .execute()
    }

    override fun updateStrictMode(clubId: UUID, strictModeEnabled: Boolean) {
        dsl.update(CLUB_CHAT_LINKS)
            .set(CLUB_CHAT_LINKS.STRICT_MODE_ENABLED, strictModeEnabled)
            .set(CLUB_CHAT_LINKS.UPDATED_AT, OffsetDateTime.now())
            .where(CLUB_CHAT_LINKS.CLUB_ID.eq(clubId))
            .execute()
    }

    override fun updateAwardTags(clubId: UUID, awardTagsEnabled: Boolean) {
        dsl.update(CLUB_CHAT_LINKS)
            .set(CLUB_CHAT_LINKS.AWARD_TAGS_ENABLED, awardTagsEnabled)
            .set(CLUB_CHAT_LINKS.UPDATED_AT, OffsetDateTime.now())
            .where(CLUB_CHAT_LINKS.CLUB_ID.eq(clubId))
            .execute()
    }

    override fun updateChatId(oldChatId: Long, newChatId: Long) {
        dsl.update(CLUB_CHAT_LINKS)
            .set(CLUB_CHAT_LINKS.CHAT_ID, newChatId)
            .set(CLUB_CHAT_LINKS.UPDATED_AT, OffsetDateTime.now())
            .where(CLUB_CHAT_LINKS.CHAT_ID.eq(oldChatId))
            .execute()
    }

    override fun findAllWithAwardTags(): List<ChatLink> =
        dsl.selectFrom(CLUB_CHAT_LINKS)
            .where(CLUB_CHAT_LINKS.AWARD_TAGS_ENABLED.isTrue)
            .fetch()
            .map(mapper::recordToDomain)

    override fun delete(clubId: UUID) {
        dsl.deleteFrom(CLUB_CHAT_LINKS)
            .where(CLUB_CHAT_LINKS.CLUB_ID.eq(clubId))
            .execute()
    }
}
