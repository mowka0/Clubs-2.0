package com.clubs.event

import com.clubs.common.dto.PageResponse
import com.clubs.common.security.AuthenticatedUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class EventController(private val eventService: EventService) {

    @PostMapping("/api/clubs/{id}/events")
    fun createEvent(
        @PathVariable id: UUID,
        @RequestBody @Valid request: CreateEventRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<EventDetailDto> {
        val event = eventService.createEvent(id, request, user.userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(event)
    }

    @GetMapping("/api/clubs/{id}/events")
    fun getClubEvents(
        @PathVariable id: UUID,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<EventListItemDto>> =
        ResponseEntity.ok(eventService.getClubEvents(id, status, page, size))

    @GetMapping("/api/events/{id}")
    fun getEvent(@PathVariable id: UUID): ResponseEntity<EventDetailDto> =
        ResponseEntity.ok(eventService.getEvent(id))
}
