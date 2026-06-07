package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationAxis
import com.clubs.generated.jooq.enums.ReputationSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Core reputation pipeline over the append-only ledger (reputation v2, P1a).
 * The user_club_reputation table is a derived cache: recompute() is the ONLY writer.
 * Bug B (hourly re-inflation) is dead by construction — ledger rows are unique per
 * (user, source) and inserted ON CONFLICT DO NOTHING; aggregates are recomputed, not
 * incremented. See docs/modules/reputation-v2.md.
 */
@Service
class ReputationService(
    private val repository: ReputationRepository
) {

    private val log = LoggerFactory.getLogger(ReputationService::class.java)

    /**
     * Processes one finalized event into the ledger. Called by both the event
     * listener (low-latency) and the poll (durable backstop); the atomic claim makes
     * them mutually exclusive. REQUIRES_NEW so each event commits independently and a
     * failure rolls back the claim (the poll then retries) — must be invoked across a
     * bean boundary (ReputationScheduler / AttendanceFinalizedListener) for the proxy.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun processFinalizedEvent(eventId: UUID) {
        if (!repository.claimEvent(eventId)) return // already processed — no-op

        val ctx = repository.findEventContext(eventId) ?: return
        val entries = repository.findConfirmedResponses(eventId)
            .filter { it.userId != ctx.ownerId } // anti-farm rule 1: owner does not accrue in own club
            .map { response ->
                val kind = ReputationPolicy.attendanceKind(response.stage1Vote, response.attendance)
                LedgerEntry(
                    userId = response.userId,
                    clubId = ctx.clubId,
                    axis = ReputationAxis.attendance,
                    kind = kind,
                    points = ReputationPolicy.pointsFor(kind),
                    occurredAt = ctx.eventDatetime,
                    sourceType = ReputationSource.event,
                    sourceId = eventId
                )
            }

        appendAndRecompute(entries)
        log.info("Reputation processed: eventId={} entries={}", eventId, entries.size)
    }

    /**
     * Appends ledger rows (idempotent) and recomputes the cache for every affected
     * (user, club). No new transaction — joins the caller's (processFinalizedEvent's
     * REQUIRES_NEW, or skladchina's close transaction).
     */
    @Transactional
    fun appendAndRecompute(entries: List<LedgerEntry>) {
        if (entries.isEmpty()) return
        repository.appendLedgerIfAbsent(entries)
        entries.map { it.userId to it.clubId }.toSet()
            .forEach { (userId, clubId) -> repository.recompute(userId, clubId) }
    }
}
