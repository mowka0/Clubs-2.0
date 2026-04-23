package com.clubs.reputation

import java.util.UUID

interface ReputationRepository {

    fun findByUserAndClub(userId: UUID, clubId: UUID): Reputation?

    fun save(reputation: Reputation)

    fun findFinalizedEvents(): List<FinalizedEventRef>

    fun findResponsesByEvent(eventId: UUID): List<ResponseForReputation>
}
