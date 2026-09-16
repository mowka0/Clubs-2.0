package com.clubs.subscription

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/** MOTHER — материнский платёж со страницы оплаты; RECURRING — дочернее списание шедулером. */
enum class PaymentKind { MOTHER, RECURRING }

/** PENDING — счёт выставлен; SUCCEEDED — провайдер подтвердил; FAILED — отказ или счёт протух. */
enum class PlatformPaymentStatus { PENDING, SUCCEEDED, FAILED }

/** Счёт платформе за чат (таблица platform_payment). Один ряд = один InvId у провайдера. */
data class PlatformPayment(
    val id: UUID,
    val clubId: UUID,
    /** null у материнского счёта до подтверждения: подписка появляется с первой оплатой. */
    val subscriptionId: UUID?,
    val invId: Long,
    val kind: PaymentKind,
    val previousInvId: Long?,
    val amountKopecks: Int,
    val status: PlatformPaymentStatus,
    /** Положение ползунка автопродления в шите на момент чекаута (MOTHER). */
    val autopayRequested: Boolean,
    val paymentMethod: String?,
    val providerFee: BigDecimal?,
    val createdAt: OffsetDateTime,
    val paidAt: OffsetDateTime?,
)
