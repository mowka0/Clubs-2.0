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
