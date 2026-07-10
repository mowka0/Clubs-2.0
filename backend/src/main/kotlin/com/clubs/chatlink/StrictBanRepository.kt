package com.clubs.chatlink

import com.clubs.generated.jooq.tables.references.CHAT_STRICT_BANS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Учёт банов, наложенных строгим режимом (слайс 5): кого бот забанил за уход из клуба.
 * Нужен, чтобы отвязка чата могла снять ВСЕ наши баны перед выходом бота (иначе они
 * навсегда) — и только наши: ручные баны организатора здесь не учитываются.
 */
interface StrictBanRepository {
    /** Зафиксировать наложенный ботом бан (идемпотентно — повторный бан не дублирует строку). */
    fun record(clubId: UUID, telegramId: Long)

    /** Бан снят/неактуален (доступ снова открыт) — убрать из учёта. */
    fun delete(clubId: UUID, telegramId: Long)

    /** Все забаненные строгим режимом в клубе — для снятия при отвязке чата. */
    fun findTelegramIds(clubId: UUID): List<Long>

    /** Очистить учёт клуба (после снятия банов при отвязке). */
    fun deleteAllForClub(clubId: UUID)
}

@Repository
class JooqStrictBanRepository(private val dsl: DSLContext) : StrictBanRepository {

    override fun record(clubId: UUID, telegramId: Long) {
        dsl.insertInto(CHAT_STRICT_BANS)
            .set(CHAT_STRICT_BANS.CLUB_ID, clubId)
            .set(CHAT_STRICT_BANS.TELEGRAM_ID, telegramId)
            .onConflictDoNothing()
            .execute()
    }

    override fun delete(clubId: UUID, telegramId: Long) {
        dsl.deleteFrom(CHAT_STRICT_BANS)
            .where(CHAT_STRICT_BANS.CLUB_ID.eq(clubId))
            .and(CHAT_STRICT_BANS.TELEGRAM_ID.eq(telegramId))
            .execute()
    }

    override fun findTelegramIds(clubId: UUID): List<Long> =
        dsl.select(CHAT_STRICT_BANS.TELEGRAM_ID)
            .from(CHAT_STRICT_BANS)
            .where(CHAT_STRICT_BANS.CLUB_ID.eq(clubId))
            .fetch(CHAT_STRICT_BANS.TELEGRAM_ID)
            .filterNotNull()

    override fun deleteAllForClub(clubId: UUID) {
        dsl.deleteFrom(CHAT_STRICT_BANS)
            .where(CHAT_STRICT_BANS.CLUB_ID.eq(clubId))
            .execute()
    }
}
