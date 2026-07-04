package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Инкапсулирует жизненный цикл "создать-или-реактивировать" для membership. Два целевых состояния:
 *
 *  - [activateFree]  → `active`  (бесплатный клуб: мгновенный доступ).
 *  - [activateFrozen] → `frozen` (платный клуб, de-Stars Slice 2: участник принадлежит клубу, но доступ
 *    закрыт, пока организатор не подтвердит офлайн-взнос — см. [AccessGateService]).
 *
 * Обе ветки используют одну и ту же логику, чтобы решение create/reactivate жило в одном месте:
 *   - строки нет вообще → INSERT новой membership в целевом статусе.
 *   - строка есть и она "жива" (active / frozen / grace_period) → баг вызывающего кода; бросаем
 *     IllegalState (вызывающий код обязан проверить `findActiveByUserAndClub` ДО вызова и вернуть
 *     правильную HTTP-ошибку).
 *   - строка есть и она "мертва" (cancelled / expired) → реактивируем в целевой статус.
 *
 * Контракт: вызывающий код гарантирует, что клуб уже провалидирован (тип, лимит участников, ownership).
 */
@Component
class MembershipActivator(
    private val membershipRepository: MembershipRepository
) {

    private val log = LoggerFactory.getLogger(MembershipActivator::class.java)

    /** @throws IllegalStateException если живая membership уже существует (вызывающий код обязан проверить заранее). */
    fun activateFree(userId: UUID, clubId: UUID): Membership = activate(userId, clubId, frozen = false)

    /** @throws IllegalStateException если живая membership уже существует (вызывающий код обязан проверить заранее). */
    fun activateFrozen(userId: UUID, clubId: UUID): Membership = activate(userId, clubId, frozen = true)

    private fun activate(userId: UUID, clubId: UUID, frozen: Boolean): Membership {
        val existing = membershipRepository.findByUserAndClub(userId, clubId)
        val membership = when {
            existing == null ->
                if (frozen) membershipRepository.createFrozen(userId, clubId)
                else membershipRepository.create(userId, clubId)
            existing.status.isAlive() ->
                // Эшелонированная защита — вызывающий код уже должен был отклонить этот случай.
                throw IllegalStateException("Active membership already exists: id=${existing.id}")
            else ->
                if (frozen) membershipRepository.reactivateFrozen(existing.id)
                else membershipRepository.reactivateFree(existing.id)
        }
        log.info(
            "Membership {} ({}): userId={} clubId={}",
            if (existing == null) "created" else "reactivated",
            if (frozen) "frozen" else "active", userId, clubId
        )
        return membership
    }

    // "Жива" = membership всё ещё принадлежит клубу, поэтому её НЕЛЬЗЯ молча реактивировать как
    // свежее вступление. `frozen` (закрыта организатором, ожидает офлайн-взнос) тоже считается принадлежащей.
    private fun MembershipStatus.isAlive(): Boolean =
        this == MembershipStatus.active ||
            this == MembershipStatus.frozen ||
            this == MembershipStatus.grace_period
}
