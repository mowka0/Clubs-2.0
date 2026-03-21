package com.clubs.reputation

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.USER_CLUB_REPUTATION
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Reputation calculation based on PRD table 4.4.4:
 *
 * "Железобетонный" (going → confirmed → attended):   +100 reliability
 * "Пустозвон"      (going → confirmed → absent):     -50  reliability
 * "Спонтанный"     (maybe → confirmed → attended):   +30  reliability, +1 spontaneity
 * "Зритель"        (maybe → confirmed → absent):     -20  reliability
 * "Честный"        (going → declined before stage2):  +10 reliability
 * No-show (going → not confirmed, absent):           -30  reliability
 */
@Service
class ReputationService(private val dsl: DSLContext) {

    private val log = LoggerFactory.getLogger(ReputationService::class.java)

    @Scheduled(fixedDelay = 3_600_000) // every hour
    @Transactional
    fun processReputationForFinalizedEvents() {
        // Find finalized events where reputation hasn't been calculated yet
        val finalizedEvents = dsl.selectFrom(EVENTS)
            .where(
                EVENTS.ATTENDANCE_FINALIZED.eq(true)
                    .and(EVENTS.ATTENDANCE_MARKED.eq(true))
            )
            .fetch()

        finalizedEvents.forEach { event ->
            try {
                calculateReputation(event.id!!, event.clubId!!)
            } catch (e: Exception) {
                log.error("Failed to calculate reputation for event ${event.id}", e)
            }
        }
    }

    fun calculateReputation(eventId: UUID, clubId: UUID) {
        val responses = dsl.selectFrom(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId))
            .fetch()

        responses.forEach { response ->
            val userId = response.userId ?: return@forEach
            val stage1Vote = response.stage_1Vote
            val finalStatus = response.finalStatus
            val attendance = response.attendance

            val reliabilityDelta = when {
                // "Железобетонный": going → confirmed → attended
                stage1Vote == Stage_1Vote.going && finalStatus == FinalStatus.confirmed && attendance == AttendanceStatus.attended -> 100
                // "Пустозвон": going → confirmed → absent
                stage1Vote == Stage_1Vote.going && finalStatus == FinalStatus.confirmed && attendance == AttendanceStatus.absent -> -50
                // "Спонтанный": maybe → confirmed → attended
                stage1Vote == Stage_1Vote.maybe && finalStatus == FinalStatus.confirmed && attendance == AttendanceStatus.attended -> 30
                // "Зритель": maybe → confirmed → absent
                stage1Vote == Stage_1Vote.maybe && finalStatus == FinalStatus.confirmed && attendance == AttendanceStatus.absent -> -20
                // "Честный": declined
                finalStatus == FinalStatus.declined -> 10
                else -> 0
            }

            val spontaneityDelta = if (stage1Vote == Stage_1Vote.maybe && finalStatus == FinalStatus.confirmed && attendance == AttendanceStatus.attended) 1 else 0
            val isConfirmedAndAttended = finalStatus == FinalStatus.confirmed && attendance == AttendanceStatus.attended
            val isConfirmed = finalStatus == FinalStatus.confirmed

            upsertReputation(userId, clubId, reliabilityDelta, spontaneityDelta, isConfirmedAndAttended, isConfirmed)
        }
    }

    private fun upsertReputation(
        userId: UUID,
        clubId: UUID,
        reliabilityDelta: Int,
        spontaneityDelta: Int,
        confirmedAndAttended: Boolean,
        confirmed: Boolean
    ) {
        val existing = dsl.selectFrom(USER_CLUB_REPUTATION)
            .where(USER_CLUB_REPUTATION.USER_ID.eq(userId).and(USER_CLUB_REPUTATION.CLUB_ID.eq(clubId)))
            .fetchOne()

        if (existing == null) {
            val newReliability = (100 + reliabilityDelta).coerceIn(0, 200)
            val newConfirmations = if (confirmed) 1 else 0
            val newAttendances = if (confirmedAndAttended) 1 else 0
            val fulfillmentPct = if (newConfirmations > 0) BigDecimal(newAttendances * 100).divide(BigDecimal(newConfirmations), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO

            dsl.insertInto(USER_CLUB_REPUTATION)
                .set(USER_CLUB_REPUTATION.USER_ID, userId)
                .set(USER_CLUB_REPUTATION.CLUB_ID, clubId)
                .set(USER_CLUB_REPUTATION.RELIABILITY_INDEX, newReliability)
                .set(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT, fulfillmentPct)
                .set(USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS, newConfirmations)
                .set(USER_CLUB_REPUTATION.TOTAL_ATTENDANCES, newAttendances)
                .set(USER_CLUB_REPUTATION.SPONTANEITY_COUNT, spontaneityDelta)
                .execute()
        } else {
            val newReliability = (existing.reliabilityIndex!! + reliabilityDelta).coerceIn(0, 200)
            val newConfirmations = existing.totalConfirmations!! + (if (confirmed) 1 else 0)
            val newAttendances = existing.totalAttendances!! + (if (confirmedAndAttended) 1 else 0)
            val fulfillmentPct = if (newConfirmations > 0) BigDecimal(newAttendances * 100).divide(BigDecimal(newConfirmations), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO

            dsl.update(USER_CLUB_REPUTATION)
                .set(USER_CLUB_REPUTATION.RELIABILITY_INDEX, newReliability)
                .set(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT, fulfillmentPct)
                .set(USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS, newConfirmations)
                .set(USER_CLUB_REPUTATION.TOTAL_ATTENDANCES, newAttendances)
                .set(USER_CLUB_REPUTATION.SPONTANEITY_COUNT, existing.spontaneityCount!! + spontaneityDelta)
                .set(USER_CLUB_REPUTATION.UPDATED_AT, OffsetDateTime.now())
                .where(USER_CLUB_REPUTATION.USER_ID.eq(userId).and(USER_CLUB_REPUTATION.CLUB_ID.eq(clubId)))
                .execute()
        }
    }
}
