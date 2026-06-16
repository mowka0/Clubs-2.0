package com.clubs.skladchina

import com.clubs.common.auth.RequiresOrganizer
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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class SkladchinaController(
    private val skladchinaService: SkladchinaService
) {
    private val log = LoggerFactory.getLogger(SkladchinaController::class.java)

    @RequiresOrganizer(clubIdParam = "clubId")
    @GetMapping("/api/clubs/{clubId}/skladchinas/active")
    fun getClubActiveSkladchinas(
        @PathVariable clubId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<MySkladchinaListItemDto>> {
        val list = skladchinaService.getClubActiveSkladchinas(clubId, user.userId)
        return ResponseEntity.ok(list)
    }

    @RequiresOrganizer(clubIdParam = "clubId")
    @PostMapping("/api/clubs/{clubId}/skladchinas")
    fun create(
        @PathVariable clubId: UUID,
        @RequestBody @Valid request: CreateSkladchinaRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Create skladchina: clubId={} userId={} title='{}'", clubId, user.userId, request.title)
        val dto = skladchinaService.createSkladchina(clubId, request, user.userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(dto)
    }

    @GetMapping("/api/skladchinas/{id}")
    fun getDetail(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> =
        ResponseEntity.ok(skladchinaService.getDetail(id, user.userId))

    @PostMapping("/api/skladchinas/{id}/mark-paid")
    fun markPaid(
        @PathVariable id: UUID,
        @RequestBody @Valid request: MarkPaidRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina mark-paid: id={} userId={} amount={}", id, user.userId, request.declaredAmountKopecks)
        return ResponseEntity.ok(skladchinaService.markPaid(id, user.userId, request.declaredAmountKopecks))
    }

    @PostMapping("/api/skladchinas/{id}/decline")
    fun decline(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina decline: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(skladchinaService.decline(id, user.userId))
    }

    // V28: participant opens a decline request with a reason (REQUIRES_APPROVAL templates, e.g. split_bill).
    @PostMapping("/api/skladchinas/{id}/request-decline")
    fun requestDecline(
        @PathVariable id: UUID,
        @RequestBody @Valid request: RequestDeclineRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina decline-request: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(skladchinaService.requestDecline(id, user.userId, request.reason))
    }

    // V28: organizer approves/rejects a participant's decline request.
    @PostMapping("/api/skladchinas/{id}/participants/{userId}/resolve-decline")
    fun resolveDecline(
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
        @RequestBody @Valid request: ResolveDeclineRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina resolve-decline: id={} target={} by={} approve={}", id, userId, user.userId, request.approve)
        return ResponseEntity.ok(skladchinaService.resolveDecline(id, user.userId, userId, request.approve))
    }

    // A-2: organizer marks a participant paid ("получил наличкой"). Creator-only (checked in service).
    @PostMapping("/api/skladchinas/{id}/participants/{userId}/mark-paid")
    fun organizerMarkPaid(
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina organizer-mark-paid: id={} target={} by={}", id, userId, user.userId)
        return ResponseEntity.ok(skladchinaService.organizerMarkPaid(id, user.userId, userId))
    }

    // A-2 (toggle): organizer reverts a participant's payment back to pending.
    @PostMapping("/api/skladchinas/{id}/participants/{userId}/unmark")
    fun organizerUnmarkPaid(
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina organizer-unmark: id={} target={} by={}", id, userId, user.userId)
        return ResponseEntity.ok(skladchinaService.organizerUnmarkPaid(id, user.userId, userId))
    }

    // A-3: organizer redistributes the deficit across pending participants.
    @PostMapping("/api/skladchinas/{id}/redistribute")
    fun redistribute(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina redistribute: id={} by={}", id, user.userId)
        return ResponseEntity.ok(skladchinaService.redistributeDeficit(id, user.userId))
    }

    @PostMapping("/api/skladchinas/{id}/close")
    fun close(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina close: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(skladchinaService.closeManually(id, user.userId))
    }
}
