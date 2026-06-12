package com.clubs.activity

import com.clubs.activity.dto.ClubActivityFeedDto
import com.clubs.common.auth.RequiresMembership
import com.clubs.common.exception.ValidationException
import com.clubs.common.security.AuthenticatedUser
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class ActivityController(
    private val activityService: ActivityService
) {

    @RequiresMembership(clubIdParam = "id")
    @GetMapping("/api/clubs/{id}/activities")
    fun getClubActivities(
        @PathVariable id: UUID,
        @RequestParam(required = false) type: String?,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ClubActivityFeedDto> {
        val parsedType = parseType(type)
        return ResponseEntity.ok(
            activityService.getClubActivities(clubId = id, userId = user.userId, typeFilter = parsedType)
        )
    }

    private fun parseType(raw: String?): ActivityType? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return ActivityType.fromWire(value)
            ?: throw ValidationException("Invalid type: $raw. Expected one of: event, skladchina")
    }
}
