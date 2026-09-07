package com.clubs.subscription

import com.clubs.generated.jooq.tables.references.CHAT_FREE_MEETING
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JooqFreeMeetingRepository(private val dsl: DSLContext) : FreeMeetingRepository {

    // INSERT … ON CONFLICT DO UPDATE … WHERE released_at IS NOT NULL: одна строка = взяли
    // (новая или возвращённая отменой), ноль строк = бесплатная уже использована.
    override fun claim(chatId: Long, clubId: UUID, eventId: UUID): Boolean =
        dsl.insertInto(CHAT_FREE_MEETING)
            .set(CHAT_FREE_MEETING.CHAT_ID, chatId)
            .set(CHAT_FREE_MEETING.CLUB_ID, clubId)
            .set(CHAT_FREE_MEETING.EVENT_ID, eventId)
            .onConflict(CHAT_FREE_MEETING.CHAT_ID)
            .doUpdate()
            .set(CHAT_FREE_MEETING.CLUB_ID, clubId)
            .set(CHAT_FREE_MEETING.EVENT_ID, eventId)
            .set(CHAT_FREE_MEETING.USED_AT, DSL.currentOffsetDateTime())
            .setNull(CHAT_FREE_MEETING.RELEASED_AT)
            .where(CHAT_FREE_MEETING.RELEASED_AT.isNotNull)
            .execute() > 0

    override fun isUsed(chatId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne().from(CHAT_FREE_MEETING)
                .where(CHAT_FREE_MEETING.CHAT_ID.eq(chatId).and(CHAT_FREE_MEETING.RELEASED_AT.isNull)),
        )

    override fun release(eventId: UUID): Int =
        dsl.update(CHAT_FREE_MEETING)
            .set(CHAT_FREE_MEETING.RELEASED_AT, DSL.currentOffsetDateTime())
            .where(CHAT_FREE_MEETING.EVENT_ID.eq(eventId).and(CHAT_FREE_MEETING.RELEASED_AT.isNull))
            .execute()

    override fun migrateChatId(oldChatId: Long, newChatId: Long): Int {
        val moved = dsl.update(CHAT_FREE_MEETING)
            .set(CHAT_FREE_MEETING.CHAT_ID, newChatId)
            .where(
                CHAT_FREE_MEETING.CHAT_ID.eq(oldChatId).andNotExists(
                    dsl.selectOne().from(CHAT_FREE_MEETING).where(CHAT_FREE_MEETING.CHAT_ID.eq(newChatId)),
                ),
            )
            .execute()
        if (moved == 0) {
            dsl.deleteFrom(CHAT_FREE_MEETING).where(CHAT_FREE_MEETING.CHAT_ID.eq(oldChatId)).execute()
        }
        return moved
    }
}
