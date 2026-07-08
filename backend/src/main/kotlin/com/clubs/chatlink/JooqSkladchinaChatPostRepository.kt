package com.clubs.chatlink

import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.tables.records.SkladchinaChatPostsRecord
import com.clubs.generated.jooq.tables.references.SKLADCHINAS
import com.clubs.generated.jooq.tables.references.SKLADCHINA_CHAT_POSTS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqSkladchinaChatPostRepository(
    private val dsl: DSLContext
) : SkladchinaChatPostRepository {

    override fun findBySkladchinaId(skladchinaId: UUID): SkladchinaChatPost? =
        dsl.selectFrom(SKLADCHINA_CHAT_POSTS)
            .where(SKLADCHINA_CHAT_POSTS.SKLADCHINA_ID.eq(skladchinaId))
            .fetchOne()
            ?.let(::toDomain)

    override fun insertIfAbsent(post: SkladchinaChatPost): Boolean =
        dsl.insertInto(SKLADCHINA_CHAT_POSTS)
            .set(SKLADCHINA_CHAT_POSTS.SKLADCHINA_ID, post.skladchinaId)
            .set(SKLADCHINA_CHAT_POSTS.CHAT_ID, post.chatId)
            .set(SKLADCHINA_CHAT_POSTS.MESSAGE_ID, post.messageId)
            .set(SKLADCHINA_CHAT_POSTS.CLOSED_AT, post.closedAt)
            .onConflict(SKLADCHINA_CHAT_POSTS.SKLADCHINA_ID)
            .doNothing()
            .execute() > 0

    override fun findOpenByChatId(chatId: Long): List<SkladchinaChatPost> =
        dsl.selectFrom(SKLADCHINA_CHAT_POSTS)
            .where(
                SKLADCHINA_CHAT_POSTS.CHAT_ID.eq(chatId)
                    .and(SKLADCHINA_CHAT_POSTS.CLOSED_AT.isNull)
            )
            .fetch()
            .map(::toDomain)

    override fun findOpenPostsOfInactiveSkladchinas(): List<SkladchinaChatPost> =
        dsl.select(SKLADCHINA_CHAT_POSTS.fields().toList())
            .from(SKLADCHINA_CHAT_POSTS)
            .join(SKLADCHINAS).on(SKLADCHINAS.ID.eq(SKLADCHINA_CHAT_POSTS.SKLADCHINA_ID))
            .where(
                SKLADCHINA_CHAT_POSTS.CLOSED_AT.isNull
                    .and(SKLADCHINAS.STATUS.ne(SkladchinaStatus.active))
            )
            .fetch()
            .map { toDomain(it.into(SKLADCHINA_CHAT_POSTS)) }

    override fun markClosed(skladchinaId: UUID) {
        dsl.update(SKLADCHINA_CHAT_POSTS)
            .set(SKLADCHINA_CHAT_POSTS.CLOSED_AT, OffsetDateTime.now())
            .set(SKLADCHINA_CHAT_POSTS.UPDATED_AT, OffsetDateTime.now())
            .where(SKLADCHINA_CHAT_POSTS.SKLADCHINA_ID.eq(skladchinaId))
            .execute()
    }

    override fun delete(skladchinaId: UUID) {
        dsl.deleteFrom(SKLADCHINA_CHAT_POSTS)
            .where(SKLADCHINA_CHAT_POSTS.SKLADCHINA_ID.eq(skladchinaId))
            .execute()
    }

    private fun toDomain(record: SkladchinaChatPostsRecord): SkladchinaChatPost = SkladchinaChatPost(
        skladchinaId = record.skladchinaId!!,
        chatId = record.chatId!!,
        messageId = record.messageId!!,
        closedAt = record.closedAt
    )
}
