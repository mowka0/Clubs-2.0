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
class EventController(
    private val eventService: EventService,
    private val voteService: VoteService,
    private val stage2Service: Stage2Service,
    private val attendanceService: AttendanceService
) {

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

    @PostMapping("/api/events/{id}/vote")
    fun castVote(
        @PathVariable id: UUID,
        @RequestBody request: CastVoteRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<VoteResponseDto> =
        ResponseEntity.ok(voteService.castVote(id, user.userId, request))

    @GetMapping("/api/events/{id}/my-vote")
    fun getMyVote(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<MyVoteDto> =
        ResponseEntity.ok(voteService.getMyVote(id, user.userId))

    @PostMapping("/api/events/{id}/confirm")
    fun confirmParticipation(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ConfirmResponseDto> =
        ResponseEntity.ok(stage2Service.confirmParticipation(id, user.userId))

    @PostMapping("/api/events/{id}/decline")
    fun declineParticipation(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ConfirmResponseDto> =
        ResponseEntity.ok(stage2Service.declineParticipation(id, user.userId))

    @PostMapping("/api/events/{id}/attendance")
    fun markAttendance(
        @PathVariable id: UUID,
        @RequestBody request: MarkAttendanceRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<AttendanceResultDto> =
        ResponseEntity.ok(attendanceService.markAttendance(id, user.userId, request))

    @PostMapping("/api/events/{id}/dispute")
    fun disputeAttendance(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<AttendanceResultDto> =
        ResponseEntity.ok(attendanceService.disputeAttendance(id, user.userId))

    @PostMapping("/api/events/{id}/attendance/{userId}/resolve")
    fun resolveDispute(
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
        @RequestBody request: ResolveDisputeRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<AttendanceResultDto> =
        ResponseEntity.ok(attendanceService.resolveDispute(id, user.userId, userId, request.attended))
}
