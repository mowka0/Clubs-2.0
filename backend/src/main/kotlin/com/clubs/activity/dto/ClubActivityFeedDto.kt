package com.clubs.activity.dto

/**
 * Two-group club activity feed.
 *
 * The feed is split by each activity's own date (not creation date) so the UI
 * can render upcoming items as full cards and collapse past ones:
 * - [upcoming] — not yet completed, sorted by `relevantDate` ASC (soonest first)
 * - [past] — completed, sorted by `relevantDate` DESC (most recent first)
 *
 * `relevantDate` is `eventDatetime` for events and `deadline` for skladchinas.
 * See [com.clubs.activity.ActivityService] for partition + sort rules.
 */
data class ClubActivityFeedDto(
    val upcoming: List<ActivityItemDto>,
    val past: List<ActivityItemDto>
)
