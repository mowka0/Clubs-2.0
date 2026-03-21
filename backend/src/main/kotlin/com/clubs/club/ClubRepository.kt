package com.clubs.club

import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.tables.records.ClubsRecord
import com.clubs.generated.jooq.tables.references.CLUBS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class ClubRepository(private val dsl: DSLContext) {

    fun create(request: CreateClubRequest, ownerId: UUID): ClubsRecord =
        dsl.insertInto(CLUBS)
            .set(CLUBS.ID, UUID.randomUUID())
            .set(CLUBS.OWNER_ID, ownerId)
            .set(CLUBS.NAME, request.name)
            .set(CLUBS.DESCRIPTION, request.description)
            .set(CLUBS.CATEGORY, ClubCategory.valueOf(request.category))
            .set(CLUBS.ACCESS_TYPE, AccessType.valueOf(request.accessType))
            .set(CLUBS.CITY, request.city)
            .set(CLUBS.DISTRICT, request.district)
            .set(CLUBS.MEMBER_LIMIT, request.memberLimit)
            .set(CLUBS.SUBSCRIPTION_PRICE, request.subscriptionPrice)
            .set(CLUBS.AVATAR_URL, request.avatarUrl)
            .set(CLUBS.RULES, request.rules)
            .set(CLUBS.APPLICATION_QUESTION, request.applicationQuestion)
            .set(CLUBS.MEMBER_COUNT, 0)
            .set(CLUBS.ACTIVITY_RATING, 0)
            .set(CLUBS.IS_ACTIVE, true)
            .returning()
            .fetchOne()!!

    fun findById(id: UUID): ClubsRecord? =
        dsl.selectFrom(CLUBS)
            .where(CLUBS.ID.eq(id))
            .fetchOne()

    fun countByOwnerId(ownerId: UUID): Int =
        dsl.selectCount().from(CLUBS)
            .where(CLUBS.OWNER_ID.eq(ownerId))
            .fetchOne(0, Int::class.java) ?: 0

    fun update(id: UUID, request: UpdateClubRequest): ClubsRecord? {
        val step = dsl.update(CLUBS).set(CLUBS.UPDATED_AT, OffsetDateTime.now())
        request.name?.let { step.set(CLUBS.NAME, it) }
        request.description?.let { step.set(CLUBS.DESCRIPTION, it) }
        request.city?.let { step.set(CLUBS.CITY, it) }
        request.district?.let { step.set(CLUBS.DISTRICT, it) }
        request.memberLimit?.let { step.set(CLUBS.MEMBER_LIMIT, it) }
        request.subscriptionPrice?.let { step.set(CLUBS.SUBSCRIPTION_PRICE, it) }
        request.avatarUrl?.let { step.set(CLUBS.AVATAR_URL, it) }
        request.rules?.let { step.set(CLUBS.RULES, it) }
        request.applicationQuestion?.let { step.set(CLUBS.APPLICATION_QUESTION, it) }
        step.where(CLUBS.ID.eq(id)).execute()
        return findById(id)
    }
}
