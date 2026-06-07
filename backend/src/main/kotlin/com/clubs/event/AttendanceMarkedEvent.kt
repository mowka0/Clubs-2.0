package com.clubs.event

import java.util.UUID

/**
 * Published when an organizer marks attendance for an event (ATT-3). A bot listener reacts
 * AFTER_COMMIT to DM the participants marked absent, offering them the dispute option — the
 * @Async DM must read the committed `absent` rows, hence the after-commit hop rather than a
 * direct in-transaction call. See AttendanceService.markAttendance and reputation-v2.md.
 */
data class AttendanceMarkedEvent(val eventId: UUID)
