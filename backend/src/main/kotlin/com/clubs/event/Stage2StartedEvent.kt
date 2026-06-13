package com.clubs.event

/**
 * Published when an event transitions to Stage 2 (S2T-2). A bot listener reacts AFTER_COMMIT
 * to DM going/maybe voters asking them to confirm participation (PRD §4.4.2 step 1) — the
 * @Async DM queries voter rows on a separate connection, which only sees the transition and
 * waitlist assignments once the scheduler transaction has committed. Carries the full [Event]
 * snapshot because the DM body needs title/datetime, which the transition never changes.
 */
data class Stage2StartedEvent(val event: Event)
