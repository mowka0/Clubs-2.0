package com.clubs.payment

import com.clubs.generated.jooq.enums.TransactionStatus
import com.clubs.generated.jooq.enums.TransactionType
import com.clubs.generated.jooq.tables.references.TRANSACTIONS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

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

    override fun sumCompletedSubscriptionRevenueSince(clubId: UUID, since: OffsetDateTime): Int =
        dsl.select(DSL.coalesce(DSL.sum(TRANSACTIONS.AMOUNT), DSL.`val`(0)))
            .from(TRANSACTIONS)
            .where(
                TRANSACTIONS.CLUB_ID.eq(clubId)
                    .and(TRANSACTIONS.TYPE.eq(TransactionType.subscription))
                    .and(TRANSACTIONS.STATUS.eq(TransactionStatus.completed))
                    .and(TRANSACTIONS.CREATED_AT.greaterOrEqual(since))
            )
            .fetchOne(0, Int::class.java) ?: 0
}
