package com.clubs.chatlink

import com.clubs.generated.jooq.tables.records.EventChatPinsRecord
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.EVENT_CHAT_PINS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqEventChatPinRepository(
    private val dsl: DSLContext
) : EventChatPinRepository {

    override fun findByEventId(eventId: UUID): EventChatPin? =
        dsl.selectFrom(EVENT_CHAT_PINS)
            .where(EVENT_CHAT_PINS.EVENT_ID.eq(eventId))
            .fetchOne()
            ?.let(::toDomain)

    override fun insert(pin: EventChatPin): EventChatPin {
        val record = dsl.insertInto(EVENT_CHAT_PINS)
            .set(EVENT_CHAT_PINS.EVENT_ID, pin.eventId)
            .set(EVENT_CHAT_PINS.CHAT_ID, pin.chatId)
            .set(EVENT_CHAT_PINS.MESSAGE_ID, pin.messageId)
            .set(EVENT_CHAT_PINS.CLOSED_AT, pin.closedAt)
            .set(EVENT_CHAT_PINS.SUMMARY_MESSAGE_ID, pin.summaryMessageId)
            .returning()
            .fetchOne()!!
        return toDomain(record)
    }

    override fun findOpenStartedPins(now: OffsetDateTime): List<EventChatPin> =
        dsl.select(EVENT_CHAT_PINS.fields().toList())
            .from(EVENT_CHAT_PINS)
            .join(EVENTS).on(EVENTS.ID.eq(EVENT_CHAT_PINS.EVENT_ID))
            .where(
                EVENT_CHAT_PINS.CLOSED_AT.isNull
                    .and(EVENT_CHAT_PINS.MESSAGE_ID.isNotNull)
                    .and(EVENTS.EVENT_DATETIME.lessOrEqual(now))
            )
            .fetch()
            .map { toDomain(it.into(EVENT_CHAT_PINS)) }

    override fun findOpenByChatId(chatId: Long): List<EventChatPin> =
        dsl.selectFrom(EVENT_CHAT_PINS)
            .where(
                EVENT_CHAT_PINS.CHAT_ID.eq(chatId)
                    .and(EVENT_CHAT_PINS.CLOSED_AT.isNull)
            )
            .fetch()
            .map(::toDomain)

    override fun markClosed(eventId: UUID) {
        dsl.update(EVENT_CHAT_PINS)
            .set(EVENT_CHAT_PINS.CLOSED_AT, OffsetDateTime.now())
            .set(EVENT_CHAT_PINS.UPDATED_AT, OffsetDateTime.now())
            .where(EVENT_CHAT_PINS.EVENT_ID.eq(eventId))
            .execute()
    }

    override fun delete(eventId: UUID) {
        dsl.deleteFrom(EVENT_CHAT_PINS)
            .where(EVENT_CHAT_PINS.EVENT_ID.eq(eventId))
            .execute()
    }

    override fun tryClaimSummary(eventId: UUID, chatId: Long): Boolean {
        // Сентинел 0 = «итог постится прямо сейчас»: реальный message_id Telegram всегда > 0.
        // INSERT-ветка — итог без закрепа (тумблер включили уже после старта события);
        // ON CONFLICT DO UPDATE ... WHERE IS NULL — ровно один победитель среди конкурентов.
        val updated = dsl.insertInto(EVENT_CHAT_PINS)
            .set(EVENT_CHAT_PINS.EVENT_ID, eventId)
            .set(EVENT_CHAT_PINS.CHAT_ID, chatId)
            .set(EVENT_CHAT_PINS.CLOSED_AT, OffsetDateTime.now())
            .set(EVENT_CHAT_PINS.SUMMARY_MESSAGE_ID, SUMMARY_CLAIMED_SENTINEL)
            .onConflict(EVENT_CHAT_PINS.EVENT_ID)
            .doUpdate()
            .set(EVENT_CHAT_PINS.SUMMARY_MESSAGE_ID, SUMMARY_CLAIMED_SENTINEL)
            .set(EVENT_CHAT_PINS.UPDATED_AT, OffsetDateTime.now())
            .where(EVENT_CHAT_PINS.SUMMARY_MESSAGE_ID.isNull)
            .execute()
        return updated > 0
    }

    override fun setSummaryMessageId(eventId: UUID, summaryMessageId: Long) {
        dsl.update(EVENT_CHAT_PINS)
            .set(EVENT_CHAT_PINS.SUMMARY_MESSAGE_ID, summaryMessageId)
            .set(EVENT_CHAT_PINS.UPDATED_AT, OffsetDateTime.now())
            .where(EVENT_CHAT_PINS.EVENT_ID.eq(eventId))
            .execute()
    }

    private fun toDomain(record: EventChatPinsRecord): EventChatPin = EventChatPin(
        eventId = record.eventId!!,
        chatId = record.chatId!!,
        messageId = record.messageId,
        closedAt = record.closedAt,
        summaryMessageId = record.summaryMessageId
    )

    companion object {
        // «Итог занят, но ещё не отправлен» — заглушка вместо message_id (реальные id Telegram > 0)
        private const val SUMMARY_CLAIMED_SENTINEL = 0L
    }
}
