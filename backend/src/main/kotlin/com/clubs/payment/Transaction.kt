package com.clubs.payment

import com.clubs.generated.jooq.enums.TransactionStatus
import com.clubs.generated.jooq.enums.TransactionType
import java.time.OffsetDateTime
import java.util.UUID

data class Transaction(
    val id: UUID,
    val userId: UUID,
    val clubId: UUID,
    val membershipId: UUID?,
    val type: TransactionType,
    val status: TransactionStatus,
    val amount: Int,
    val platformFee: Int,
    val organizerRevenue: Int,
    val telegramPaymentChargeId: String?,
    val createdAt: OffsetDateTime
)
