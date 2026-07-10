package com.clubs.award

import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.references.CLUB_AWARDS
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.USERS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JooqAwardRepository(
    private val dsl: DSLContext,
    private val mapper: AwardMapper
) : AwardRepository {

    override fun findByMember(clubId: UUID, userId: UUID): List<Award> =
        dsl.selectFrom(CLUB_AWARDS)
            .where(CLUB_AWARDS.CLUB_ID.eq(clubId).and(CLUB_AWARDS.USER_ID.eq(userId)))
            .orderBy(CLUB_AWARDS.AWARDED_AT.desc())
            .fetch()
            .map(mapper::recordToDomain)

    override fun findByUser(userId: UUID): List<Award> =
        dsl.selectFrom(CLUB_AWARDS)
            .where(CLUB_AWARDS.USER_ID.eq(userId))
            .orderBy(CLUB_AWARDS.AWARDED_AT.desc())
            .fetch()
            .map(mapper::recordToDomain)

    override fun findByClub(clubId: UUID): List<Award> =
        dsl.selectFrom(CLUB_AWARDS)
            .where(CLUB_AWARDS.CLUB_ID.eq(clubId))
            .orderBy(CLUB_AWARDS.AWARDED_AT.desc())
            .fetch()
            .map(mapper::recordToDomain)

    override fun findSuggestions(clubId: UUID, limit: Int): List<AwardSuggestion> {
        val usage = DSL.count()
        return dsl.select(CLUB_AWARDS.EMOJI, CLUB_AWARDS.LABEL, usage)
            .from(CLUB_AWARDS)
            .where(CLUB_AWARDS.CLUB_ID.eq(clubId))
            .groupBy(CLUB_AWARDS.EMOJI, CLUB_AWARDS.LABEL)
            .orderBy(usage.desc(), CLUB_AWARDS.LABEL.asc())
            .limit(limit)
            .fetch()
            .map { AwardSuggestion(it.get(CLUB_AWARDS.EMOJI)!!, it.get(CLUB_AWARDS.LABEL)!!) }
    }

    override fun countByMember(clubId: UUID, userId: UUID): Int =
        dsl.fetchCount(
            dsl.selectFrom(CLUB_AWARDS)
                .where(CLUB_AWARDS.CLUB_ID.eq(clubId).and(CLUB_AWARDS.USER_ID.eq(userId)))
        )

    override fun existsByLabel(clubId: UUID, userId: UUID, label: String): Boolean =
        dsl.fetchExists(
            dsl.selectFrom(CLUB_AWARDS)
                .where(
                    CLUB_AWARDS.CLUB_ID.eq(clubId)
                        .and(CLUB_AWARDS.USER_ID.eq(userId))
                        .and(CLUB_AWARDS.LABEL.eq(label))
                )
        )

    override fun insert(award: Award): Award {
        val record = dsl.newRecord(CLUB_AWARDS).apply {
            id = award.id
            clubId = award.clubId
            userId = award.userId
            emoji = award.emoji
            label = award.label
            awardedBy = award.awardedBy
            awardedAt = award.awardedAt
        }
        record.store()
        return mapper.recordToDomain(record)
    }

    override fun delete(awardId: UUID, clubId: UUID, userId: UUID): Int =
        dsl.deleteFrom(CLUB_AWARDS)
            .where(
                CLUB_AWARDS.ID.eq(awardId)
                    .and(CLUB_AWARDS.CLUB_ID.eq(clubId))
                    .and(CLUB_AWARDS.USER_ID.eq(userId))
            )
            .execute()

    override fun findTagSyncRows(clubId: UUID): List<TagSyncRow> =
        // DISTINCT ON от memberships: одна строка на живого участника, LEFT JOIN наград —
        // участники без наград тоже нужны (обратная синхронизация тег→награда, правила PO).
        dsl.select(MEMBERSHIPS.USER_ID, USERS.TELEGRAM_ID, CLUB_AWARDS.LABEL)
            .distinctOn(MEMBERSHIPS.USER_ID)
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .leftJoin(CLUB_AWARDS).on(
                CLUB_AWARDS.USER_ID.eq(MEMBERSHIPS.USER_ID)
                    .and(CLUB_AWARDS.CLUB_ID.eq(MEMBERSHIPS.CLUB_ID))
            )
            .where(
                MEMBERSHIPS.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIPS.STATUS.ne(MembershipStatus.cancelled))
            )
            .orderBy(MEMBERSHIPS.USER_ID, CLUB_AWARDS.AWARDED_AT.desc().nullsLast())
            .fetch {
                TagSyncRow(
                    userId = it.get(MEMBERSHIPS.USER_ID)!!,
                    telegramId = it.get(USERS.TELEGRAM_ID)!!,
                    label = it.get(CLUB_AWARDS.LABEL)
                )
            }
}
