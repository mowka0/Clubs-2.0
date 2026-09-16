package com.clubs.subscription

import com.clubs.chatlink.BotChatStatus
import com.clubs.generated.jooq.enums.SubscriptionStatus
import com.clubs.generated.jooq.tables.references.CHAT_TRIAL
import com.clubs.generated.jooq.tables.references.CLUB_CHAT_LINKS
import com.clubs.generated.jooq.tables.references.SERVICE_SUBSCRIPTION
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqChatTrialRepository(private val dsl: DSLContext) : ChatTrialRepository {

    // INSERT … ON CONFLICT DO NOTHING RETURNING: первая встреча заводит строку и получает её
    // started_at, последующие получают пустой RETURNING и читают уже записанный старт.
    override fun startOrGet(chatId: Long, clubId: UUID, eventId: UUID): TrialStart {
        val inserted = dsl.insertInto(CHAT_TRIAL)
            .set(CHAT_TRIAL.CHAT_ID, chatId)
            .set(CHAT_TRIAL.CLUB_ID, clubId)
            .set(CHAT_TRIAL.FIRST_EVENT_ID, eventId)
            .onConflict(CHAT_TRIAL.CHAT_ID)
            .doNothing()
            .returningResult(CHAT_TRIAL.STARTED_AT)
            .fetchOne()
            ?.value1()
        return if (inserted != null) TrialStart(inserted, justStarted = true)
        else TrialStart(findStartedAt(chatId)!!, justStarted = false)
    }

    override fun findStartedAt(chatId: Long): OffsetDateTime? =
        dsl.select(CHAT_TRIAL.STARTED_AT)
            .from(CHAT_TRIAL)
            .where(CHAT_TRIAL.CHAT_ID.eq(chatId))
            .fetchOne()
            ?.value1()

    // Конец периода = started_at + trialDays, поэтому «кончится не позже until» — это
    // started_at <= until − trialDays: арифметика уезжает в Kotlin, запрос остаётся по индексу.
    // Клуб берётся из ЖИВОЙ привязки чата, а не из строки триала: чат мог переехать к другому клубу.
    override fun findTrialsEndingBefore(until: OffsetDateTime, trialDays: Long): List<ChatTrial> =
        dsl.select(CHAT_TRIAL.CHAT_ID, CLUB_CHAT_LINKS.CLUB_ID, CHAT_TRIAL.STARTED_AT, CHAT_TRIAL.REMINDER_DAYS_LEFT)
            .from(CHAT_TRIAL)
            .join(CLUB_CHAT_LINKS).on(CLUB_CHAT_LINKS.CHAT_ID.eq(CHAT_TRIAL.CHAT_ID))
            .where(CHAT_TRIAL.STARTED_AT.le(until.minusDays(trialDays)))
            // Выгнанному боту напоминать не о чем: без него чат бесплатен.
            .and(CLUB_CHAT_LINKS.BOT_STATUS.`in`(BotChatStatus.ADMINISTRATOR.literal, BotChatStatus.MEMBER.literal))
            .andNotExists(
                dsl.selectOne().from(SERVICE_SUBSCRIPTION).where(
                    SERVICE_SUBSCRIPTION.SUBJECT_CLUB_ID.eq(CLUB_CHAT_LINKS.CLUB_ID)
                        .and(SERVICE_SUBSCRIPTION.STATUS.ne(SubscriptionStatus.ENDED)),
                ),
            )
            .fetch {
                ChatTrial(
                    chatId = it[CHAT_TRIAL.CHAT_ID]!!,
                    clubId = it[CLUB_CHAT_LINKS.CLUB_ID]!!,
                    startedAt = it[CHAT_TRIAL.STARTED_AT]!!,
                    reminderDaysLeft = it[CHAT_TRIAL.REMINDER_DAYS_LEFT],
                )
            }

    override fun markReminded(chatId: Long, daysLeft: Int): Int =
        dsl.update(CHAT_TRIAL)
            .set(CHAT_TRIAL.REMINDER_DAYS_LEFT, daysLeft)
            .where(CHAT_TRIAL.CHAT_ID.eq(chatId))
            .execute()

    override fun migrateChatId(oldChatId: Long, newChatId: Long): Int {
        val moved = dsl.update(CHAT_TRIAL)
            .set(CHAT_TRIAL.CHAT_ID, newChatId)
            .where(
                CHAT_TRIAL.CHAT_ID.eq(oldChatId).andNotExists(
                    dsl.selectOne().from(CHAT_TRIAL).where(CHAT_TRIAL.CHAT_ID.eq(newChatId)),
                ),
            )
            .execute()
        if (moved == 0) {
            dsl.deleteFrom(CHAT_TRIAL).where(CHAT_TRIAL.CHAT_ID.eq(oldChatId)).execute()
        }
        return moved
    }
}
