package com.clubs.membership

import java.time.OffsetDateTime
import java.util.UUID

interface MembershipRepository {

    // Поиск
    fun findActiveByUserAndClub(userId: UUID, clubId: UUID): Membership?
    fun findByUserAndClub(userId: UUID, clubId: UUID): Membership?
    fun findById(id: UUID): Membership?
    fun findByUserId(userId: UUID): List<Membership>
    fun findClubMembersWithUserInfo(clubId: UUID, includeFrozen: Boolean = false): List<ClubMemberInfo>
    fun findUserClubsWithReputation(userId: UUID): List<UserClubReputationInfo>
    /** Участники в статусе `frozen` по всем активным клубам, которыми владеет [ownerId] — кросс-клубовая
     *  лента «Ждут оплаты». */
    fun findFrozenMembersByOwner(ownerId: UUID): List<OrganizerDuesMember>
    /** Участники в статусе `frozen`, заявившие об оплате взноса (claim pending), по всем клубам [ownerId] —
     *  питает точку-индикатор на «Мои клубы», чтобы оплативший и ждущий участник был замечен без захода в таб. */
    fun countClaimedFrozenByOwner(ownerId: UUID): Int
    fun findExpiryRefByUserAndClub(userId: UUID, clubId: UUID): MembershipExpiryRef?

    // Предикаты / счётчики
    fun isMember(userId: UUID, clubId: UUID): Boolean
    fun isActiveMemberInActiveClub(userId: UUID, clubId: UUID): Boolean
    fun countActiveByClubId(clubId: UUID): Int
    /** Активные участники без организатора, по всем [clubIds] — карточка доверия организатора «доверяют N участников». */
    fun countActiveNonOrganizerMembersInClubs(clubIds: Collection<UUID>): Int

    // Мутации
    fun create(userId: UUID, clubId: UUID): Membership
    fun createFrozen(userId: UUID, clubId: UUID): Membership
    fun createOrganizer(userId: UUID, clubId: UUID): Membership
    fun reactivateFree(membershipId: UUID): Membership
    fun reactivateFrozen(membershipId: UUID): Membership
    fun cancel(membershipId: UUID)
    /** Кик организатором: отменяет membership И очищает окно оплаты, чтобы участник немедленно
     *  терял доступ (без grace-периода до expiry, как при обычном выходе). Возвращает число затронутых
     *  строк (0 = уже удалён / гонка). */
    fun remove(membershipId: UUID): Int
    fun activateSubscription(userId: UUID, clubId: UUID, expiresAt: OffsetDateTime): UUID
    fun renewSubscription(membershipId: UUID, newExpiresAt: OffsetDateTime)

    // Access gate (de-Stars, слой 2) — контролируемая организатором заморозка + учёт взносов. Каждый метод
    // возвращает число затронутых строк, чтобы сервис мог защитить оптимистичный переход статуса (0 = гонка проиграна → 409).
    fun freezeAccess(membershipId: UUID): Int
    fun unfreezeAccess(membershipId: UUID): Int
    fun markDuesPaid(membershipId: UUID, markedBy: UUID, accessUntil: OffsetDateTime): Int
    fun unmarkDues(membershipId: UUID): Int

    // Заявление участника об оплате взноса (de-Stars): замороженный участник заявляет, что оплатил
    // (method "sbp"/"cash"; proofUrl = скриншот для sbp, null для cash). Защищено условием status=frozen
    // (0 строк → больше не frozen).
    fun claimDues(membershipId: UUID, method: String, proofUrl: String?): Int

    // Member admin profile (S1) — организатор вручную задаёт конец окна доступа / приватную заметку.
    /** Даёт доступ до произвольной даты (status→active, снимает frozen). Ручное переопределение,
     *  не подтверждение оплаты взноса. */
    fun setAccessUntil(membershipId: UUID, accessUntil: OffsetDateTime): Int
    /** Задаёт приватную заметку организатора (null = очищает её). */
    fun updateOrganizerNote(membershipId: UUID, note: String?): Int

    // Жизненный цикл / планировщик (honor-system окно доступа)
    fun findExpiringWithin(now: OffsetDateTime, threshold: OffsetDateTime): List<ExpiringSubscriptionNotification>
    fun findActiveExpired(now: OffsetDateTime): List<ExpiringSubscriptionNotification>
    /** Переводит в `frozen` каждый membership в статусе `active`, чьё окно доступа (subscription_expires_at) истекло. */
    fun expireOverdueAccess(now: OffsetDateTime): Int
    /** Число скоро истекающих участников по всем [clubIds] — питает red-dot бейдж на «Управление». */
    fun countExpiringSoonByClubs(clubIds: Collection<UUID>, now: OffsetDateTime, threshold: OffsetDateTime): Int
    /**
     * Число `frozen`-участников, ЗАЯВИВШИХ об оплате (dues_claimed_at IS NOT NULL), по всем [clubIds] —
     * тоже зажигает red-dot. Именно claimed: только такой frozen требует действия организатора
     * («Взнос получен»). Просто frozen (ручная пауза или автоистечение окна) точку не зажигает —
     * мяч на стороне участника, организатору делать нечего. Зеркалит countClaimedFrozenByOwner
     * (бейдж таб-бара) — оба сигнала считают одно и то же множество.
     */
    fun countClaimedFrozenByClubs(clubIds: Collection<UUID>): Int

    // Бот/уведомления
    fun findMemberTelegramIds(clubId: UUID): List<Long>
}
