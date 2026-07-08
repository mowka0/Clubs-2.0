package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipStatus
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
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
 *   - строка есть и она "жива" (active / frozen / expired) → баг вызывающего кода; бросаем
 *     IllegalState (вызывающий код обязан проверить `findActiveByUserAndClub` ДО вызова и вернуть
 *     правильную HTTP-ошибку).
 *   - строка есть и она "мертва" (cancelled) → реактивируем в целевой статус.
 *
 * Контракт: вызывающий код гарантирует, что клуб уже провалидирован (тип, лимит участников, ownership).
 */
@Component
class MembershipActivator(
    private val membershipRepository: MembershipRepository,
    private val eventPublisher: ApplicationEventPublisher
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
        // Бесплатная активация = доступ открыт сразу; frozen откроется позже через
        // AccessGateService.markDuesPaid (там своя публикация). Слушает чат-«дверь» AFTER_COMMIT.
        // Frozen-активация = новый должник (ждёт первого взноса): строгий режим чата мьютит его,
        // если он уже сидит в привязанном чате (чат мог существовать до клуба).
        if (!frozen) eventPublisher.publishEvent(MembershipAccessOpenedEvent(clubId, userId, wasAccessClosed = true))
        else eventPublisher.publishEvent(MembershipAccessClosedEvent(clubId, userId))
        return membership
    }

    // "Жива" = membership всё ещё принадлежит клубу, поэтому её НЕЛЬЗЯ молча реактивировать как
    // свежее вступление. `frozen` (ждёт первого взноса) и `expired` (просрочил продление — должник,
    // путь назад через оплату, не через повторное вступление) тоже считаются принадлежащими; иначе
    // повторный join стёр бы жизненный цикл должника. Матрица: docs/modules/membership-lifecycle.md.
    private fun MembershipStatus.isAlive(): Boolean =
        this == MembershipStatus.active ||
            this == MembershipStatus.frozen ||
            this == MembershipStatus.expired
}
