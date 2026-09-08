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

@RestController
class SkladchinaController(
    private val creationService: SkladchinaCreationService,
    private val queryService: SkladchinaQueryService,
    private val paymentService: SkladchinaPaymentService,
    private val lifecycleService: SkladchinaLifecycleService
) {
    private val log = LoggerFactory.getLogger(SkladchinaController::class.java)

    @RequiresCapability(ClubCapability.MANAGE_SKLADCHINA, clubIdParam = "clubId")
    @GetMapping("/api/clubs/{clubId}/skladchinas/active")
    fun getClubActiveSkladchinas(
        @PathVariable clubId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<MySkladchinaListItemDto>> {
        val list = queryService.getClubActiveSkladchinas(clubId, user.userId)
        return ResponseEntity.ok(list)
    }

    // Шаг «выберите встречу» в форме «Разделить счёт»: только те встречи, по которым сплит
    // реально создастся (явка отмечена, есть кому платить, счёт ещё не делили).
    @RequiresCapability(ClubCapability.MANAGE_SKLADCHINA, clubIdParam = "clubId")
    @GetMapping("/api/clubs/{clubId}/skladchinas/splittable-events")
    fun getSplittableEvents(
        @PathVariable clubId: UUID
    ): ResponseEntity<List<SplittableEventDto>> =
        ResponseEntity.ok(queryService.getSplittableEvents(clubId))

    @RequiresCapability(ClubCapability.MANAGE_SKLADCHINA, clubIdParam = "clubId")
    @PostMapping("/api/clubs/{clubId}/skladchinas")
    fun create(
        @PathVariable clubId: UUID,
        @RequestBody @Valid request: CreateSkladchinaRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Create skladchina: clubId={} userId={} title='{}'", clubId, user.userId, request.title)
        val dto = creationService.createSkladchina(clubId, request, user.userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(dto)
    }

    @GetMapping("/api/skladchinas/{id}")
    fun getDetail(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> =
        ResponseEntity.ok(queryService.getDetail(id, user.userId))

    // Кнопка "Разделить счёт" на EventPage: уже есть ли сплит для этого события (active → открыть его,
    // closed_success → уже собрано)? Оба null, когда можно создать новый сплит.
    @GetMapping("/api/events/{eventId}/skladchina")
    fun getEventSkladchina(
        @PathVariable eventId: UUID
    ): ResponseEntity<EventSplitStateDto> =
        ResponseEntity.ok(queryService.findEventSplitState(eventId))

    @PostMapping("/api/skladchinas/{id}/mark-paid")
    fun markPaid(
        @PathVariable id: UUID,
        @RequestBody @Valid request: MarkPaidRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina mark-paid: id={} userId={} amount={}", id, user.userId, request.declaredAmountKopecks)
        return ResponseEntity.ok(paymentService.markPaid(id, user.userId, request.declaredAmountKopecks))
    }

    // Отказ — всегда заявка с причиной, которую решает организатор (PO 2026-09-09).
    @PostMapping("/api/skladchinas/{id}/request-decline")
    fun requestDecline(
        @PathVariable id: UUID,
        @RequestBody @Valid request: RequestDeclineRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina decline-request: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(paymentService.requestDecline(id, user.userId, request.reason))
    }

    // V28: организатор одобряет/отклоняет запрос участника на отказ.
    @PostMapping("/api/skladchinas/{id}/participants/{userId}/resolve-decline")
    fun resolveDecline(
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
        @RequestBody @Valid request: ResolveDeclineRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina resolve-decline: id={} target={} by={} approve={}", id, userId, user.userId, request.approve)
        return ResponseEntity.ok(paymentService.resolveDecline(id, user.userId, userId, request.approve, request.rejectReason))
    }

    // A-2: организатор отмечает участника оплатившим ("получил наличкой"). Только создатель (проверяется в service).
    @PostMapping("/api/skladchinas/{id}/participants/{userId}/mark-paid")
    fun organizerMarkPaid(
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina organizer-mark-paid: id={} target={} by={}", id, userId, user.userId)
        return ResponseEntity.ok(paymentService.organizerMarkPaid(id, user.userId, userId))
    }

    // A-2 (toggle): организатор возвращает оплату участника обратно в статус ожидания.
    @PostMapping("/api/skladchinas/{id}/participants/{userId}/unmark")
    fun organizerUnmarkPaid(
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina organizer-unmark: id={} target={} by={}", id, userId, user.userId)
        return ResponseEntity.ok(paymentService.organizerUnmarkPaid(id, user.userId, userId))
    }

    // V89: участник снимает СВОЮ отметку об оплате (ошибочный тап), пока сбор идёт.
    @PostMapping("/api/skladchinas/{id}/unmark-paid")
    fun unmarkOwnPayment(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina self-unmark: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(paymentService.unmarkOwnPayment(id, user.userId))
    }

    // V89: «Засчитать всех» — организатор подтверждает разом все неразобранные заявки.
    // Отдельного шага закрытия нет: сбор закроется сам, когда разбирать станет нечего.
    @PostMapping("/api/skladchinas/{id}/confirm-all")
    fun confirmAllPayments(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina confirm-all: id={} by={}", id, user.userId)
        return ResponseEntity.ok(lifecycleService.confirmAllClaimed(id, user.userId))
    }

    // V89: участник оспаривает отклонение оплаты, приложив фото или скриншот чека.
    @PostMapping("/api/skladchinas/{id}/dispute-payment")
    fun disputePayment(
        @PathVariable id: UUID,
        @RequestBody @Valid request: DisputePaymentRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina dispute-payment: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(paymentService.disputePayment(id, user.userId, request.receiptUrl, request.note))
    }

    // V89: решение организатора по оплате участника — и по ходу сбора, и при разборе чека.
    @PostMapping("/api/skladchinas/{id}/participants/{userId}/resolve-payment")
    fun resolveParticipantPayment(
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
        @RequestBody @Valid request: ResolvePaymentRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina resolve-payment: id={} target={} by={} accept={}", id, userId, user.userId, request.accept)
        return ResponseEntity.ok(
            paymentService.resolveParticipantPayment(id, user.userId, userId, request.accept, request.reason)
        )
    }

    @PostMapping("/api/skladchinas/{id}/close")
    fun close(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<SkladchinaDetailDto> {
        log.info("Skladchina close: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(lifecycleService.closeManually(id, user.userId))
    }
}
