package com.clubs.interest

import com.clubs.generated.jooq.tables.references.INTERESTS
import com.clubs.generated.jooq.tables.references.USER_INTERESTS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JooqInterestRepository(private val dsl: DSLContext) : InterestRepository {

    override fun suggest(prefix: String, limit: Int): List<String> =
        dsl.select(INTERESTS.NAME)
            .from(INTERESTS)
            // startsWith escapes %/_ and emits `name LIKE 'prefix%'`, which the
            // varchar_pattern_ops index serves; names are stored canonical so a
            // plain (case-sensitive) prefix match is enough.
            .where(INTERESTS.NAME.startsWith(prefix))
            .orderBy(INTERESTS.USAGE_COUNT.desc(), INTERESTS.NAME.asc())
            .limit(limit)
            .fetch(INTERESTS.NAME)
            .filterNotNull()

    override fun upsertAll(names: List<String>): Map<String, UUID> {
        if (names.isEmpty()) return emptyMap()
        names.forEach { name ->
            dsl.insertInto(INTERESTS, INTERESTS.ID, INTERESTS.NAME)
                .values(UUID.randomUUID(), name)
                .onConflict(INTERESTS.NAME).doNothing()
                .execute()
        }
        return dsl.select(INTERESTS.NAME, INTERESTS.ID)
            .from(INTERESTS)
            .where(INTERESTS.NAME.`in`(names))
            .fetch()
            .associate { it.get(INTERESTS.NAME)!! to it.get(INTERESTS.ID)!! }
    }

    override fun findUserInterestIds(userId: UUID): Set<UUID> =
        dsl.select(USER_INTERESTS.INTEREST_ID)
            .from(USER_INTERESTS)
            .where(USER_INTERESTS.USER_ID.eq(userId))
            .fetch(USER_INTERESTS.INTEREST_ID)
            .filterNotNull()
            .toSet()

    override fun findUserInterestNames(userId: UUID): List<String> =
        dsl.select(INTERESTS.NAME)
            .from(USER_INTERESTS)
            .join(INTERESTS).on(INTERESTS.ID.eq(USER_INTERESTS.INTEREST_ID))
            .where(USER_INTERESTS.USER_ID.eq(userId))
            .orderBy(INTERESTS.NAME.asc())
            .fetch(INTERESTS.NAME)
            .filterNotNull()

    override fun findUserInterestNamesByUserIds(userIds: Collection<UUID>): Map<UUID, List<String>> {
        if (userIds.isEmpty()) return emptyMap()
        return dsl.select(USER_INTERESTS.USER_ID, INTERESTS.NAME)
            .from(USER_INTERESTS)
            .join(INTERESTS).on(INTERESTS.ID.eq(USER_INTERESTS.INTEREST_ID))
            .where(USER_INTERESTS.USER_ID.`in`(userIds))
            .orderBy(USER_INTERESTS.USER_ID.asc(), INTERESTS.NAME.asc())
            .fetch()
            .groupBy(
                { it.get(USER_INTERESTS.USER_ID)!! },
                { it.get(INTERESTS.NAME)!! }
            )
    }

    override fun linkUserInterests(userId: UUID, interestIds: Collection<UUID>) {
        if (interestIds.isEmpty()) return
        interestIds.forEach { interestId ->
            dsl.insertInto(USER_INTERESTS, USER_INTERESTS.USER_ID, USER_INTERESTS.INTEREST_ID)
                .values(userId, interestId)
                .onConflict(USER_INTERESTS.USER_ID, USER_INTERESTS.INTEREST_ID).doNothing()
                .execute()
        }
    }

    override fun unlinkUserInterests(userId: UUID, interestIds: Collection<UUID>) {
        if (interestIds.isEmpty()) return
        dsl.deleteFrom(USER_INTERESTS)
            .where(
                USER_INTERESTS.USER_ID.eq(userId)
                    .and(USER_INTERESTS.INTEREST_ID.`in`(interestIds))
            )
            .execute()
    }

    override fun adjustUsage(interestIds: Collection<UUID>, delta: Int) {
        if (interestIds.isEmpty() || delta == 0) return
        var condition = INTERESTS.ID.`in`(interestIds)
        if (delta < 0) condition = condition.and(INTERESTS.USAGE_COUNT.greaterThan(0))
        dsl.update(INTERESTS)
            .set(INTERESTS.USAGE_COUNT, INTERESTS.USAGE_COUNT.plus(delta))
            .where(condition)
            .execute()
    }
}
