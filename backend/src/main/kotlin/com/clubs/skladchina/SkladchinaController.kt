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

    @PostMapping("/api/skladchinas/{id}/close")
    fun close(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina close: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(skladchinaService.closeManually(id, user.userId))
    }
}
