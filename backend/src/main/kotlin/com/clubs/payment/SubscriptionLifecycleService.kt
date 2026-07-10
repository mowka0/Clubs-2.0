package com.clubs.payment

import com.clubs.membership.ExpiringSubscriptionNotification
import com.clubs.membership.MembershipAccessClosedEvent
import com.clubs.membership.MembershipAccessRevokedEvent
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class SubscriptionLifecycleService(
    private val membershipRepository: MembershipRepository,
    private val eventPublisher: ApplicationEventPublisher
) {

    private val log = LoggerFactory.getLogger(SubscriptionLifecycleService::class.java)

    @Transactional(readOnly = true)
    fun findExpiringWithin(now: OffsetDateTime, threshold: OffsetDateTime): List<ExpiringSubscriptionNotification> =
        membershipRepository.findExpiringWithin(now, threshold)

    @Transactional(readOnly = true)
    fun findActiveExpired(now: OffsetDateTime): List<ExpiringSubscriptionNotification> =
        membershipRepository.findActiveExpired(now)

    /**
     * Автоистечение по honor-system (de-Stars Slice 2, статусная модель 2026-07-06): в одной короткой
     * транзакции каждое `active` платное membership, у которого окно доступа (subscription_expires_at)
     * истекло, переходит в `expired` («просрочено продление») — участник остаётся в клубе должником,
     * но теряет доступ к контенту, пока организатор не подтвердит следующий платёж взноса.
     * `frozen` зарезервирован за новыми участниками, ждущими ПЕРВОГО взноса. Free-membership (без
     * истечения) не затрагиваются. Внешний IO (уведомления) должен выполняться вне этого метода.
     *
     * Строгий режим чата (слайс 5) слушает AFTER_COMMIT:
     *  - по каждому переходу active→expired публикуется «доступ закрылся» → mute должника;
     *  - по каждой cancelled-строке, чьё оплаченное окно истекло в catch-up-окне, — «путь в клуб
     *    закрыт» → ban ушедшего (человек вышел из платного клуба и досидел оплаченный период).
     */
    @Transactional
    fun processExpiry(now: OffsetDateTime) {
        val expired = membershipRepository.expireOverdueAccess(now)
        if (expired.isNotEmpty()) log.info("Auto-expired {} overdue memberships to expired (awaiting renewal dues)", expired.size)
        expired.forEach { eventPublisher.publishEvent(MembershipAccessClosedEvent(it.clubId, it.userId)) }

        // Повторная публикация внутри окна безвредна (ban идемпотентен, вне строгого режима — no-op);
        // строки старше окна не трогаются — осознанное ограничение (спека § Слайс 5).
        val cancelledExpired = membershipRepository.findCancelledExpiredBetween(now.minusDays(CANCELLED_BAN_CATCHUP_DAYS), now)
        cancelledExpired.forEach { eventPublisher.publishEvent(MembershipAccessRevokedEvent(it.clubId, it.userId)) }
    }

    companion object {
        // Catch-up-окно (в днях) для банов по истёкшим cancelled-подпискам: покрывает пропуски
        // ежедневного тика (ре-деплой, простой), не создавая вечного пересканирования всей истории.
        private const val CANCELLED_BAN_CATCHUP_DAYS = 3L
    }
}
