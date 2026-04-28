package com.clubs.event

import com.clubs.common.auth.RequiresMembership
import com.clubs.common.auth.RequiresOrganizer
import com.clubs.common.dto.PageResponse
import com.clubs.common.security.AuthenticatedUser
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
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

    private val log = LoggerFactory.getLogger(EventController::class.java)

    @RequiresOrganizer
    @PostMapping("/api/clubs/{id}/events")
    fun createEvent(
        @PathVariable id: UUID,
        @RequestBody @Valid request: CreateEventRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<EventDetailDto> {
        log.info("Create event: clubId={} title='{}' userId={}", id, request.title, user.userId)
        val event = eventService.createEvent(id, request, user.userId)
        log.info("Event created: id={} clubId={} userId={}", event.id, id, user.userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(event)
    }

    @RequiresMembership
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
    ): ResponseEntity<VoteResponseDto> {
        log.info("Vote on event {}: userId={} vote={}", id, user.userId, request.vote)
        return ResponseEntity.ok(voteService.castVote(id, user.userId, request))
    }

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
    ): ResponseEntity<ConfirmResponseDto> {
        log.info("Confirm participation: eventId={} userId={}", id, user.userId)
        return ResponseEntity.ok(stage2Service.confirmParticipation(id, user.userId))
    }

    @PostMapping("/api/events/{id}/decline")
    fun declineParticipation(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ConfirmResponseDto> {
        log.info("Decline participation: eventId={} userId={}", id, user.userId)
        return ResponseEntity.ok(stage2Service.declineParticipation(id, user.userId))
    }

    @PostMapping("/api/events/{id}/attendance")
    fun markAttendance(
        @PathVariable id: UUID,
        @RequestBody request: MarkAttendanceRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<AttendanceResultDto> {
        log.info("Mark attendance: eventId={} userId={} count={}", id, user.userId, request.attendance.size)
        return ResponseEntity.ok(attendanceService.markAttendance(id, user.userId, request))
    }

    @PostMapping("/api/events/{id}/dispute")
    fun disputeAttendance(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<AttendanceResultDto> {
        log.info("Dispute attendance: eventId={} userId={}", id, user.userId)
        return ResponseEntity.ok(attendanceService.disputeAttendance(id, user.userId))
    }

    @PostMapping("/api/events/{id}/attendance/{userId}/resolve")
    fun resolveDispute(
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
        @RequestBody request: ResolveDisputeRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<AttendanceResultDto> {
        log.info("Resolve dispute: eventId={} targetUserId={} attended={} resolvedBy={}", id, userId, request.attended, user.userId)
        return ResponseEntity.ok(attendanceService.resolveDispute(id, user.userId, userId, request.attended))
    }
}
