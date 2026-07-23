package com.clubs.user

import com.clubs.generated.jooq.tables.references.USERS
import com.clubs.generated.jooq.tables.references.USER_INTERESTS
import com.clubs.generated.jooq.tables.records.UsersRecord
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** Вехи профиль-квеста (V66): метки первого заполнения поля; NULL = веха не достигнута. */
data class QuestFlags(
    val cityAt: OffsetDateTime?,
    val interestsAt: OffsetDateTime?,
    val bioAt: OffsetDateTime?
)

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

    /**
     * Помечает онбординг пройденным. Условие `onboarded_at IS NULL` — в самом UPDATE, а не
     * отдельной проверкой перед ним: два одновременных тапа кнопки дают два запроса, и только
     * один из них обновит строку. Второй получит 0 и превратится в 409, без гонки read-then-write.
     *
     * @return true — пометили сейчас; false — уже был пройден (или пользователя нет).
     */
    fun markOnboarded(userId: UUID, at: OffsetDateTime): Boolean =
        dsl.update(USERS)
            .set(USERS.ONBOARDED_AT, at)
            .set(USERS.UPDATED_AT, at)
            .where(USERS.ID.eq(userId))
            .and(USERS.ONBOARDED_AT.isNull)
            .execute() > 0

    /**
     * Проставляет НЕдостигнутые вехи профиль-квеста, чьи поля сейчас непусты, одним атомарным
     * UPDATE (COALESCE + CASE): уже поставленные метки не трогаются, гонок read-then-write нет.
     * city/bio к этому моменту нормализованы через blankToNull → достаточно IS NOT NULL.
     */
    fun markQuestMilestones(userId: UUID, at: OffsetDateTime = OffsetDateTime.now()) {
        val hasInterest = DSL.exists(
            DSL.selectOne().from(USER_INTERESTS).where(USER_INTERESTS.USER_ID.eq(USERS.ID))
        )
        dsl.update(USERS)
            .set(USERS.QUEST_CITY_AT, DSL.coalesce(USERS.QUEST_CITY_AT, DSL.`when`(USERS.CITY.isNotNull, at)))
            .set(USERS.QUEST_BIO_AT, DSL.coalesce(USERS.QUEST_BIO_AT, DSL.`when`(USERS.BIO.isNotNull, at)))
            .set(USERS.QUEST_INTERESTS_AT, DSL.coalesce(USERS.QUEST_INTERESTS_AT, DSL.`when`(hasInterest, at)))
            .where(USERS.ID.eq(userId))
            .execute()
    }

    /** Вехи профиль-квеста пользователя; null — пользователя нет. */
    fun findQuestFlags(userId: UUID): QuestFlags? =
        dsl.select(USERS.QUEST_CITY_AT, USERS.QUEST_INTERESTS_AT, USERS.QUEST_BIO_AT)
            .from(USERS)
            .where(USERS.ID.eq(userId))
            .fetchOne { QuestFlags(it[USERS.QUEST_CITY_AT], it[USERS.QUEST_INTERESTS_AT], it[USERS.QUEST_BIO_AT]) }

    /** Батч-вехи квеста для others-проекции уровня (ApplicantSignalService): один запрос на всех. */
    fun findQuestFlagsByIds(ids: Collection<UUID>): Map<UUID, QuestFlags> {
        if (ids.isEmpty()) return emptyMap()
        return dsl.select(USERS.ID, USERS.QUEST_CITY_AT, USERS.QUEST_INTERESTS_AT, USERS.QUEST_BIO_AT)
            .from(USERS)
            .where(USERS.ID.`in`(ids))
            .fetchMap(
                { it[USERS.ID]!! },
                { QuestFlags(it[USERS.QUEST_CITY_AT], it[USERS.QUEST_INTERESTS_AT], it[USERS.QUEST_BIO_AT]) }
            )
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
