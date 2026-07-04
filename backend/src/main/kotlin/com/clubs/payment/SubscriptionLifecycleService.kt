package com.clubs.payment

import com.clubs.membership.ExpiringSubscriptionNotification
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class SubscriptionLifecycleService(
    private val membershipRepository: MembershipRepository
) {

    private val log = LoggerFactory.getLogger(SubscriptionLifecycleService::class.java)

    @Transactional(readOnly = true)
    fun findExpiringWithin(now: OffsetDateTime, threshold: OffsetDateTime): List<ExpiringSubscriptionNotification> =
        membershipRepository.findExpiringWithin(now, threshold)

    @Transactional(readOnly = true)
    fun findActiveExpired(now: OffsetDateTime): List<ExpiringSubscriptionNotification> =
        membershipRepository.findActiveExpired(now)

    /**
     * Автоистечение по honor-system (de-Stars Slice 2): в одной короткой транзакции каждое `active`
     * платное membership, у которого окно доступа (subscription_expires_at) истекло, переходит в
     * `frozen` («ждёт оплаты») — участник остаётся в клубе, но теряет доступ к контенту, пока организатор
     * не подтвердит следующий платёж взноса. Free-membership (без истечения) не затрагиваются. Внешний
     * IO (уведомления) должен выполняться вне этого метода. Жёсткое отсечение, без grace-периода —
     * по решению PO (de-Stars).
     */
    @Transactional
    fun processExpiry(now: OffsetDateTime) {
        val expired = membershipRepository.expireOverdueAccess(now)
        if (expired > 0) log.info("Auto-expired {} overdue memberships to frozen (awaiting dues)", expired)
    }
}
