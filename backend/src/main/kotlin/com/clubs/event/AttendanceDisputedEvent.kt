package com.clubs.event

import java.util.UUID

/**
 * Published when a participant disputes their "absent" mark (ATT-3). A bot listener reacts
 * AFTER_COMMIT to DM the organizer: an unresolved dispute is converted back to absent (no_show
 * penalty) when the dispute window expires, so the organizer has to learn about the dispute
 * while there is still time to review it — without this DM an organizer who never reopens the
 * event page lets the penalty land unreviewed.
 */
data class AttendanceDisputedEvent(val eventId: UUID, val disputerUserId: UUID)
