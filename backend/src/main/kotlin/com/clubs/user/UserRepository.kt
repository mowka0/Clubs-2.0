package com.clubs.user

import com.clubs.generated.jooq.tables.references.USERS
import com.clubs.generated.jooq.tables.records.UsersRecord
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class UserRepository(private val dsl: DSLContext) {

    fun findByTelegramId(telegramId: Long): UsersRecord? =
        dsl.selectFrom(USERS)
            .where(USERS.TELEGRAM_ID.eq(telegramId))
            .fetchOne()

    fun findById(id: UUID): UsersRecord? =
        dsl.selectFrom(USERS)
            .where(USERS.ID.eq(id))
            .fetchOne()

    /** Обновляет только редактируемые пользователем скаляры профиля; синхронизируемые из TG поля не трогает. */
    fun updateProfileFields(userId: UUID, country: String?, city: String?, bio: String?) {
        dsl.update(USERS)
            .set(USERS.COUNTRY, country)
            .set(USERS.CITY, city)
            .set(USERS.BIO, bio)
            .set(USERS.UPDATED_AT, OffsetDateTime.now())
            .where(USERS.ID.eq(userId))
            .execute()
    }

    /** Батч-поиск пользователей по ID (активность решает вызывающий). Пустой вход → пустой выход (без SQL). */
    fun findByIds(ids: Collection<UUID>): List<UsersRecord> {
        if (ids.isEmpty()) return emptyList()
        return dsl.selectFrom(USERS)
            .where(USERS.ID.`in`(ids))
            .fetch()
    }

    /** Telegram chat ID для заданных user ID, в произвольном порядке. Нужен NotificationService для батч-DM. */
    fun findTelegramIds(userIds: Collection<UUID>): List<Long> {
        if (userIds.isEmpty()) return emptyList()
        return dsl.select(USERS.TELEGRAM_ID)
            .from(USERS)
            .where(USERS.ID.`in`(userIds))
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()
    }

    fun upsert(
        telegramId: Long,
        telegramUsername: String?,
        firstName: String,
        lastName: String?,
        avatarUrl: String?
    ): UsersRecord {
        val existing = findByTelegramId(telegramId)
        if (existing != null) {
            dsl.update(USERS)
                .set(USERS.TELEGRAM_USERNAME, telegramUsername)
                .set(USERS.FIRST_NAME, firstName)
                .set(USERS.LAST_NAME, lastName)
                .set(USERS.AVATAR_URL, avatarUrl)
                .set(USERS.UPDATED_AT, OffsetDateTime.now())
                .where(USERS.ID.eq(existing.id))
                .execute()
            return findById(existing.id!!)!!
        }

        return dsl.insertInto(USERS)
            .set(USERS.ID, UUID.randomUUID())
            .set(USERS.TELEGRAM_ID, telegramId)
            .set(USERS.TELEGRAM_USERNAME, telegramUsername)
            .set(USERS.FIRST_NAME, firstName)
            .set(USERS.LAST_NAME, lastName)
            .set(USERS.AVATAR_URL, avatarUrl)
            .returning()
            .fetchOne()!!
    }
}
