package com.clubs.subscription

import com.clubs.chatlink.BotChatStatus
import com.clubs.chatlink.ChatLink
import com.clubs.club.Club
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.SubscriptionPayerRole
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import java.time.OffsetDateTime
import java.util.UUID

/** Общие билдеры доменных объектов для юнит-тестов биллинга. */
internal object BillingTestFixtures {

    const val PRICE = 19900

    fun club(clubId: UUID = UUID.randomUUID(), ownerId: UUID = UUID.randomUUID()): Club {
        val now = OffsetDateTime.now()
        return Club(
            id = clubId, ownerId = ownerId, name = "Бег по средам", description = "", category = ClubCategory.sport,
            accessType = AccessType.`open`, city = "Moscow", district = null, memberLimit = 30,
            subscriptionPrice = 0, avatarUrl = null, rules = null, applicationQuestion = null, inviteLink = null,
            memberCount = 1, isActive = true, paymentLink = null, paymentMethodNote = null,
            createdAt = now, updatedAt = now,
        )
    }

    fun link(club: Club, chatId: Long = -1001234567890L, botStatus: BotChatStatus = BotChatStatus.ADMINISTRATOR) = ChatLink(
        clubId = club.id, chatId = chatId, chatTitle = "Бег по средам", linkedByUserId = club.ownerId,
        linkedAt = OffsetDateTime.now(), botStatus = botStatus,
        canPinMessages = true, canInviteUsers = true, canRestrictMembers = true, canManageTags = true,
        doorEnabled = false, doorInviteLink = null, livePinEnabled = true, skladchinaStatusEnabled = true,
        strictModeEnabled = false, awardTagsEnabled = false,
    )

    fun subscription(
        club: Club,
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        periodEnd: OffsetDateTime = OffsetDateTime.now().plusDays(20),
        autopay: Boolean = true,
        autopayPossible: Boolean = true,
        providerToken: String? = "100001",
        chargeAttempts: Int = 0,
    ) = ServiceSubscription(
        id = UUID.randomUUID(), payerUserId = club.ownerId, payerRole = SubscriptionPayerRole.ORGANIZER,
        plan = SubscriptionPlan.CHAT, subjectClubId = club.id, status = status, currentPeriodEnd = periodEnd,
        providerToken = providerToken, createdAt = OffsetDateTime.now().minusDays(10), updatedAt = OffsetDateTime.now(),
        autopay = autopay, autopayPossible = autopayPossible, chargeAttempts = chargeAttempts,
    )

    fun payment(
        club: Club,
        kind: PaymentKind = PaymentKind.MOTHER,
        subscriptionId: UUID? = null,
        invId: Long = 100001,
        status: PlatformPaymentStatus = PlatformPaymentStatus.PENDING,
        autopayRequested: Boolean = true,
        createdAt: OffsetDateTime = OffsetDateTime.now().minusMinutes(5),
    ) = PlatformPayment(
        id = UUID.randomUUID(), clubId = club.id, subscriptionId = subscriptionId, invId = invId, kind = kind,
        previousInvId = if (kind == PaymentKind.RECURRING) 100001 else null, amountKopecks = PRICE, status = status,
        autopayRequested = autopayRequested, paymentMethod = null, providerFee = null, createdAt = createdAt, paidAt = null,
    )
}
