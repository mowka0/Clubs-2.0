package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipEvent
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.records.MembershipsRecord
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.USERS
import com.clubs.generated.jooq.tables.references.USER_CLUB_REPUTATION
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqMembershipRepository(
    private val dsl: DSLContext,
    private val mapper: MembershipMapper,
    // Осознанно: каждая мутация статуса membership проходит через ЭТОТ репозиторий, поэтому запись
    // append-only истории именно здесь (единая узкая точка, та же транзакция) гарантирует, что лог
    // не пропустит переход молча. Маппинг смен статуса → {joined,left,rejoined,expired} живёт здесь
    // намеренно — не поднимать его в сервисный слой (это размажет логику и снова откроет дыру).
    private val history: MembershipHistoryRepository
) : MembershipRepository {

    // Поиск «текущей принадлежности» для управления/выхода/идемпотентности вступления: `frozen`
    // (ждёт первого взноса) и `expired` (просрочил продление) всё ещё числятся участниками и должны
    // находиться этим методом, чтобы их можно было допустить/продлить, дать выйти или сказать
    // «уже участник» при повторной попытке вступления. Намеренно шире, чем
    // MembershipAccess.hasAccess (доступ к контенту). Матрица: docs/modules/membership-lifecycle.md.
    override fun findActiveByUserAndClub(userId: UUID, clubId: UUID): Membership? =
        dsl.selectFrom(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.USER_ID.eq(userId)
                    .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                    .and(
                        MEMBERSHIPS.STATUS.`in`(
                            MembershipStatus.active,
                            MembershipStatus.frozen,
                            MembershipStatus.expired
                        )
                    )
            )
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findByUserAndClub(userId: UUID, clubId: UUID): Membership? =
        dsl.selectFrom(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.USER_ID.eq(userId)
                    .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
            )
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findById(id: UUID): Membership? =
        dsl.selectFrom(MEMBERSHIPS)
            .where(MEMBERSHIPS.ID.eq(id))
            .fetchOne()
            ?.let(mapper::toDomain)

    // Список «Мои клубы»: клубы, где юзер сейчас числится участником. `frozen` и `expired` включены —
    // участник без доступа всё равно должен видеть клуб (чтобы узнать, что доступ закрыт / оплатить
    // взнос). Убрана старая ветка «cancelled, но ещё оплачено» (de-Stars: subscription_expires_at
    // больше не драйвер).
    override fun findByUserId(userId: UUID): List<Membership> {
        return dsl.select(*MEMBERSHIPS.fields())
            .from(MEMBERSHIPS)
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .where(
                MEMBERSHIPS.USER_ID.eq(userId)
                    .and(CLUBS.IS_ACTIVE.eq(true))
                    .and(
                        MEMBERSHIPS.STATUS.`in`(
                            MembershipStatus.active,
                            MembershipStatus.frozen,
                            MembershipStatus.expired
                        )
                    )
            )
            .fetchInto(MembershipsRecord::class.java)
            .map(mapper::toDomain)
    }

    override fun findClubMembersWithUserInfo(clubId: UUID, includeWithoutAccess: Boolean): List<ClubMemberInfo> {
        // Дашборду организатора нужны и участники без доступа: `frozen` (ждут первого взноса) и
        // `expired` (просрочили продление) — они показаны в бакетах «Оплата вступления» / «Доступ истёк».
        // Ростер для обычного участника на ClubPage видит только `active`. includeWithoutAccess задаёт
        // вызывающий код (MemberService) исходя из того, является ли зритель организатором.
        val statusCondition = if (includeWithoutAccess) {
            MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.frozen, MembershipStatus.expired)
        } else {
            MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
        }
        val outcomeCount = DSL.coalesce(USER_CLUB_REPUTATION.OUTCOME_COUNT, DSL.`val`(0))
        // Порядок здесь — только стабильная база: MemberService пересортирует по отображаемому Trust
        // (вычисляется при чтении из ledger), что SQL выразить не может. outcome_count определяет
        // порог для отметки «Новичок».
        return dsl.select(
            MEMBERSHIPS.USER_ID,
            MEMBERSHIPS.ROLE,
            MEMBERSHIPS.STATUS,
            MEMBERSHIPS.JOINED_AT,
            MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT,
            MEMBERSHIPS.DUES_CLAIMED_AT,
            MEMBERSHIPS.DUES_CLAIM_METHOD,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            USERS.AVATAR_URL,
            USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT,
            USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS,
            outcomeCount.`as`("outcome_count")
        )
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .leftJoin(USER_CLUB_REPUTATION).on(
                USER_CLUB_REPUTATION.USER_ID.eq(MEMBERSHIPS.USER_ID)
                    .and(USER_CLUB_REPUTATION.CLUB_ID.eq(clubId))
            )
            .where(MEMBERSHIPS.CLUB_ID.eq(clubId).and(statusCondition))
            .orderBy(MEMBERSHIPS.JOINED_AT.desc())
            .fetch { r ->
                ClubMemberInfo(
                    userId = r.get(MEMBERSHIPS.USER_ID)!!,
                    firstName = r.get(USERS.FIRST_NAME),
                    lastName = r.get(USERS.LAST_NAME),
                    avatarUrl = r.get(USERS.AVATAR_URL),
                    role = r.get(MEMBERSHIPS.ROLE) ?: MembershipRole.member,
                    joinedAt = r.get(MEMBERSHIPS.JOINED_AT)!!,
                    promiseFulfillmentPct = r.get(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT),
                    totalConfirmations = r.get(USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS),
                    outcomeCount = r.get("outcome_count", Int::class.java) ?: 0,
                    status = r.get(MEMBERSHIPS.STATUS) ?: MembershipStatus.active,
                    subscriptionExpiresAt = r.get(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT),
                    duesClaimedAt = r.get(MEMBERSHIPS.DUES_CLAIMED_AT),
                    duesClaimMethod = r.get(MEMBERSHIPS.DUES_CLAIM_METHOD)
                )
            }
    }

    override fun findUserClubsWithReputation(userId: UUID): List<UserClubReputationInfo> {
        val outcomeCount = DSL.coalesce(USER_CLUB_REPUTATION.OUTCOME_COUNT, DSL.`val`(0))
        // «Active» в профиле = участник всё ещё числится И клуб жив. `frozen` и `expired` тоже считаются
        // принадлежностью (доступ закрыт до оплаты взноса, но клуб всё ещё «его»). Всё остальное, что
        // проходит фильтр ниже (вышедшее членство) — это «История»: оно появляется только потому, что
        // живёт след репутации (outcome_count > 0). P1b: глобальный агрегат теперь all-history, поэтому
        // этот запрос больше не отбрасывает клубы, из которых юзер вышел (закрывает дыру active-only A).
        // De-Stars: убрана старая ветка «cancelled, но ещё оплачено».
        val activeCondition = MEMBERSHIPS.STATUS.`in`(
            MembershipStatus.active,
            MembershipStatus.frozen,
            MembershipStatus.expired
        ).and(CLUBS.IS_ACTIVE.eq(true))
        val activeField = DSL.field(activeCondition).`as`("active")
        return dsl.select(
            CLUBS.ID,
            CLUBS.NAME,
            CLUBS.AVATAR_URL,
            CLUBS.CATEGORY,
            MEMBERSHIPS.ROLE,
            MEMBERSHIPS.JOINED_AT,
            USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT,
            USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS,
            USER_CLUB_REPUTATION.TOTAL_ATTENDANCES,
            USER_CLUB_REPUTATION.SPONTANEITY_COUNT,
            outcomeCount.`as`("outcome_count"),
            activeField
        )
            .from(MEMBERSHIPS)
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .leftJoin(USER_CLUB_REPUTATION).on(
                USER_CLUB_REPUTATION.USER_ID.eq(MEMBERSHIPS.USER_ID)
                    .and(USER_CLUB_REPUTATION.CLUB_ID.eq(CLUBS.ID))
            )
            .where(
                MEMBERSHIPS.USER_ID.eq(userId)
                    .and(activeCondition.or(outcomeCount.gt(0)))
            )
            .orderBy(MEMBERSHIPS.JOINED_AT.desc().nullsLast())
            .fetch { r ->
                UserClubReputationInfo(
                    clubId = r.get(CLUBS.ID)!!,
                    clubName = r.get(CLUBS.NAME)!!,
                    clubAvatarUrl = r.get(CLUBS.AVATAR_URL),
                    category = r.get(CLUBS.CATEGORY)!!,
                    role = r.get(MEMBERSHIPS.ROLE) ?: MembershipRole.member,
                    joinedAt = r.get(MEMBERSHIPS.JOINED_AT),
                    promiseFulfillmentPct = r.get(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT),
                    totalConfirmations = r.get(USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS),
                    totalAttendances = r.get(USER_CLUB_REPUTATION.TOTAL_ATTENDANCES),
                    spontaneityCount = r.get(USER_CLUB_REPUTATION.SPONTANEITY_COUNT),
                    outcomeCount = r.get("outcome_count", Int::class.java) ?: 0,
                    active = r.get(activeField) ?: false
                )
            }
    }

    override fun findExpiryRefByUserAndClub(userId: UUID, clubId: UUID): MembershipExpiryRef? =
        dsl.select(MEMBERSHIPS.ID, MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT)
            .from(MEMBERSHIPS)
            .where(MEMBERSHIPS.USER_ID.eq(userId).and(MEMBERSHIPS.CLUB_ID.eq(clubId)))
            .fetchOne { record ->
                MembershipExpiryRef(
                    id = record.get(MEMBERSHIPS.ID)!!,
                    subscriptionExpiresAt = record.get(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT)
                )
            }

    override fun isMember(userId: UUID, clubId: UUID): Boolean {
        return dsl.fetchExists(
            dsl.selectOne()
                .from(MEMBERSHIPS)
                .where(
                    MEMBERSHIPS.USER_ID.eq(userId)
                        .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                        .and(MembershipAccess.hasAccess())
                )
        )
    }

    override fun isActiveMemberInActiveClub(userId: UUID, clubId: UUID): Boolean {
        return dsl.fetchExists(
            dsl.selectOne()
                .from(MEMBERSHIPS)
                .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
                .where(
                    MEMBERSHIPS.USER_ID.eq(userId)
                        .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                        .and(MembershipAccess.hasAccess())
                        .and(CLUBS.IS_ACTIVE.eq(true))
                )
        )
    }

    // Число занятых слотов для проверки лимита участников. `frozen` тоже считается: вступление в
    // платный клуб сразу переводит участника в `frozen` (заблокирован до оплаты взноса), и он всё равно
    // занимает слот — иначе N человек могли бы набиться в клуб на 1 место, все во frozen, обойдя лимит.
    // `expired` (должник по продлению) аналогично остаётся участником и держит слот.
    override fun countActiveByClubId(clubId: UUID): Int =
        dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.frozen, MembershipStatus.expired))
            )
            .fetchOne(0, Int::class.java) ?: 0

    override fun countActiveNonOrganizerMembersInClubs(clubIds: Collection<UUID>): Int {
        if (clubIds.isEmpty()) return 0
        // active (= доступ есть прямо сейчас, реальный social proof) + role != organizer (владельца не считаем).
        return dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.`in`(clubIds)
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
                    .and(MEMBERSHIPS.ROLE.ne(MembershipRole.organizer))
            )
            .fetchOne(0, Int::class.java) ?: 0
    }

    // Вступление в бесплатный клуб → `active`. У бесплатного членства НЕТ подписки → subscription_expires_at
    // остаётся NULL. (Простановка 30-дневного срока здесь была исторической багой, из-за которой каждый
    // бесплатный участник выглядел как «cancelled, но ещё в оплаченном периоде» платный подписчик.)
    // Вступление в платный клуб — createFrozen ниже.
    override fun create(userId: UUID, clubId: UUID): Membership =
        insertMembership(userId, clubId, MembershipStatus.active)

    // Вступление в платный клуб → `frozen` (de-Stars Slice 2): участник числится и занимает слот, но
    // не имеет доступа к контенту, пока организатор не подтвердит офлайн-взнос (AccessGateService.markDuesPaid).
    override fun createFrozen(userId: UUID, clubId: UUID): Membership =
        insertMembership(userId, clubId, MembershipStatus.frozen)

    private fun insertMembership(userId: UUID, clubId: UUID, status: MembershipStatus): Membership {
        val now = OffsetDateTime.now()
        val record = dsl.insertInto(MEMBERSHIPS)
            .set(MEMBERSHIPS.ID, UUID.randomUUID())
            .set(MEMBERSHIPS.USER_ID, userId)
            .set(MEMBERSHIPS.CLUB_ID, clubId)
            .set(MEMBERSHIPS.STATUS, status)
            .set(MEMBERSHIPS.ROLE, MembershipRole.member)
            .set(MEMBERSHIPS.JOINED_AT, now)
            .set(MEMBERSHIPS.ACCESS_FROZEN_AT, if (status == MembershipStatus.frozen) now else null)
            .returning()
            .fetchOne()!!
        history.record(userId, clubId, MembershipEvent.joined, now)
        return mapper.toDomain(record)
    }

    override fun createOrganizer(userId: UUID, clubId: UUID): Membership {
        // НЕ логируется в membership_history: организатор — владелец, структурно всегда присутствует,
        // никогда не вступает и не оттекает в смысле retention (владелец не может выйти). Если лог
        // ведётся только по обычным участникам, будущему читателю retention-данных не нужен фильтр по роли.
        val record = dsl.insertInto(MEMBERSHIPS)
            .set(MEMBERSHIPS.ID, UUID.randomUUID())
            .set(MEMBERSHIPS.USER_ID, userId)
            .set(MEMBERSHIPS.CLUB_ID, clubId)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .set(MEMBERSHIPS.ROLE, MembershipRole.organizer)
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    /**
     * Возвращает к жизни ранее мёртвую (cancelled) строку membership. UNIQUE(user_id, club_id)
     * означает, что нельзя INSERT-ить свежую строку, когда одна уже существует, — реактивация единственный
     * путь. Сбрасывает поля жизненного цикла так, что вступление неотличимо от совершенно нового.
     * [reactivateFree] → `active` (бесплатный клуб, без биллинга); [reactivateFrozen] → `frozen`
     * (платный клуб, заблокирован до подтверждения взноса организатором).
     */
    override fun reactivateFree(membershipId: UUID): Membership =
        reactivate(membershipId, MembershipStatus.active)

    override fun reactivateFrozen(membershipId: UUID): Membership =
        reactivate(membershipId, MembershipStatus.frozen)

    private fun reactivate(membershipId: UUID, status: MembershipStatus): Membership {
        val now = OffsetDateTime.now()
        val record = dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, status)
            .set(MEMBERSHIPS.JOINED_AT, now)
            .setNull(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT)
            // Свежее вступление: очистить устаревшие метки взноса от предыдущего жизненного цикла;
            // проставить «заморожен с» при переходе во frozen.
            .setNull(MEMBERSHIPS.DUES_MARKED_PAID_AT)
            .setNull(MEMBERSHIPS.DUES_MARKED_BY)
            // Также сбросить прежний claim взноса со стороны участника — иначе повторно вступивший,
            // который сделал claim до отказа, снова окажется в «Оплата на проверке» вместо свежего
            // «Оплатить взнос».
            .setNull(MEMBERSHIPS.DUES_CLAIMED_AT)
            .setNull(MEMBERSHIPS.DUES_CLAIM_METHOD)
            .setNull(MEMBERSHIPS.DUES_PROOF_URL)
            .set(MEMBERSHIPS.ACCESS_FROZEN_AT, if (status == MembershipStatus.frozen) now else null)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId))
            .returning()
            .fetchOne()!!
        history.record(record.userId, record.clubId, MembershipEvent.rejoined, now)
        return mapper.toDomain(record)
    }

    override fun cancel(membershipId: UUID) {
        val now = OffsetDateTime.now()
        val row = dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.cancelled)
            // У мёртвого членства не должно быть живого claim'а взноса — очистка здесь сразу закрывает
            // «Отказать и вернуть» (и любой выход), чтобы устаревший claim не завис и не перешёл на
            // повторное вступление.
            .setNull(MEMBERSHIPS.DUES_CLAIMED_AT)
            .setNull(MEMBERSHIPS.DUES_CLAIM_METHOD)
            .setNull(MEMBERSHIPS.DUES_PROOF_URL)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId))
            .returningResult(MEMBERSHIPS.USER_ID, MEMBERSHIPS.CLUB_ID)
            .fetchOne()
        if (row != null) {
            history.record(row.get(MEMBERSHIPS.USER_ID)!!, row.get(MEMBERSHIPS.CLUB_ID)!!, MembershipEvent.left, now)
        }
    }

    override fun remove(membershipId: UUID): Int {
        val now = OffsetDateTime.now()
        // Кик: cancel + обнуление оплаченного окна, чтобы фронтендный grace-период («cancelled, но оплачено
        // до X») никогда не применялся — исключённый участник полностью выходит, в отличие от того, кто
        // вышел добровольно и сохраняет доступ до истечения срока.
        val row = dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.cancelled)
            .set(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT, null as OffsetDateTime?)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId).and(MEMBERSHIPS.STATUS.ne(MembershipStatus.cancelled)))
            .returningResult(MEMBERSHIPS.USER_ID, MEMBERSHIPS.CLUB_ID)
            .fetchOne()
        if (row != null) {
            history.record(row.get(MEMBERSHIPS.USER_ID)!!, row.get(MEMBERSHIPS.CLUB_ID)!!, MembershipEvent.left, now)
            return 1
        }
        return 0
    }

    // Мутации access-gate (de-Stars, Slice 2). Намеренно НЕ пишутся в membership_history:
    // заморозка/разморозка — временная приостановка доступа, а не событие оттока join/leave/expire
    // (для неё нет MembershipEvent). Замороженный участник всё равно числится; когда он реально выходит,
    // cancel() логирует `left` как обычно — так лог retention/churn остаётся точным и без этих событий.
    override fun freezeAccess(membershipId: UUID): Int {
        val now = OffsetDateTime.now()
        return dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.frozen)
            .set(MEMBERSHIPS.ACCESS_FROZEN_AT, now)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId).and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active)))
            .execute()
    }

    override fun unfreezeAccess(membershipId: UUID): Int {
        val now = OffsetDateTime.now()
        return dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .setNull(MEMBERSHIPS.ACCESS_FROZEN_AT)
            // Открытие доступа заодно закрывает любой ожидающий claim взноса.
            .setNull(MEMBERSHIPS.DUES_CLAIMED_AT)
            .setNull(MEMBERSHIPS.DUES_CLAIM_METHOD)
            .setNull(MEMBERSHIPS.DUES_PROOF_URL)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId).and(MEMBERSHIPS.STATUS.eq(MembershipStatus.frozen)))
            .execute()
    }

    override fun claimDues(membershipId: UUID, method: String, proofUrl: String?): Int {
        val now = OffsetDateTime.now()
        return dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.DUES_CLAIMED_AT, now)
            .set(MEMBERSHIPS.DUES_CLAIM_METHOD, method)
            .set(MEMBERSHIPS.DUES_PROOF_URL, proofUrl)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            // Claim делает участник без доступа: frozen (ждёт первого взноса) или expired (просрочил
            // продление); 0 строк = доступ уже открыт (например, организатор только что впустил).
            .where(
                MEMBERSHIPS.ID.eq(membershipId)
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.frozen, MembershipStatus.expired))
            )
            .execute()
    }

    // «Взнос получен»: фиксирует офлайн-оплату взноса, открывает доступ (status→active из
    // frozen/expired) И одним атомарным шагом устанавливает конец окна доступа
    // (subscription_expires_at = accessUntil). Позже шедулер автоматически переводит просроченный
    // доступ в expired — см. expireOverdueAccess.
    override fun markDuesPaid(membershipId: UUID, markedBy: UUID, accessUntil: OffsetDateTime): Int {
        val now = OffsetDateTime.now()
        return dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .setNull(MEMBERSHIPS.ACCESS_FROZEN_AT)
            .set(MEMBERSHIPS.DUES_MARKED_PAID_AT, now)
            .set(MEMBERSHIPS.DUES_MARKED_BY, markedBy)
            .set(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT, accessUntil)
            // Открытие доступа закрывает любой ожидающий claim взноса — очистить, чтобы он покинул «Ждут оплаты».
            .setNull(MEMBERSHIPS.DUES_CLAIMED_AT)
            .setNull(MEMBERSHIPS.DUES_CLAIM_METHOD)
            .setNull(MEMBERSHIPS.DUES_PROOF_URL)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(
                MEMBERSHIPS.ID.eq(membershipId)
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.frozen, MembershipStatus.expired))
            )
            .execute()
    }

    // Очищает только запись о взносе; НЕ замораживает обратно (симметрично со складчиной, где отмена
    // тоже не закрывается автоматически). Guard на dues_marked_paid_at делает повторную отмену no-op
    // (сервис → идемпотентно).
    override fun unmarkDues(membershipId: UUID): Int {
        val now = OffsetDateTime.now()
        return dsl.update(MEMBERSHIPS)
            .setNull(MEMBERSHIPS.DUES_MARKED_PAID_AT)
            .setNull(MEMBERSHIPS.DUES_MARKED_BY)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId).and(MEMBERSHIPS.DUES_MARKED_PAID_AT.isNotNull))
            .execute()
    }

    // «Своя дата»: организатор вручную задаёт конец окна доступа. Открывает доступ (active, снимает
    // frozen) без фиксации оплаты взноса — это админский override, а не событие «взнос получен».
    override fun setAccessUntil(membershipId: UUID, accessUntil: OffsetDateTime): Int {
        val now = OffsetDateTime.now()
        return dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .setNull(MEMBERSHIPS.ACCESS_FROZEN_AT)
            .set(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT, accessUntil)
            // Открытие доступа закрывает любой ожидающий claim взноса.
            .setNull(MEMBERSHIPS.DUES_CLAIMED_AT)
            .setNull(MEMBERSHIPS.DUES_CLAIM_METHOD)
            .setNull(MEMBERSHIPS.DUES_PROOF_URL)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId))
            .execute()
    }

    override fun updateOrganizerNote(membershipId: UUID, note: String?): Int {
        val now = OffsetDateTime.now()
        return dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.ORGANIZER_NOTE, note)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId))
            .execute()
    }

    override fun findExpiringWithin(now: OffsetDateTime, threshold: OffsetDateTime): List<ExpiringSubscriptionNotification> =
        dsl.select(USERS.TELEGRAM_ID, CLUBS.NAME, CLUBS.ID)
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .where(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(threshold))
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.greaterThan(now))
            )
            .fetch { record ->
                ExpiringSubscriptionNotification(
                    telegramId = record.get(USERS.TELEGRAM_ID)!!,
                    clubName = record.get(CLUBS.NAME)!!,
                    clubId = record.get(CLUBS.ID)!!
                )
            }

    override fun findActiveExpired(now: OffsetDateTime): List<ExpiringSubscriptionNotification> =
        dsl.select(USERS.TELEGRAM_ID, CLUBS.NAME, CLUBS.ID)
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .where(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(now))
            )
            .fetch { record ->
                ExpiringSubscriptionNotification(
                    telegramId = record.get(USERS.TELEGRAM_ID)!!,
                    clubName = record.get(CLUBS.NAME)!!,
                    clubId = record.get(CLUBS.ID)!!
                )
            }

    // Автоистечение honor-system: `active`-платное членство, у которого прошло окно доступа, переходит
    // в `expired` (просрочено продление) — остаётся членством и занимает слот, но теряет доступ к
    // контенту до следующего подтверждённого взноса. Бесплатные членства (subscription_expires_at
    // IS NULL) исключены. `access_frozen_at` не трогаем — это метка заморозки, момент истечения и так
    // виден по subscription_expires_at. НЕ логируется в membership_history: лапс продления —
    // приостановка доступа, а не событие оттока; club-quality считает события `left`+`expired`
    // оттоком, и лог на каждый транзиентный лапс штрафовал бы ранг клуба ложным churn'ом
    // (решение зафиксировано в docs/modules/membership-lifecycle.md §3).
    override fun expireOverdueAccess(now: OffsetDateTime): Int =
        dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.expired)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.isNotNull)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(now))
            )
            .execute()

    override fun countExpiringSoonByClubs(clubIds: Collection<UUID>, now: OffsetDateTime, threshold: OffsetDateTime): Int {
        if (clubIds.isEmpty()) return 0
        return dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.`in`(clubIds)
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.greaterThan(now))
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(threshold))
            )
            .fetchOne(0, Int::class.java) ?: 0
    }

    override fun countClaimedAwaitingDuesByClubs(clubIds: Collection<UUID>): Int {
        if (clubIds.isEmpty()) return 0
        return dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.`in`(clubIds)
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.frozen, MembershipStatus.expired))
                    // Только заявившие об оплате: не-claimed frozen/expired внимания не требует (мяч у участника).
                    .and(MEMBERSHIPS.DUES_CLAIMED_AT.isNotNull)
            )
            .fetchOne(0, Int::class.java) ?: 0
    }

    override fun countClaimedAwaitingDuesByOwner(ownerId: UUID): Int =
        dsl.selectCount().from(MEMBERSHIPS)
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .where(
                CLUBS.OWNER_ID.eq(ownerId)
                    .and(CLUBS.IS_ACTIVE.eq(true))
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.frozen, MembershipStatus.expired))
                    .and(MEMBERSHIPS.DUES_CLAIMED_AT.isNotNull)
            )
            .fetchOne(0, Int::class.java) ?: 0

    override fun findAwaitingDuesMembersByOwner(ownerId: UUID): List<OrganizerDuesMember> {
        return dsl.select(
            MEMBERSHIPS.USER_ID,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            USERS.AVATAR_URL,
            USERS.TELEGRAM_USERNAME,
            CLUBS.ID,
            CLUBS.NAME,
            CLUBS.AVATAR_URL,
            MEMBERSHIPS.JOINED_AT,
            MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT,
            MEMBERSHIPS.DUES_CLAIMED_AT,
            MEMBERSHIPS.DUES_CLAIM_METHOD,
            MEMBERSHIPS.STATUS
        )
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .where(
                CLUBS.OWNER_ID.eq(ownerId)
                    .and(CLUBS.IS_ACTIVE.eq(true))
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.frozen, MembershipStatus.expired))
            )
            .orderBy(MEMBERSHIPS.JOINED_AT.desc())
            .fetch { r ->
                OrganizerDuesMember(
                    userId = r.get(MEMBERSHIPS.USER_ID)!!,
                    firstName = r.get(USERS.FIRST_NAME),
                    lastName = r.get(USERS.LAST_NAME),
                    avatarUrl = r.get(USERS.AVATAR_URL),
                    telegramUsername = r.get(USERS.TELEGRAM_USERNAME),
                    clubId = r.get(CLUBS.ID)!!,
                    clubName = r.get(CLUBS.NAME)!!,
                    clubAvatarUrl = r.get(CLUBS.AVATAR_URL),
                    joinedAt = r.get(MEMBERSHIPS.JOINED_AT)!!,
                    subscriptionExpiresAt = r.get(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT),
                    duesClaimedAt = r.get(MEMBERSHIPS.DUES_CLAIMED_AT),
                    duesClaimMethod = r.get(MEMBERSHIPS.DUES_CLAIM_METHOD),
                    accessStatus = (r.get(MEMBERSHIPS.STATUS) ?: MembershipStatus.frozen).literal
                )
            }
    }

    // Telegram ID участников, у которых сейчас есть доступ к клубу — общий предикат
    // MembershipAccess (status `active`). Участникам без доступа
    // (frozen/expired) нельзя слать DM про событие, которое им не открыть. (GAP-010)
    override fun findMemberTelegramIds(clubId: UUID): List<Long> {
        return dsl.select(USERS.TELEGRAM_ID)
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .where(
                MEMBERSHIPS.CLUB_ID.eq(clubId)
                    .and(MembershipAccess.hasAccess())
            )
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()
    }
}
