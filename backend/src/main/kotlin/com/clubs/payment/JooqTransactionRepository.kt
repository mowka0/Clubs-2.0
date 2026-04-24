package com.clubs.payment

import com.clubs.generated.jooq.tables.references.TRANSACTIONS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

@Repository
class JooqTransactionRepository(
    private val dsl: DSLContext,
    private val mapper: TransactionMapper
) : TransactionRepository {

    override fun existsByTelegramChargeId(chargeId: String): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(TRANSACTIONS)
                .where(TRANSACTIONS.TELEGRAM_PAYMENT_CHARGE_ID.eq(chargeId))
        )

    override fun save(transaction: Transaction): Transaction {
        val record = dsl.insertInto(TRANSACTIONS)
            .set(TRANSACTIONS.ID, transaction.id)
            .set(TRANSACTIONS.USER_ID, transaction.userId)
            .set(TRANSACTIONS.CLUB_ID, transaction.clubId)
            .set(TRANSACTIONS.MEMBERSHIP_ID, transaction.membershipId)
            .set(TRANSACTIONS.TYPE, transaction.type)
            .set(TRANSACTIONS.STATUS, transaction.status)
            .set(TRANSACTIONS.AMOUNT, transaction.amount)
            .set(TRANSACTIONS.PLATFORM_FEE, transaction.platformFee)
            .set(TRANSACTIONS.ORGANIZER_REVENUE, transaction.organizerRevenue)
            .set(TRANSACTIONS.TELEGRAM_PAYMENT_CHARGE_ID, transaction.telegramPaymentChargeId)
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }
}
