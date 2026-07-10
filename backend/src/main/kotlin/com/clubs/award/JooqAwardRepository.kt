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

    override fun findTitleCandidates(clubId: UUID): List<AwardTitleCandidate> =
        // DISTINCT ON: одна строка на участника — его ПОСЛЕДНЯЯ награда (титул = последняя, решение PO).
        dsl.select(CLUB_AWARDS.USER_ID, USERS.TELEGRAM_ID, CLUB_AWARDS.LABEL, MEMBERSHIPS.STATUS)
            .distinctOn(CLUB_AWARDS.USER_ID)
            .from(CLUB_AWARDS)
            .join(USERS).on(USERS.ID.eq(CLUB_AWARDS.USER_ID))
            .join(MEMBERSHIPS).on(
                MEMBERSHIPS.USER_ID.eq(CLUB_AWARDS.USER_ID)
                    .and(MEMBERSHIPS.CLUB_ID.eq(CLUB_AWARDS.CLUB_ID))
            )
            .where(
                CLUB_AWARDS.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIPS.STATUS.ne(MembershipStatus.cancelled))
            )
            .orderBy(CLUB_AWARDS.USER_ID, CLUB_AWARDS.AWARDED_AT.desc())
            .fetch {
                AwardTitleCandidate(
                    userId = it.get(CLUB_AWARDS.USER_ID)!!,
                    telegramId = it.get(USERS.TELEGRAM_ID)!!,
                    label = it.get(CLUB_AWARDS.LABEL)!!,
                    membershipStatus = it.get(MEMBERSHIPS.STATUS)!!.literal
                )
            }
}
