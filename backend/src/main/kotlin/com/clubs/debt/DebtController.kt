package com.clubs.debt

import com.clubs.common.security.AuthenticatedUser
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Эндпоинты долгов (docs/modules/skladchina-v3.md § 7 «Долги»). Стороны долга проверяет сервис. */
@RestController
@RequestMapping("/api/debts")
class DebtController(
    private val debtService: DebtService,
    private val settlementService: DebtSettlementService,
    private val queryService: DebtQueryService
) {
    private val log = LoggerFactory.getLogger(DebtController::class.java)

    @GetMapping
    fun overview(@AuthenticationPrincipal user: AuthenticatedUser): ResponseEntity<DebtsOverviewDto> =
        ResponseEntity.ok(queryService.overview(user.userId))

    @GetMapping("/with/{userId}")
    fun pair(
        @PathVariable userId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<DebtPairDto> =
        ResponseEntity.ok(queryService.pair(user.userId, userId))

    @PostMapping("/{id}/promise")
    fun promise(
        @PathVariable id: UUID,
        @RequestBody @Valid request: PromiseRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<DebtDto> {
        log.info("Debt promise: id={} userId={} date={}", id, user.userId, request.date)
        return ResponseEntity.ok(debtService.promise(id, user.userId, request.date))
    }

    @PostMapping("/{id}/claim")
    fun claim(@PathVariable id: UUID, @AuthenticationPrincipal user: AuthenticatedUser): ResponseEntity<DebtDto> {
        log.info("Debt claim: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(debtService.claim(id, user.userId))
    }

    @PostMapping("/{id}/unclaim")
    fun unclaim(@PathVariable id: UUID, @AuthenticationPrincipal user: AuthenticatedUser): ResponseEntity<DebtDto> {
        log.info("Debt unclaim: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(debtService.unclaim(id, user.userId))
    }

    @PostMapping("/{id}/confirm")
    fun confirm(@PathVariable id: UUID, @AuthenticationPrincipal user: AuthenticatedUser): ResponseEntity<DebtDto> {
        log.info("Debt confirm: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(debtService.confirm(id, user.userId))
    }

    @PostMapping("/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @RequestBody(required = false) @Valid request: RejectDebtRequest?,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<DebtDto> {
        log.info("Debt reject: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(debtService.reject(id, user.userId, request?.note))
    }

    @PostMapping("/{id}/forgive")
    fun forgive(@PathVariable id: UUID, @AuthenticationPrincipal user: AuthenticatedUser): ResponseEntity<DebtDto> {
        log.info("Debt forgive: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(debtService.forgive(id, user.userId))
    }

    @PatchMapping("/{id}")
    fun changeAmount(
        @PathVariable id: UUID,
        @RequestBody @Valid request: ChangeDebtAmountRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<DebtDto> {
        log.info("Debt change amount: id={} userId={} amount={}", id, user.userId, request.amountKopecks)
        return ResponseEntity.ok(debtService.changeAmount(id, user.userId, request.amountKopecks))
    }

    @PostMapping("/{id}/receipt")
    fun receipt(
        @PathVariable id: UUID,
        @RequestBody @Valid request: DebtReceiptRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<DebtDto> {
        log.info("Debt receipt: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(debtService.setReceipt(id, user.userId, request.url))
    }

    @PostMapping("/{id}/note")
    fun note(
        @PathVariable id: UUID,
        @RequestBody @Valid request: DebtNoteRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<DebtDto> =
        ResponseEntity.ok(debtService.setNote(id, user.userId, request.note))

    @PostMapping("/with/{userId}/settle")
    fun settle(
        @PathVariable userId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<DebtPairDto> {
        log.info("Settlement claim: payer={} payee={}", user.userId, userId)
        return ResponseEntity.ok(settlementService.settle(user.userId, userId))
    }

    @PostMapping("/settlements/{id}/confirm")
    fun confirmSettlement(@PathVariable id: UUID, @AuthenticationPrincipal user: AuthenticatedUser): ResponseEntity<DebtPairDto> {
        log.info("Settlement confirm: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(settlementService.confirm(id, user.userId))
    }

    @PostMapping("/settlements/{id}/reject")
    fun rejectSettlement(@PathVariable id: UUID, @AuthenticationPrincipal user: AuthenticatedUser): ResponseEntity<DebtPairDto> {
        log.info("Settlement reject: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(settlementService.reject(id, user.userId))
    }
}
