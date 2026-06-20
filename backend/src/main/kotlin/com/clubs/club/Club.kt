package com.clubs.club

import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import java.time.OffsetDateTime
import java.util.UUID

data class Club(
    val id: UUID,
    val ownerId: UUID,
    val name: String,
    val description: String,
    val category: ClubCategory,
    val accessType: AccessType,
    val city: String,
    val district: String?,
    val memberLimit: Int,
    val subscriptionPrice: Int,
    val avatarUrl: String?,
    val rules: String?,
    val applicationQuestion: String?,
    val inviteLink: String?,
    val memberCount: Int,
    val isActive: Boolean,
    val telegramGroupId: Long?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)
