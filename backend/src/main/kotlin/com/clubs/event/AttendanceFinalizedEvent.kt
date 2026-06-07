package com.clubs.event

import java.util.UUID

/**
 * Published (once per event) when attendance is finalized, after the 48h dispute
 * window. A reputation listener processes it into the ledger with low latency; the
 * hourly poll is the durable backstop. See docs/modules/reputation-v2.md.
 */
data class AttendanceFinalizedEvent(val eventId: UUID)
