package com.clubs.chatlink

import com.clubs.generated.jooq.tables.references.CHAT_AWARD_TITLES
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** Запись учёта: бот повысил участника ради титула и держит на нём этот текст. */
data class ChatAwardTitle(
    val telegramId: Long,
    val title: String
)

/**
 * Учёт титулов, выставленных ботом (слайс 4): кого бот повысил в «минимального админа»
 * и какой титул стоит. Нужен для снятия при выключении/отвязке, отката при отзыве награды,
 * дедупа перевыставления и для строгого режима (админа нельзя мьютить/банить — сначала demote).
 * «Настоящие» админы организатора в учёт не попадают и не трогаются.
 */
interface ChatAwardTitleRepository {
    fun find(clubId: UUID, telegramId: Long): ChatAwardTitle?
    fun findAllForClub(clubId: UUID): List<ChatAwardTitle>

    /** Выставить/обновить титул (идемпотентно по PK club+telegram). */
    fun upsert(clubId: UUID, telegramId: Long, title: String)
    fun delete(clubId: UUID, telegramId: Long)
    fun deleteAllForClub(clubId: UUID)
}

@Repository
class JooqChatAwardTitleRepository(private val dsl: DSLContext) : ChatAwardTitleRepository {

    override fun find(clubId: UUID, telegramId: Long): ChatAwardTitle? =
        dsl.selectFrom(CHAT_AWARD_TITLES)
            .where(CHAT_AWARD_TITLES.CLUB_ID.eq(clubId))
            .and(CHAT_AWARD_TITLES.TELEGRAM_ID.eq(telegramId))
            .fetchOne()
            ?.let { ChatAwardTitle(it.telegramId!!, it.title!!) }

    override fun findAllForClub(clubId: UUID): List<ChatAwardTitle> =
        dsl.selectFrom(CHAT_AWARD_TITLES)
            .where(CHAT_AWARD_TITLES.CLUB_ID.eq(clubId))
            .fetch()
            .map { ChatAwardTitle(it.telegramId!!, it.title!!) }

    override fun upsert(clubId: UUID, telegramId: Long, title: String) {
        dsl.insertInto(CHAT_AWARD_TITLES)
            .set(CHAT_AWARD_TITLES.CLUB_ID, clubId)
            .set(CHAT_AWARD_TITLES.TELEGRAM_ID, telegramId)
            .set(CHAT_AWARD_TITLES.TITLE, title)
            .set(CHAT_AWARD_TITLES.TITLED_AT, OffsetDateTime.now())
            .onConflict(CHAT_AWARD_TITLES.CLUB_ID, CHAT_AWARD_TITLES.TELEGRAM_ID)
            .doUpdate()
            .set(CHAT_AWARD_TITLES.TITLE, title)
            .set(CHAT_AWARD_TITLES.TITLED_AT, OffsetDateTime.now())
            .execute()
    }

    override fun delete(clubId: UUID, telegramId: Long) {
        dsl.deleteFrom(CHAT_AWARD_TITLES)
            .where(CHAT_AWARD_TITLES.CLUB_ID.eq(clubId))
            .and(CHAT_AWARD_TITLES.TELEGRAM_ID.eq(telegramId))
            .execute()
    }

    override fun deleteAllForClub(clubId: UUID) {
        dsl.deleteFrom(CHAT_AWARD_TITLES)
            .where(CHAT_AWARD_TITLES.CLUB_ID.eq(clubId))
            .execute()
    }
}
