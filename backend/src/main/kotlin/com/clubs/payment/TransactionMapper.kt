package com.clubs.payment

import com.clubs.generated.jooq.tables.records.TransactionsRecord
import org.springframework.stereotype.Component

@Component
class TransactionMapper {

    fun toDomain(record: TransactionsRecord): Transaction = Transaction(
        id = record.id!!,
        userId = record.userId!!,
        clubId = record.clubId!!,
        membershipId = record.membershipId,
        type = record.type!!,
        status = record.status!!,
        amount = record.amount!!,
        platformFee = record.platformFee!!,
        organizerRevenue = record.organizerRevenue!!,
        telegramPaymentChargeId = record.telegramPaymentChargeId,
        createdAt = record.createdAt!!
    )
}
