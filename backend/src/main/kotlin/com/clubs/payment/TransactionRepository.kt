package com.clubs.payment

import java.time.OffsetDateTime
import java.util.UUID

interface TransactionRepository {

    fun existsByTelegramChargeId(chargeId: String): Boolean

    fun save(transaction: Transaction): Transaction

    fun sumCompletedSubscriptionRevenueSince(clubId: UUID, since: OffsetDateTime): Int
}
