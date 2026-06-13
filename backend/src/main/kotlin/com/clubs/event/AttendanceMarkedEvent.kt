package com.clubs.event

import java.util.UUID

/**
 * Published when an organizer marks attendance for an event (ATT-3). A bot listener reacts
 * AFTER_COMMIT to DM the participants who NEWLY became absent in this mark ([newlyAbsentUserIds]),
 * offering them the dispute option. The user ids are carried on the event (computed synchronously
 * in markAttendance) rather than re-queried by attendance, so a re-mark does not re-DM participants
 * already marked absent (F5-15.2). See AttendanceService.markAttendance and reputation-v2.md.
 */
data class AttendanceMarkedEvent(val eventId: UUID, val newlyAbsentUserIds: List<UUID>)
