package com.clubs.subscription

import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.RequiresCapability
import com.clubs.common.auth.RequiresOrganizer
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.security.AuthenticatedUser
import com.clubs.common.security.ClientIpResolver
import com.clubs.payment.PaymentProvider
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class BillingController(
    private val billingService: BillingService,
    private val paymentProvider: PaymentProvider,
) {

    private val log = LoggerFactory.getLogger(BillingController::class.java)

    @RequiresCapability(ClubCapability.MANAGE_EVENTS)
    @GetMapping("/api/clubs/{id}/billing")
    fun status(@PathVariable id: UUID, @AuthenticationPrincipal user: AuthenticatedUser): ResponseEntity<BillingStatusDto> =
        ResponseEntity.ok(billingService.status(id, user.userId))

    @RequiresOrganizer
    @PostMapping("/api/clubs/{id}/billing/checkout")
    fun checkout(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: StartCheckoutRequest?,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ResponseEntity<CheckoutDto> =
        ResponseEntity.ok(billingService.checkout(id, user.userId, (body ?: StartCheckoutRequest()).autopay))

    @RequiresOrganizer
    @PatchMapping("/api/clubs/{id}/billing/autopay")
    fun setAutopay(
        @PathVariable id: UUID,
        @RequestBody body: AutopayRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ResponseEntity<BillingStatusDto> =
        ResponseEntity.ok(billingService.setAutopay(id, user.userId, body.autopay))

    /**
     * ResultURL Robokassa (permitAll в SecurityConfig): подлинность — allowlist IP + подпись
     * Password#2, не JWT. Ответ — `text/plain` ровно `OK<InvId>`; иначе провайдер ретраит.
     * Метод GET/POST выбирается в настройках магазина — принимаем оба.
     */
    @RequestMapping("/api/billing/robokassa/result", method = [RequestMethod.POST, RequestMethod.GET], produces = [MediaType.TEXT_PLAIN_VALUE])
    fun robokassaResult(request: HttpServletRequest): ResponseEntity<String> {
        val clientIp = ClientIpResolver.resolve(request)
        if (!paymentProvider.trustsResultSource(clientIp)) {
            log.warn("Billing result from untrusted address: ip={}", clientIp)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("untrusted source")
        }
        val params = request.parameterMap.mapValues { (_, values) -> values.firstOrNull() ?: "" }
        val notification = try {
            paymentProvider.parseResultNotification(params)
        } catch (e: ForbiddenException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("bad signature")
        }
        return when (billingService.onResult(notification)) {
            ResultOutcome.AMOUNT_MISMATCH -> ResponseEntity.badRequest().body("amount mismatch")
            else -> ResponseEntity.ok("OK${notification.invId}")
        }
    }
}
