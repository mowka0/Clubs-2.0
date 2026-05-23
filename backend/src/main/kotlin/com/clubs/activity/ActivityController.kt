package com.clubs.activity

import com.clubs.activity.dto.ActivityItemDto
import com.clubs.common.auth.RequiresMembership
import com.clubs.common.dto.PageResponse
import com.clubs.common.exception.ValidationException
import org.springframework.http.ResponseEntity
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
        @RequestParam(defaultValue = "true") includeCompleted: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<ActivityItemDto>> {
        val parsedType = parseType(type)
        if (size > MAX_PAGE_SIZE) {
            throw ValidationException("size must be <= $MAX_PAGE_SIZE")
        }
        val safeSize = size.coerceAtLeast(MIN_PAGE_SIZE)
        val safePage = page.coerceAtLeast(0)
        return ResponseEntity.ok(
            activityService.getClubActivities(
                clubId = id,
                typeFilter = parsedType,
                includeCompleted = includeCompleted,
                page = safePage,
                size = safeSize
            )
        )
    }

    private fun parseType(raw: String?): ActivityType? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return ActivityType.fromWire(value)
            ?: throw ValidationException("Invalid type: $raw. Expected one of: event, skladchina")
    }

    companion object {
        private const val MIN_PAGE_SIZE = 1
        private const val MAX_PAGE_SIZE = 50
    }
}
