package com.clubs.payment

import com.clubs.club.ClubRepository
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.TransactionStatus
import com.clubs.generated.jooq.enums.TransactionType
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.OffsetDateTime
import java.util.UUID

private const val PLATFORM_FEE_PERCENT = 20
private const val SUBSCRIPTION_DAYS = 30L

@Service
class PaymentService(
    private val clubRepository: ClubRepository,
    private val userRepository: UserRepository,
    private val membershipRepository: MembershipRepository,
    private val transactionRepository: TransactionRepository,
    private val telegramClient: TelegramClient
) {

    private val log = LoggerFactory.getLogger(PaymentService::class.java)

    /**
     * Sends a Telegram Stars invoice to the user. Free clubs have no invoice
     * — membership for them is created by the membership module on join.
     *
     * Trust boundary: caller MUST ensure `userId` is the authenticated initiator.
     * Currently invoked only from ClubsBot (Telegram webhook).
     */
    fun createInvoice(userId: UUID, clubId: UUID) {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        val price = club.subscriptionPrice ?: 0
        if (price == 0) return

        val user = userRepository.findById(userId) ?: throw NotFoundException("User not found")

        log.info("Creating invoice: userId={} clubId={} price={} Stars", userId, clubId, price)
        val invoice = SendInvoice.builder()
            .chatId(user.telegramId.toString())
            .title("Подписка: ${club.name}")
            .description("Ежемесячная подписка на клуб «${club.name}»")
            .payload("club_subscription:${clubId}:${userId}")
            .currency("XTR")
            .price(LabeledPrice("Подписка на 30 дней", price))
            .build()

        telegramClient.execute(invoice)
        log.info("Invoice sent: userId={} telegramId={} clubId={}", userId, user.telegramId, clubId)
    }

    /**
     * Handles successful Stars payment from Telegram Bot API.
     * Idempotent — Telegram may retry the webhook. Guarantees:
     *   - transactions.telegram_payment_charge_id has a partial UNIQUE index (V12)
     *   - memberships(user_id, club_id) is UNIQUE (V3)
     *   - @Transactional rolls back the whole operation on any constraint violation
     * so concurrent retries converge to a single committed state.
     */
    @Transactional
    fun handleSuccessfulPayment(
        telegramId: Long,
        telegramChargeId: String,
        payload: String,
        amount: Int
    ) {
        if (amount <= 0) {
            log.warn("Ignoring payment with non-positive amount: telegramId={} amount={}", telegramId, amount)
            return
        }

        val parsed = parsePayload(payload)
        if (parsed == null) {
            log.warn("Unknown payment payload: telegramId={} payload={}", telegramId, payload)
            return
        }
        val (clubId, userId) = parsed

        if (transactionRepository.existsByTelegramChargeId(telegramChargeId)) {
            log.info("Duplicate successful_payment ignored: chargeId={} userId={}", telegramChargeId, userId)
            return
        }

        val club = clubRepository.findById(clubId)
        if (club == null) {
            log.warn("Payment for unknown club: clubId={} userId={}", clubId, userId)
            return
        }

        val expectedPrice = club.subscriptionPrice ?: 0
        if (expectedPrice > 0 && amount != expectedPrice) {
            log.warn(
                "Amount mismatch vs clubs.subscription_price: clubId={} userId={} amount={} expected={}",
                clubId, userId, amount, expectedPrice
            )
        }

        val now = OffsetDateTime.now()
        val existing = membershipRepository.findExpiryRefByUserAndClub(userId, clubId)

        val membershipId: UUID
        val type: TransactionType

        if (existing == null) {
            membershipId = membershipRepository.activateSubscription(userId, clubId, now.plusDays(SUBSCRIPTION_DAYS))
            clubRepository.incrementMemberCount(clubId)
            type = TransactionType.subscription
        } else {
            val newExpiry = existing.subscriptionExpiresAt
                ?.takeIf { it.isAfter(now) }
                ?.plusDays(SUBSCRIPTION_DAYS)
                ?: now.plusDays(SUBSCRIPTION_DAYS)
            membershipRepository.renewSubscription(existing.id, newExpiry)
            membershipId = existing.id
            type = TransactionType.renewal
        }

        val platformFee = amount * PLATFORM_FEE_PERCENT / 100
        val organizerRevenue = amount - platformFee

        try {
            transactionRepository.save(
                Transaction(
                    id = UUID.randomUUID(),
                    userId = userId,
                    clubId = clubId,
                    membershipId = membershipId,
                    type = type,
                    status = TransactionStatus.completed,
                    amount = amount,
                    platformFee = platformFee,
                    organizerRevenue = organizerRevenue,
                    telegramPaymentChargeId = telegramChargeId,
                    createdAt = now
                )
            )
        } catch (e: DuplicateKeyException) {
            // Concurrent retry: another thread already persisted this charge.
            // @Transactional will roll back the membership work we just did;
            // the first committer's state remains authoritative.
            log.info("Concurrent duplicate successful_payment rolled back: chargeId={}", telegramChargeId)
            throw e
        }

        log.info("Payment processed: userId={} clubId={} amount={} Stars type={}", userId, clubId, amount, type)
    }

    private fun parsePayload(payload: String): Pair<UUID, UUID>? {
        val parts = payload.split(":")
        if (parts.size != 3 || parts[0] != "club_subscription") return null
        return try {
            UUID.fromString(parts[1]) to UUID.fromString(parts[2])
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
