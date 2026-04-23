package com.clubs.reputation

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Reputation calculation based on PRD table 4.4.4:
 *
 * "Железобетонный" (going → confirmed → attended):   +100 reliability
 * "Пустозвон"      (going → confirmed → absent):     -50  reliability
 * "Спонтанный"     (maybe → confirmed → attended):   +30  reliability, +1 spontaneity
 * "Зритель"        (maybe → confirmed → absent):     -20  reliability
 * "Честный"        (going → declined before stage2):  +10 reliability
 */
@Service
class ReputationService(
    private val repository: ReputationRepository
) {

    private val log = LoggerFactory.getLogger(ReputationService::class.java)

    @Scheduled(fixedDelay = 3_600_000) // every hour
    @Transactional
    fun processReputationForFinalizedEvents() {
        val finalizedEvents = repository.findFinalizedEvents()

        finalizedEvents.forEach { event ->
            try {
                calculateReputation(event.eventId, event.clubId)
            } catch (e: Exception) {
                log.error("Failed to calculate reputation for event ${event.eventId}", e)
            }
        }
    }

    fun calculateReputation(eventId: UUID, clubId: UUID) {
        val responses = repository.findResponsesByEvent(eventId)

        responses.forEach { response ->
            val deltas = computeDeltas(response)
            val existing = repository.findByUserAndClub(response.userId, clubId)
            val updated = applyDeltas(existing, response.userId, clubId, deltas)
            repository.save(updated)
        }
    }

    private fun computeDeltas(response: ResponseForReputation): ReputationDeltas {
        val reliabilityDelta = when {
            // "Железобетонный": going → confirmed → attended
            response.stage1Vote == Stage_1Vote.going &&
                response.finalStatus == FinalStatus.confirmed &&
                response.attendance == AttendanceStatus.attended -> 100

            // "Пустозвон": going → confirmed → absent
            response.stage1Vote == Stage_1Vote.going &&
                response.finalStatus == FinalStatus.confirmed &&
                response.attendance == AttendanceStatus.absent -> -50

            // "Спонтанный": maybe → confirmed → attended
            response.stage1Vote == Stage_1Vote.maybe &&
                response.finalStatus == FinalStatus.confirmed &&
                response.attendance == AttendanceStatus.attended -> 30

            // "Зритель": maybe → confirmed → absent
            response.stage1Vote == Stage_1Vote.maybe &&
                response.finalStatus == FinalStatus.confirmed &&
                response.attendance == AttendanceStatus.absent -> -20

            // "Честный": declined
            response.finalStatus == FinalStatus.declined -> 10

            else -> 0
        }

        val spontaneityDelta = if (
            response.stage1Vote == Stage_1Vote.maybe &&
            response.finalStatus == FinalStatus.confirmed &&
            response.attendance == AttendanceStatus.attended
        ) 1 else 0

        val isConfirmedAndAttended = response.finalStatus == FinalStatus.confirmed &&
            response.attendance == AttendanceStatus.attended
        val isConfirmed = response.finalStatus == FinalStatus.confirmed

        return ReputationDeltas(reliabilityDelta, spontaneityDelta, isConfirmedAndAttended, isConfirmed)
    }

    private fun applyDeltas(
        existing: Reputation?,
        userId: UUID,
        clubId: UUID,
        deltas: ReputationDeltas
    ): Reputation {
        val baseReliability = existing?.reliabilityIndex ?: 100
        val newReliability = (baseReliability + deltas.reliability).coerceIn(0, 200)

        val newConfirmations = (existing?.totalConfirmations ?: 0) + (if (deltas.confirmed) 1 else 0)
        val newAttendances = (existing?.totalAttendances ?: 0) + (if (deltas.confirmedAndAttended) 1 else 0)
        val newSpontaneity = (existing?.spontaneityCount ?: 0) + deltas.spontaneity

        val fulfillmentPct = if (newConfirmations > 0) {
            BigDecimal(newAttendances * 100).divide(BigDecimal(newConfirmations), 2, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        return Reputation(
            userId = userId,
            clubId = clubId,
            reliabilityIndex = newReliability,
            promiseFulfillmentPct = fulfillmentPct,
            totalConfirmations = newConfirmations,
            totalAttendances = newAttendances,
            spontaneityCount = newSpontaneity
        )
    }

    private data class ReputationDeltas(
        val reliability: Int,
        val spontaneity: Int,
        val confirmedAndAttended: Boolean,
        val confirmed: Boolean
    )
}
