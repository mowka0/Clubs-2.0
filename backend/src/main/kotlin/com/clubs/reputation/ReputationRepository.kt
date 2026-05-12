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
}
