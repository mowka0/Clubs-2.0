package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationAxis
import com.clubs.generated.jooq.enums.ReputationKind
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
     * Exit-with-obligations (P1b hole B): writes the penalties for the obligations a user
     * abandons by leaving a club, BEFORE the membership cascade deletes their source rows.
     * Each abandoned confirmed booking → a `no_show` (−200); each pending reputation-affecting
     * skladchina with an unexpired deadline → a `skladchina_expired` (−40). Joins the caller's
     * leave transaction so penalty + cascade commit atomically. Idempotent by construction: the
     * ledger UNIQUE(user, source_type, source_id) + ON CONFLICT DO NOTHING means a later natural
     * outcome for the same source collides and the exit row wins — a double leave never double-counts.
     */
    @Transactional
    fun penalizeExit(
        userId: UUID,
        clubId: UUID,
        eventNoShows: List<ExitObligation>,
        skladchinaExpiries: List<ExitObligation>
    ) {
        if (eventNoShows.isEmpty() && skladchinaExpiries.isEmpty()) return
        val entries = eventNoShows.map {
            LedgerEntry(
                userId = userId,
                clubId = clubId,
                axis = ReputationAxis.attendance,
                kind = ReputationKind.no_show,
                points = ReputationPolicy.pointsFor(ReputationKind.no_show),
                occurredAt = it.occurredAt,
                sourceType = ReputationSource.event,
                sourceId = it.sourceId
            )
        } + skladchinaExpiries.map {
            LedgerEntry(
                userId = userId,
                clubId = clubId,
                axis = ReputationAxis.finance,
                kind = ReputationKind.skladchina_expired,
                points = ReputationPolicy.pointsFor(ReputationKind.skladchina_expired),
                occurredAt = it.occurredAt,
                sourceType = ReputationSource.skladchina,
                sourceId = it.sourceId
            )
        }
        appendAndRecompute(entries)
        log.info(
            "Exit penalties written: userId={} clubId={} eventNoShows={} skladchinaExpiries={}",
            userId, clubId, eventNoShows.size, skladchinaExpiries.size
        )
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
        // Deterministic (user, club) order — recompute takes one advisory xact-lock per
        // pair, and two concurrent transactions (event attendance × skladchina close of
        // the same club) acquiring the same pairs in different orders deadlock (40P01).
        // Sorting makes every caller lock in the same global order (F5-13).
        entries.map { it.userId to it.clubId }.toSet()
            .sortedWith(compareBy({ it.first }, { it.second }))
            .forEach { (userId, clubId) -> repository.recompute(userId, clubId) }
    }
}
