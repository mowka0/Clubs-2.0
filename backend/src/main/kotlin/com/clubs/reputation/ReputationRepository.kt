package com.clubs.reputation

import java.util.UUID

interface ReputationRepository {

    fun findByUserAndClub(userId: UUID, clubId: UUID): Reputation?

    fun save(reputation: Reputation)

    fun findFinalizedEvents(): List<FinalizedEventRef>

    fun findResponsesByEvent(eventId: UUID): List<ResponseForReputation>

    /**
     * Returns the most recently updated reputation row for a user across all clubs.
     * Used by ClubsBot.handleMyRating (/мой_рейтинг command).
     * NOTE: semantically returns a single-club row picked by updated_at DESC,
     * not a cross-club aggregate. Pre-existing behaviour, see
     * docs/backlog/telegram-bot-prd-gaps.md.
     */
    fun findLatestByUserId(userId: UUID): Reputation?

    /**
     * Cross-club reputation aggregate for a batch of users (one SQL query).
     * Returns `userId → aggregate`. Users absent from `user_club_reputation`
     * are absent from the map; callers default to [PeerStatsAggregate.EMPTY].
     * Empty input → emptyMap (no SQL hit).
     */
    fun aggregateByUserIds(userIds: Collection<UUID>): Map<UUID, PeerStatsAggregate>
}
