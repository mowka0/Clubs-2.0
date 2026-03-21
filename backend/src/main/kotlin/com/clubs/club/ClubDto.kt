package com.clubs.club

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class ClubDetailDto(
    val id: UUID,
    val ownerId: UUID,
    val name: String,
    val description: String,
    val category: String,
    val accessType: String,
    val city: String,
    val district: String?,
    val memberLimit: Int,
    val subscriptionPrice: Int,
    val avatarUrl: String?,
    val rules: String?,
    val applicationQuestion: String?,
    val inviteLink: String?,
    val memberCount: Int,
    val activityRating: Int,
    val isActive: Boolean
)

data class CreateClubRequest(
    @field:NotBlank(message = "Name is required")
    @field:Size(max = 60, message = "Name must be at most 60 characters")
    val name: String,

    @field:NotBlank(message = "Description is required")
    @field:Size(max = 500, message = "Description must be at most 500 characters")
    val description: String,

    @field:NotBlank(message = "Category is required")
    val category: String,

    @field:NotBlank(message = "Access type is required")
    val accessType: String,

    @field:NotBlank(message = "City is required")
    val city: String,

    val district: String? = null,

    @field:NotNull(message = "Member limit is required")
    @field:Min(value = 10, message = "Member limit must be at least 10")
    @field:Max(value = 80, message = "Member limit must be at most 80")
    val memberLimit: Int,

    @field:NotNull(message = "Subscription price is required")
    @field:Min(value = 0, message = "Subscription price must be non-negative")
    val subscriptionPrice: Int,

    val avatarUrl: String? = null,
    val rules: String? = null,
    val applicationQuestion: String? = null
)

data class UpdateClubRequest(
    @field:Size(max = 60, message = "Name must be at most 60 characters")
    val name: String? = null,

    @field:Size(max = 500, message = "Description must be at most 500 characters")
    val description: String? = null,

    val city: String? = null,
    val district: String? = null,

    @field:Min(value = 10, message = "Member limit must be at least 10")
    @field:Max(value = 80, message = "Member limit must be at most 80")
    val memberLimit: Int? = null,

    @field:Min(value = 0, message = "Subscription price must be non-negative")
    val subscriptionPrice: Int? = null,

    val avatarUrl: String? = null,
    val rules: String? = null,
    val applicationQuestion: String? = null
)
