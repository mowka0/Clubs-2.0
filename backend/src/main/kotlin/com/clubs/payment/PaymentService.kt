package com.clubs.payment

import com.clubs.club.ClubRepository
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.enums.TransactionStatus
import com.clubs.generated.jooq.enums.TransactionType
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.TRANSACTIONS
import com.clubs.generated.jooq.tables.references.USERS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.OffsetDateTime
import java.util.UUID

@Service
class PaymentService(
    private val dsl: DSLContext,
    private val clubRepository: ClubRepository,
    private val telegramClient: TelegramClient
) {

    private val log = LoggerFactory.getLogger(PaymentService::class.java)

    /**
     * Creates a Telegram Stars invoice for club subscription.
     * Sends it as a DM to the user's Telegram account.
     */
    fun createInvoice(userId: UUID, clubId: UUID) {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        val price = club.subscriptionPrice ?: 0
        if (price == 0) {
            // Free club — just create membership directly
            return
        }

        val user = dsl.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchOne()
            ?: throw NotFoundException("User not found")

        log.info("Creating invoice: userId={} clubId={} price={} Stars", userId, clubId, price)
        val invoice = SendInvoice.builder()
            .chatId(user.telegramId.toString())
            .title("Подписка: ${club.name}")
            .description("Ежемесячная подписка на клуб «${club.name}»")
            .payload("club_subscription:${clubId}:${userId}")
            .currency("XTR") // Telegram Stars
            .price(LabeledPrice("Подписка на 30 дней", price))
            .build()

        telegramClient.execute(invoice)
        log.info("Invoice sent: userId={} telegramId={} clubId={}", userId, user.telegramId, clubId)
    }

    /**
     * Handles successful Stars payment.
     * Called from ClubsBot when a successful_payment message is received.
     */
    fun handleSuccessfulPayment(
        telegramId: Long,
        telegramChargeId: String,
        payload: String,
        amount: Int
    ) {
        // Payload format: "club_subscription:{clubId}:{userId}"
        val parts = payload.split(":")
        if (parts.size != 3 || parts[0] != "club_subscription") {
            log.warn("Unknown payment payload: $payload")
            return
        }

        val clubId = UUID.fromString(parts[1])
        val userId = UUID.fromString(parts[2])

        val club = clubRepository.findById(clubId) ?: return

        // Create or renew membership
        val existing = dsl.selectFrom(MEMBERSHIPS)
            .where(MEMBERSHIPS.USER_ID.eq(userId).and(MEMBERSHIPS.CLUB_ID.eq(clubId)))
            .fetchOne()

        val expiresAt = OffsetDateTime.now().plusDays(30)

        if (existing == null) {
            dsl.insertInto(MEMBERSHIPS)
                .set(MEMBERSHIPS.USER_ID, userId)
                .set(MEMBERSHIPS.CLUB_ID, clubId)
                .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
                .set(MEMBERSHIPS.ROLE, MembershipRole.member)
                .set(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT, expiresAt)
                .execute()

            dsl.update(CLUBS)
                .set(CLUBS.MEMBER_COUNT, (club.memberCount ?: 0) + 1)
                .where(CLUBS.ID.eq(clubId))
                .execute()
        } else {
            val newExpiry = if (existing.subscriptionExpiresAt?.isAfter(OffsetDateTime.now()) == true)
                existing.subscriptionExpiresAt!!.plusDays(30)
            else expiresAt

            dsl.update(MEMBERSHIPS)
                .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
                .set(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT, newExpiry)
                .where(MEMBERSHIPS.USER_ID.eq(userId).and(MEMBERSHIPS.CLUB_ID.eq(clubId)))
                .execute()
        }

        // Record transaction
        val platformFee = (amount * 0.2).toInt()
        val organizerRevenue = amount - platformFee

        dsl.insertInto(TRANSACTIONS)
            .set(TRANSACTIONS.USER_ID, userId)
            .set(TRANSACTIONS.CLUB_ID, clubId)
            .set(TRANSACTIONS.TYPE, TransactionType.subscription)
            .set(TRANSACTIONS.STATUS, TransactionStatus.completed)
            .set(TRANSACTIONS.AMOUNT, amount)
            .set(TRANSACTIONS.PLATFORM_FEE, platformFee)
            .set(TRANSACTIONS.ORGANIZER_REVENUE, organizerRevenue)
            .set(TRANSACTIONS.TELEGRAM_PAYMENT_CHARGE_ID, telegramChargeId)
            .execute()

        log.info("Payment processed: userId={}, clubId={}, amount={} Stars", userId, clubId, amount)
    }
}
