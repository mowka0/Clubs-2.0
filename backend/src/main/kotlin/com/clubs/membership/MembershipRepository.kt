package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipRole
import java.time.OffsetDateTime
import java.util.UUID

interface MembershipRepository {

    // Поиск
    fun findActiveByUserAndClub(userId: UUID, clubId: UUID): Membership?
    fun findByUserAndClub(userId: UUID, clubId: UUID): Membership?
    fun findById(id: UUID): Membership?
    fun findByUserId(userId: UUID): List<Membership>
    fun findClubMembersWithUserInfo(clubId: UUID, includeWithoutAccess: Boolean = false): List<ClubMemberInfo>
    fun findUserClubsWithReputation(userId: UUID): List<UserClubReputationInfo>
    /** Кросс-клубовая лента «Ждут оплаты» по всем активным managed-клубам [managerId] (владение ИЛИ
     *  активный со-орг — co-organizers У-5): участники без доступа — `frozen` (ждут первого взноса)
     *  и `expired` (просрочили продление) — плюс `active` С claim (раннее продление: заявил оплату
     *  до истечения, ждёт «Взнос получен»). */
    fun findAwaitingDuesMembersByManager(managerId: UUID): List<OrganizerDuesMember>
    /** Участники, заявившие об оплате взноса (claim pending) — `frozen`/`expired`/`active` (раннее
     *  продление) — по всем managed-клубам [managerId] (У-5); питает точку-индикатор на «Мои клубы»,
     *  чтобы оплативший и ждущий участник был замечен без захода в таб. */
    fun countClaimedAwaitingDuesByManager(managerId: UUID): Int
    fun findExpiryRefByUserAndClub(userId: UUID, clubId: UUID): MembershipExpiryRef?

    // Предикаты / счётчики
    fun isMember(userId: UUID, clubId: UUID): Boolean
    /** Число со-организаторов клуба (role=co_organizer, membership не cancelled) — проверка лимита У-3. */
    fun countCoOrganizers(clubId: UUID): Int
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

    // Access gate (de-Stars, слой 2) — контролируемая организатором заморозка + учёт взносов. Каждый метод
    // возвращает число затронутых строк, чтобы сервис мог защитить оптимистичный переход статуса (0 = гонка проиграна → 409).
    fun freezeAccess(membershipId: UUID): Int
    fun unfreezeAccess(membershipId: UUID): Int
    fun markDuesPaid(membershipId: UUID, markedBy: UUID, accessUntil: OffsetDateTime): Int
    fun unmarkDues(membershipId: UUID): Int

    // Заявление участника об оплате взноса (de-Stars): frozen (первый взнос), expired (просрочка
    // продления) или active (раннее продление — окно T-3 валидирует AccessGateService) заявляет,
    // что оплатил (method "sbp"/"cash"; proofUrl = скриншот для sbp, null для cash). Защищено
    // условием status IN (frozen, expired, active) (0 строк → членство ушло в cancelled).
    fun claimDues(membershipId: UUID, method: String, proofUrl: String?): Int

    // Member admin profile (S1) — организатор вручную задаёт конец окна доступа / приватную заметку.
    /** Даёт доступ до произвольной даты (status→active, снимает frozen). Ручное переопределение,
     *  не подтверждение оплаты взноса. */
    fun setAccessUntil(membershipId: UUID, accessUntil: OffsetDateTime): Int
    /** Задаёт приватную заметку организатора (null = очищает её). */
    fun updateOrganizerNote(membershipId: UUID, note: String?): Int

    // Смена роли (co-organizers): защищённый переход `WHERE role = expected` + rows-affected —
    // 0 строк = конкурентная смена роли (сервис -> 409), тот же паттерн, что org-toggle складчины.
    fun updateRole(membershipId: UUID, expectedRole: MembershipRole, newRole: MembershipRole): Int

    /** Транзакционный advisory-lock смен ролей клуба (снимается сам на commit/rollback):
     *  сериализует промоуты, чтобы конкурентные назначения не пробили лимит со-оргов (TOCTOU —
     *  count и UPDATE иначе не атомарны). Брать ДО проверки countCoOrganizers. */
    fun lockRoleChanges(clubId: UUID)

    // Жизненный цикл / планировщик (honor-system окно доступа)
    fun findExpiringWithin(now: OffsetDateTime, threshold: OffsetDateTime): List<ExpiringSubscriptionNotification>
    fun findActiveExpired(now: OffsetDateTime): List<ExpiringSubscriptionNotification>
    /** Переводит в `expired` каждый membership в статусе `active`, чьё окно доступа (subscription_expires_at)
     *  истекло. Возвращает затронутые (clubId, userId) — по ним строгий режим чата мьютит должников. */
    fun expireOverdueAccess(now: OffsetDateTime): List<MembershipAccessRef>
    /** Cancelled-membership'ы, чьё оплаченное окно истекло в интервале (from; to] — «человек вышел из клуба,
     *  оплаченный период закончился». По ним строгий режим чата банит (слайс 5). */
    fun findCancelledExpiredBetween(from: OffsetDateTime, to: OffsetDateTime): List<MembershipAccessRef>

    // Бот/уведомления
    fun findMemberTelegramIds(clubId: UUID): List<Long>
    /** telegram_id должников клуба (frozen/expired) — backfill/снятие мьютов строгого режима (слайс 5). */
    fun findDebtorTelegramIds(clubId: UUID): List<Long>
}
