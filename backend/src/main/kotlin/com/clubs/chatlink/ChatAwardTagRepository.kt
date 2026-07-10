package com.clubs.chatlink

import com.clubs.generated.jooq.tables.references.CHAT_AWARD_TAGS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** Запись учёта: бот поставил участнику тег и держит на нём этот текст. */
data class ChatAwardTag(
    val telegramId: Long,
    val tag: String
)

/**
 * Учёт тегов, выставленных ботом (слайс 4, Bot API 9.5): кому бот поставил тег и какой.
 * Нужен для снятия при выключении тумблера/отвязке/уходе из клуба и дедупа перевыставления.
 * Теги, поставленные организатором руками (или участником при can_edit_tag), в учёт не
 * попадают и не снимаются нами.
 */
interface ChatAwardTagRepository {
    fun find(clubId: UUID, telegramId: Long): ChatAwardTag?
    fun findAllForClub(clubId: UUID): List<ChatAwardTag>

    /** Выставить/обновить тег (идемпотентно по PK club+telegram). */
    fun upsert(clubId: UUID, telegramId: Long, tag: String)
    fun delete(clubId: UUID, telegramId: Long)
    fun deleteAllForClub(clubId: UUID)
}

@Repository
class JooqChatAwardTagRepository(private val dsl: DSLContext) : ChatAwardTagRepository {

    override fun find(clubId: UUID, telegramId: Long): ChatAwardTag? =
        dsl.selectFrom(CHAT_AWARD_TAGS)
            .where(CHAT_AWARD_TAGS.CLUB_ID.eq(clubId))
            .and(CHAT_AWARD_TAGS.TELEGRAM_ID.eq(telegramId))
            .fetchOne()
            ?.let { ChatAwardTag(it.telegramId!!, it.tag!!) }

    override fun findAllForClub(clubId: UUID): List<ChatAwardTag> =
        dsl.selectFrom(CHAT_AWARD_TAGS)
            .where(CHAT_AWARD_TAGS.CLUB_ID.eq(clubId))
            .fetch()
            .map { ChatAwardTag(it.telegramId!!, it.tag!!) }

    override fun upsert(clubId: UUID, telegramId: Long, tag: String) {
        dsl.insertInto(CHAT_AWARD_TAGS)
            .set(CHAT_AWARD_TAGS.CLUB_ID, clubId)
            .set(CHAT_AWARD_TAGS.TELEGRAM_ID, telegramId)
            .set(CHAT_AWARD_TAGS.TAG, tag)
            .set(CHAT_AWARD_TAGS.TAGGED_AT, OffsetDateTime.now())
            .onConflict(CHAT_AWARD_TAGS.CLUB_ID, CHAT_AWARD_TAGS.TELEGRAM_ID)
            .doUpdate()
            .set(CHAT_AWARD_TAGS.TAG, tag)
            .set(CHAT_AWARD_TAGS.TAGGED_AT, OffsetDateTime.now())
            .execute()
    }

    override fun delete(clubId: UUID, telegramId: Long) {
        dsl.deleteFrom(CHAT_AWARD_TAGS)
            .where(CHAT_AWARD_TAGS.CLUB_ID.eq(clubId))
            .and(CHAT_AWARD_TAGS.TELEGRAM_ID.eq(telegramId))
            .execute()
    }

    override fun deleteAllForClub(clubId: UUID) {
        dsl.deleteFrom(CHAT_AWARD_TAGS)
            .where(CHAT_AWARD_TAGS.CLUB_ID.eq(clubId))
            .execute()
    }
}
