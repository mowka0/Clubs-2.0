package com.clubs.skladchina

import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.RequiresCapability
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

/** Эндпоинты сбора (docs/modules/skladchina-v3.md § 7 «Сборы»). Права проверяют сервисы. */
@RestController
class SkladchinaController(
    private val creationService: SkladchinaCreationService,
    private val queryService: SkladchinaQueryService,
    private val participationService: SkladchinaParticipationService,
    private val lifecycleService: SkladchinaLifecycleService
) {
    private val log = LoggerFactory.getLogger(SkladchinaController::class.java)

    // Список сборов клуба в «Управлении»: единственное оставшееся применение MANAGE_SKLADCHINA.
    @RequiresCapability(ClubCapability.MANAGE_SKLADCHINA, clubIdParam = "clubId")
    @GetMapping("/api/clubs/{clubId}/skladchinas/active")
    fun getClubActiveSkladchinas(
        @PathVariable clubId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<MySkladchinaListItemDto>> =
        ResponseEntity.ok(queryService.getClubActiveSkladchinas(clubId, user.userId))

    @GetMapping("/api/clubs/{clubId}/skladchinas/splittable-events")
    fun getSplittableEvents(
        @PathVariable clubId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<SplittableEventDto>> =
        ResponseEntity.ok(queryService.getSplittableEvents(clubId, user.userId))

    @PostMapping("/api/clubs/{clubId}/skladchinas")
    fun create(
        @PathVariable clubId: UUID,
        @RequestBody @Valid request: CreateSkladchinaRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Create skladchina: clubId={} userId={} kind={} title='{}'", clubId, user.userId, request.kind, request.title)
        return ResponseEntity.status(HttpStatus.CREATED).body(creationService.createSkladchina(clubId, request, user.userId))
    }

    @GetMapping("/api/skladchinas/{id}")
    fun getDetail(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> =
        ResponseEntity.ok(queryService.getDetail(id, user.userId))

    @GetMapping("/api/events/{eventId}/skladchina")
    fun getEventSkladchina(@PathVariable eventId: UUID): ResponseEntity<EventSplitStateDto> =
        ResponseEntity.ok(queryService.findEventSplitState(eventId))

    @PostMapping("/api/skladchinas/{id}/join")
    fun join(
        @PathVariable id: UUID,
        @RequestBody(required = false) @Valid request: JoinSkladchinaRequest?,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina join: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(participationService.join(id, user.userId, request?.note))
    }

    @PostMapping("/api/skladchinas/{id}/leave")
    fun leave(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina leave: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(participationService.leave(id, user.userId))
    }

    @PostMapping("/api/skladchinas/{id}/contribute")
    fun contribute(
        @PathVariable id: UUID,
        @RequestBody @Valid request: ContributeRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina contribute: id={} userId={} amount={}", id, user.userId, request.amountKopecks)
        return ResponseEntity.ok(participationService.contribute(id, user.userId, request.amountKopecks))
    }

    @PostMapping("/api/skladchinas/{id}/lock")
    fun lock(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina lock: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(lifecycleService.lock(id, user.userId))
    }

    @PostMapping("/api/skladchinas/{id}/order")
    fun order(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina order: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(lifecycleService.order(id, user.userId))
    }

    @PostMapping("/api/skladchinas/{id}/close")
    fun close(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina close: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(lifecycleService.close(id, user.userId))
    }

    @PostMapping("/api/skladchinas/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina cancel: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(lifecycleService.cancel(id, user.userId))
    }

    @PostMapping("/api/skladchinas/{id}/debts")
    fun addDebtor(
        @PathVariable id: UUID,
        @RequestBody @Valid request: AddDebtorRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina add debtor: id={} target={} by={}", id, request.userId, user.userId)
        return ResponseEntity.ok(participationService.addDebtor(id, user.userId, request.userId, request.amountKopecks))
    }

    @PostMapping("/api/skladchinas/{id}/debts/{debtId}/replace")
    fun replaceDebtor(
        @PathVariable id: UUID,
        @PathVariable debtId: UUID,
        @RequestBody @Valid request: ReplaceDebtorRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina replace debtor: id={} debt={} new={} by={}", id, debtId, request.userId, user.userId)
        return ResponseEntity.ok(participationService.replaceDebtor(id, debtId, user.userId, request.userId))
    }
}
