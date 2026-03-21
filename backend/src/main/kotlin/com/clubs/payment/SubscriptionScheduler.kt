package com.clubs.payment

import com.clubs.bot.NotificationService
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.USERS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Component
class SubscriptionScheduler(
    private val dsl: DSLContext,
    private val notificationService: NotificationService
) {

    private val log = LoggerFactory.getLogger(SubscriptionScheduler::class.java)

    @Scheduled(cron = "0 0 9 * * *") // every day at 09:00
    @Transactional
    fun checkSubscriptions() {
        val now = OffsetDateTime.now()
        val warningThreshold = now.plusDays(3)
        val gracePeriodEnd = now.minusDays(3)

        // 1. Send 3-day warning notifications
        val expiringSoon = dsl
            .select(MEMBERSHIPS.ID, MEMBERSHIPS.USER_ID, MEMBERSHIPS.CLUB_ID, USERS.TELEGRAM_ID, CLUBS.NAME)
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .where(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(warningThreshold))
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.greaterThan(now))
            )
            .fetch()

        expiringSoon.forEach { row ->
            val telegramId = row.get(USERS.TELEGRAM_ID) ?: return@forEach
            val clubName = row.get(CLUBS.NAME)
            notificationService.sendDirectMessage(
                telegramId,
                "⚠️ Ваша подписка на клуб «$clubName» истекает через 3 дня. Продлите доступ в приложении."
            )
        }

        // 2. Move expired subscriptions to grace_period
        val expired = dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.grace_period)
            .where(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(now))
            )
            .execute()

        if (expired > 0) log.info("Moved $expired memberships to grace_period")

        // 3. Expire grace_period memberships older than 3 days
        val fullyExpired = dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.expired)
            .where(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.grace_period)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(gracePeriodEnd))
            )
            .execute()

        if (fullyExpired > 0) {
            // Decrement member_count for affected clubs
            dsl.select(MEMBERSHIPS.CLUB_ID, org.jooq.impl.DSL.count())
                .from(MEMBERSHIPS)
                .where(
                    MEMBERSHIPS.STATUS.eq(MembershipStatus.expired)
                        .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(gracePeriodEnd))
                )
                .groupBy(MEMBERSHIPS.CLUB_ID)
                .fetch()
                .forEach { row ->
                    val clubId = row.value1()
                    val count = row.value2()
                    dsl.update(CLUBS)
                        .set(CLUBS.MEMBER_COUNT, org.jooq.impl.DSL.greatest(CLUBS.MEMBER_COUNT.minus(count), org.jooq.impl.DSL.`val`(0)))
                        .where(CLUBS.ID.eq(clubId))
                        .execute()
                }
            log.info("Expired $fullyExpired memberships after grace period")
        }
    }
}
