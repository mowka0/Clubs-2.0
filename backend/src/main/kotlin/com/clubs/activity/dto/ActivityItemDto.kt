package com.clubs.activity.dto

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Unified club-activity feed item — discriminated union over `type`.
 *
 * Discriminator design: the `type` field is **declared explicitly** on each
 * concrete subtype with a constant string value. Jackson serializes it as a
 * regular property, producing exactly one `"type": "event" | "skladchina"`
 * field on the wire.
 *
 * Why no `@JsonTypeInfo` / `@JsonSubTypes`: this DTO is **response-only** —
 * the server never deserializes it (the frontend consumes it as a TS
 * discriminated union). Polymorphic deserialization machinery would add
 * complexity for a feature we don't use, and combining it with the explicit
 * `type` val was brittle (relied on `EXISTING_PROPERTY` staying idempotent
 * across Jackson versions).
 *
 * Lives in [com.clubs.activity] because it merges two different domains
 * (events, skladchinas) and should not leak into either's package.
 */
sealed class ActivityItemDto {
    abstract val type: String
    abstract val id: UUID
    abstract val clubId: UUID
    abstract val title: String
    abstract val createdAt: OffsetDateTime
    abstract val isCompleted: Boolean

    data class EventActivity(
        override val id: UUID,
        override val clubId: UUID,
        override val title: String,
        override val createdAt: OffsetDateTime,
        override val isCompleted: Boolean,
        val eventDatetime: OffsetDateTime,
        val locationText: String,
        val participantLimit: Int,
        val goingCount: Int,
        val status: String,
        val descriptionPreview: String?,
        val photoUrl: String?,
        // True when this event awaits the requesting user's stage-1 vote or stage-2
        // confirmation — drives the "Проголосуй"/"Подтверди участие" badge in the feed.
        val actionRequired: Boolean
    ) : ActivityItemDto() {
        override val type: String = "event"
    }

    data class SkladchinaActivity(
        override val id: UUID,
        override val clubId: UUID,
        override val title: String,
        override val createdAt: OffsetDateTime,
        override val isCompleted: Boolean,
        val paymentMode: String,
        val totalGoalKopecks: Long?,
        val collectedKopecks: Long,
        val deadline: OffsetDateTime,
        val participantCount: Int,
        val paidCount: Int,
        val status: String,
        val affectsReputation: Boolean,
        val photoUrl: String?
    ) : ActivityItemDto() {
        override val type: String = "skladchina"
    }
}
