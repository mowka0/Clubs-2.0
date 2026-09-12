package com.clubs.debt

import com.clubs.generated.jooq.enums.DebtSettlementStatus
import com.clubs.generated.jooq.enums.DebtStatus
import com.clubs.generated.jooq.enums.SkladchinaKind
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.DEBTS
import com.clubs.generated.jooq.tables.references.DEBT_SETTLEMENTS
import com.clubs.generated.jooq.tables.references.SKLADCHINAS
import com.clubs.generated.jooq.tables.references.USERS
import org.jooq.Condition
import org.jooq.Record
import org.jooq.SelectOnConditionStep
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqDebtRepository(
    private val dsl: DSLContext,
    private val mapper: DebtMapper
) : DebtRepository {

    // Обе стороны долга из одной таблицы users — два алиаса в одном JOIN.
    private val debtor = USERS.`as`("debtor")
    private val creditor = USERS.`as`("creditor")

    override fun insertAll(debts: List<NewDebt>): List<Debt> {
        if (debts.isEmpty()) return emptyList()
        var step = dsl.insertInto(DEBTS)
            .columns(
                DEBTS.SKLADCHINA_ID, DEBTS.DEBTOR_ID, DEBTS.CREDITOR_ID, DEBTS.AMOUNT_KOPECKS, DEBTS.DUE_AT,
                DEBTS.STATUS, DEBTS.CLAIMED_AT, DEBTS.CONFIRMED_AT, DEBTS.NOTE
            )
        debts.forEach {
            step = step.values(
                it.skladchinaId, it.debtorId, it.creditorId, it.amountKopecks, it.dueAt,
                it.status, it.claimedAt, it.confirmedAt, it.note
            )
        }
        return step.returning().fetch().map(mapper::toDomain)
    }

    override fun insertIfAbsent(debt: NewDebt): Debt? =
        dsl.insertInto(DEBTS)
            .set(DEBTS.SKLADCHINA_ID, debt.skladchinaId)
            .set(DEBTS.DEBTOR_ID, debt.debtorId)
            .set(DEBTS.CREDITOR_ID, debt.creditorId)
            .set(DEBTS.AMOUNT_KOPECKS, debt.amountKopecks)
            .set(DEBTS.DUE_AT, debt.dueAt)
            .set(DEBTS.STATUS, debt.status)
            .set(DEBTS.CLAIMED_AT, debt.claimedAt)
            .set(DEBTS.CONFIRMED_AT, debt.confirmedAt)
            .set(DEBTS.NOTE, debt.note)
            .onConflictDoNothing()
            .returning()
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findById(id: UUID): Debt? =
        dsl.selectFrom(DEBTS).where(DEBTS.ID.eq(id)).fetchOne()?.let(mapper::toDomain)

    override fun findWithContext(id: UUID): DebtWithContext? =
        contextSelect().where(DEBTS.ID.eq(id)).fetchOne()?.let(::toContext)

    override fun findBySkladchina(skladchinaId: UUID): List<DebtWithContext> =
        contextSelect()
            .where(DEBTS.SKLADCHINA_ID.eq(skladchinaId))
            .orderBy(DEBTS.CREATED_AT.asc(), DEBTS.ID.asc())
            .fetch(::toContext)

    override fun findBySkladchinaAndDebtor(skladchinaId: UUID, debtorId: UUID): Debt? =
        dsl.selectFrom(DEBTS)
            .where(DEBTS.SKLADCHINA_ID.eq(skladchinaId).and(DEBTS.DEBTOR_ID.eq(debtorId)))
            .fetchOne()?.let(mapper::toDomain)

    override fun totals(skladchinaId: UUID): DebtTotals =
        totalsBatch(listOf(skladchinaId))[skladchinaId] ?: DebtTotals.EMPTY

    override fun totalsBatch(skladchinaIds: Collection<UUID>): Map<UUID, DebtTotals> {
        if (skladchinaIds.isEmpty()) return emptyMap()
        val received = DEBTS.STATUS.eq(DebtStatus.received)
        val claimed = DEBTS.STATUS.eq(DebtStatus.claimed)
        val open = DEBTS.STATUS.`in`(OPEN_DEBT_STATUSES)
        val live = DEBTS.STATUS.`in`(LIVE_DEBT_STATUSES)
        return dsl.select(
            DEBTS.SKLADCHINA_ID,
            DSL.sum(DEBTS.AMOUNT_KOPECKS).filterWhere(received),
            DSL.sum(DEBTS.AMOUNT_KOPECKS).filterWhere(claimed),
            DSL.sum(DEBTS.AMOUNT_KOPECKS).filterWhere(live),
            DSL.count().filterWhere(live),
            DSL.count().filterWhere(received),
            DSL.count().filterWhere(open),
            DSL.count().filterWhere(claimed)
        )
            .from(DEBTS)
            .where(DEBTS.SKLADCHINA_ID.`in`(skladchinaIds))
            .groupBy(DEBTS.SKLADCHINA_ID)
            .fetch()
            .associate { r ->
                r.value1()!! to DebtTotals(
                    receivedKopecks = r.value2().toLongOrZero(),
                    claimedKopecks = r.value3().toLongOrZero(),
                    // Живых долгов нет → знаменателя нет (null), экран подставит сумму сбора.
                    targetKopecks = if (r.value5() > 0) r.value4().toLongOrZero() else null,
                    debtCount = r.value5(),
                    receivedCount = r.value6(),
                    openCount = r.value7(),
                    claimedCount = r.value8()
                )
            }
    }

    override fun findOpenForUser(userId: UUID): List<DebtWithContext> =
        contextSelect()
            .where(
                DEBTS.DEBTOR_ID.eq(userId).or(DEBTS.CREDITOR_ID.eq(userId))
                    .and(DEBTS.STATUS.`in`(OPEN_DEBT_STATUSES))
                    .and(CLUBS.IS_ACTIVE.isTrue)
            )
            .orderBy(DEBTS.DUE_AT.asc().nullsLast(), DEBTS.CREATED_AT.asc())
            .fetch(::toContext)

    override fun findOpenBetween(userA: UUID, userB: UUID): List<DebtWithContext> =
        contextSelect()
            .where(betweenCondition(userA, userB).and(DEBTS.STATUS.`in`(OPEN_DEBT_STATUSES)).and(CLUBS.IS_ACTIVE.isTrue))
            .orderBy(DEBTS.DUE_AT.asc().nullsLast(), DEBTS.CREATED_AT.asc())
            .fetch(::toContext)

    override fun countActionRequired(userId: UUID): Int =
        dsl.selectCount().from(DEBTS)
            .join(SKLADCHINAS).on(SKLADCHINAS.ID.eq(DEBTS.SKLADCHINA_ID))
            .join(CLUBS).on(CLUBS.ID.eq(SKLADCHINAS.CLUB_ID))
            .where(
                CLUBS.IS_ACTIVE.isTrue.and(
                    DEBTS.DEBTOR_ID.eq(userId).and(DEBTS.STATUS.`in`(DebtStatus.waiting, DebtStatus.promised))
                        .or(DEBTS.CREDITOR_ID.eq(userId).and(DEBTS.STATUS.eq(DebtStatus.claimed)))
                )
            )
            .fetchOne(0, Int::class.java) ?: 0

    // --- Переходы одиночного долга ---

    override fun promise(id: UUID, date: LocalDate): Int =
        dsl.update(DEBTS)
            .set(DEBTS.STATUS, DebtStatus.promised)
            .set(DEBTS.PROMISED_AT, date)
            .setNull(DEBTS.PROMISE_REMINDED_AT)
            .set(DEBTS.UPDATED_AT, OffsetDateTime.now())
            .where(DEBTS.ID.eq(id).and(DEBTS.STATUS.`in`(DebtStatus.waiting, DebtStatus.promised)).and(DEBTS.SETTLEMENT_ID.isNull))
            .execute()

    override fun claim(id: UUID, at: OffsetDateTime): Int =
        dsl.update(DEBTS)
            .set(DEBTS.STATUS, DebtStatus.claimed)
            .set(DEBTS.CLAIMED_AT, at)
            .setNull(DEBTS.PROMISED_AT)
            .setNull(DEBTS.CLAIM_REMINDED_AT)
            .set(DEBTS.UPDATED_AT, at)
            .where(DEBTS.ID.eq(id).and(DEBTS.STATUS.`in`(DebtStatus.waiting, DebtStatus.promised)).and(DEBTS.SETTLEMENT_ID.isNull))
            .execute()

    override fun unclaim(id: UUID): Int =
        dsl.update(DEBTS)
            .set(DEBTS.STATUS, DebtStatus.waiting)
            .setNull(DEBTS.CLAIMED_AT)
            .set(DEBTS.UPDATED_AT, OffsetDateTime.now())
            .where(DEBTS.ID.eq(id).and(DEBTS.STATUS.eq(DebtStatus.claimed)).and(DEBTS.SETTLEMENT_ID.isNull))
            .execute()

    override fun confirm(id: UUID, at: OffsetDateTime): Int =
        dsl.update(DEBTS)
            .set(DEBTS.STATUS, DebtStatus.received)
            .set(DEBTS.CONFIRMED_AT, at)
            .setNull(DEBTS.PROMISED_AT)
            .set(DEBTS.UPDATED_AT, at)
            .where(DEBTS.ID.eq(id).and(DEBTS.STATUS.`in`(OPEN_DEBT_STATUSES)).and(DEBTS.SETTLEMENT_ID.isNull))
            .execute()

    // claimed_at стирается: иначе позднее «Получил» засчитало бы плюс по дате ложного «Отдал».
    override fun reject(id: UUID, note: String?, at: OffsetDateTime): Int =
        dsl.update(DEBTS)
            .set(DEBTS.STATUS, DebtStatus.waiting)
            .set(DEBTS.REJECTED_AT, at)
            .set(DEBTS.REJECT_NOTE, note)
            .setNull(DEBTS.CLAIMED_AT)
            .set(DEBTS.UPDATED_AT, at)
            .where(DEBTS.ID.eq(id).and(DEBTS.STATUS.eq(DebtStatus.claimed)).and(DEBTS.SETTLEMENT_ID.isNull))
            .execute()

    override fun drop(id: UUID, fromStatuses: Set<DebtStatus>): Int =
        dsl.update(DEBTS)
            .set(DEBTS.STATUS, DebtStatus.dropped)
            .setNull(DEBTS.PROMISED_AT)
            .set(DEBTS.UPDATED_AT, OffsetDateTime.now())
            .where(DEBTS.ID.eq(id).and(DEBTS.STATUS.`in`(fromStatuses)).and(DEBTS.SETTLEMENT_ID.isNull))
            .execute()

    override fun forgive(id: UUID): Int =
        dsl.update(DEBTS)
            .set(DEBTS.STATUS, DebtStatus.forgiven)
            .setNull(DEBTS.PROMISED_AT)
            .set(DEBTS.UPDATED_AT, OffsetDateTime.now())
            .where(DEBTS.ID.eq(id).and(DEBTS.STATUS.`in`(OPEN_DEBT_STATUSES)).and(DEBTS.SETTLEMENT_ID.isNull))
            .execute()

    override fun changeAmount(id: UUID, amountKopecks: Long): Int =
        dsl.update(DEBTS)
            .set(DEBTS.AMOUNT_KOPECKS, amountKopecks)
            .set(DEBTS.UPDATED_AT, OffsetDateTime.now())
            .where(DEBTS.ID.eq(id).and(DEBTS.STATUS.`in`(DebtStatus.waiting, DebtStatus.promised)).and(DEBTS.SETTLEMENT_ID.isNull))
            .execute()

    override fun setReceipt(id: UUID, url: String): Int =
        dsl.update(DEBTS)
            .set(DEBTS.RECEIPT_URL, url)
            .set(DEBTS.UPDATED_AT, OffsetDateTime.now())
            .where(DEBTS.ID.eq(id).and(DEBTS.STATUS.`in`(OPEN_DEBT_STATUSES)))
            .execute()

    override fun setNote(id: UUID, note: String): Int =
        dsl.update(DEBTS)
            .set(DEBTS.NOTE, note)
            .set(DEBTS.UPDATED_AT, OffsetDateTime.now())
            .where(DEBTS.ID.eq(id).and(DEBTS.STATUS.`in`(OPEN_DEBT_STATUSES)))
            .execute()

    override fun dropWaitingBySkladchina(skladchinaId: UUID): List<Debt> =
        dsl.update(DEBTS)
            .set(DEBTS.STATUS, DebtStatus.dropped)
            .set(DEBTS.UPDATED_AT, OffsetDateTime.now())
            .where(
                DEBTS.SKLADCHINA_ID.eq(skladchinaId)
                    .and(DEBTS.STATUS.`in`(DebtStatus.waiting, DebtStatus.promised))
                    .and(DEBTS.SETTLEMENT_ID.isNull)
            )
            .returning()
            .fetch()
            .map(mapper::toDomain)

    override fun forgiveOpenBySkladchina(skladchinaId: UUID): Int =
        dsl.update(DEBTS)
            .set(DEBTS.STATUS, DebtStatus.forgiven)
            .setNull(DEBTS.SETTLEMENT_ID)
            .set(DEBTS.UPDATED_AT, OffsetDateTime.now())
            .where(DEBTS.SKLADCHINA_ID.eq(skladchinaId).and(DEBTS.STATUS.`in`(OPEN_DEBT_STATUSES)))
            .execute()

    // --- Сальдо пары ---

    override fun createSettlement(payerId: UUID, payeeId: UUID, amountKopecks: Long, at: OffsetDateTime): DebtSettlement =
        dsl.insertInto(DEBT_SETTLEMENTS)
            .set(DEBT_SETTLEMENTS.PAYER_ID, payerId)
            .set(DEBT_SETTLEMENTS.PAYEE_ID, payeeId)
            .set(DEBT_SETTLEMENTS.AMOUNT_KOPECKS, amountKopecks)
            .set(DEBT_SETTLEMENTS.STATUS, DebtSettlementStatus.claimed)
            .set(DEBT_SETTLEMENTS.CLAIMED_AT, at)
            .returning()
            .fetchOne()!!
            .let(mapper::toSettlement)

    override fun findSettlement(id: UUID): DebtSettlement? =
        dsl.selectFrom(DEBT_SETTLEMENTS).where(DEBT_SETTLEMENTS.ID.eq(id)).fetchOne()?.let(mapper::toSettlement)

    override fun findClaimedSettlementBetween(userA: UUID, userB: UUID): DebtSettlement? =
        dsl.selectFrom(DEBT_SETTLEMENTS)
            .where(
                DEBT_SETTLEMENTS.STATUS.eq(DebtSettlementStatus.claimed).and(
                    DEBT_SETTLEMENTS.PAYER_ID.eq(userA).and(DEBT_SETTLEMENTS.PAYEE_ID.eq(userB))
                        .or(DEBT_SETTLEMENTS.PAYER_ID.eq(userB).and(DEBT_SETTLEMENTS.PAYEE_ID.eq(userA)))
                )
            )
            .limit(1)
            .fetchOne()?.let(mapper::toSettlement)

    override fun findClaimedSettlementsForUser(userId: UUID): List<DebtSettlement> =
        dsl.selectFrom(DEBT_SETTLEMENTS)
            .where(
                DEBT_SETTLEMENTS.STATUS.eq(DebtSettlementStatus.claimed)
                    .and(DEBT_SETTLEMENTS.PAYER_ID.eq(userId).or(DEBT_SETTLEMENTS.PAYEE_ID.eq(userId)))
            )
            .fetch()
            .map(mapper::toSettlement)

    override fun findStaleSettlements(claimedBefore: OffsetDateTime, remindedBefore: OffsetDateTime): List<DebtSettlement> =
        dsl.selectFrom(DEBT_SETTLEMENTS)
            .where(
                DEBT_SETTLEMENTS.STATUS.eq(DebtSettlementStatus.claimed)
                    .and(DEBT_SETTLEMENTS.CLAIMED_AT.le(claimedBefore))
                    .and(DEBT_SETTLEMENTS.REMINDED_AT.isNull.or(DEBT_SETTLEMENTS.REMINDED_AT.le(remindedBefore)))
            )
            .fetch()
            .map(mapper::toSettlement)

    override fun markSettlementReminded(id: UUID, at: OffsetDateTime) {
        dsl.update(DEBT_SETTLEMENTS).set(DEBT_SETTLEMENTS.REMINDED_AT, at).where(DEBT_SETTLEMENTS.ID.eq(id)).execute()
    }

    override fun rejectSettlementsTouching(skladchinaId: UUID, at: OffsetDateTime): Int {
        val settlementIds = dsl.selectDistinct(DEBTS.SETTLEMENT_ID)
            .from(DEBTS)
            .where(DEBTS.SKLADCHINA_ID.eq(skladchinaId).and(DEBTS.SETTLEMENT_ID.isNotNull))
            .fetch()
            .mapNotNull { it.value1() }
        var rejected = 0
        settlementIds.forEach { id ->
            val settlement = findSettlement(id) ?: return@forEach
            if (resolveSettlement(id, DebtSettlementStatus.rejected, at) > 0) {
                rejectBySettlement(id, settlement.payerId, at)
                rejected++
            }
        }
        return rejected
    }

    override fun existsAnyBetween(userA: UUID, userB: UUID): Boolean =
        dsl.fetchExists(dsl.selectOne().from(DEBTS).where(betweenCondition(userA, userB)))

    override fun attachToSettlement(debtIds: Collection<UUID>, settlementId: UUID, at: OffsetDateTime): Int {
        if (debtIds.isEmpty()) return 0
        return dsl.update(DEBTS)
            .set(DEBTS.STATUS, DebtStatus.claimed)
            // Одиночное «Отдал», сделанное раньше, не теряет дату: для +10 считается первое заявление.
            .set(DEBTS.CLAIMED_AT, DSL.coalesce(DEBTS.CLAIMED_AT, DSL.`val`(at)))
            .setNull(DEBTS.PROMISED_AT)
            .set(DEBTS.SETTLEMENT_ID, settlementId)
            .setNull(DEBTS.CLAIM_REMINDED_AT)
            .set(DEBTS.UPDATED_AT, at)
            .where(DEBTS.ID.`in`(debtIds).and(DEBTS.STATUS.`in`(OPEN_DEBT_STATUSES)).and(DEBTS.SETTLEMENT_ID.isNull))
            .execute()
    }

    override fun resolveSettlement(id: UUID, status: DebtSettlementStatus, at: OffsetDateTime): Int =
        dsl.update(DEBT_SETTLEMENTS)
            .set(DEBT_SETTLEMENTS.STATUS, status)
            .set(DEBT_SETTLEMENTS.RESOLVED_AT, at)
            .where(DEBT_SETTLEMENTS.ID.eq(id).and(DEBT_SETTLEMENTS.STATUS.eq(DebtSettlementStatus.claimed)))
            .execute()

    override fun confirmBySettlement(settlementId: UUID, at: OffsetDateTime): List<Debt> =
        dsl.update(DEBTS)
            .set(DEBTS.STATUS, DebtStatus.received)
            .set(DEBTS.CONFIRMED_AT, at)
            .set(DEBTS.UPDATED_AT, at)
            .where(DEBTS.SETTLEMENT_ID.eq(settlementId).and(DEBTS.STATUS.eq(DebtStatus.claimed)))
            .returning()
            .fetch()
            .map(mapper::toDomain)

    override fun rejectBySettlement(settlementId: UUID, payerId: UUID, at: OffsetDateTime): List<Debt> {
        val reopened = dsl.update(DEBTS)
            .set(DEBTS.STATUS, DebtStatus.waiting)
            .setNull(DEBTS.SETTLEMENT_ID)
            .setNull(DEBTS.CLAIMED_AT)
            .set(DEBTS.UPDATED_AT, at)
            .where(DEBTS.SETTLEMENT_ID.eq(settlementId).and(DEBTS.STATUS.eq(DebtStatus.claimed)))
            .returning()
            .fetch()
            .map(mapper::toDomain)
        val payerDebtIds = reopened.filter { it.debtorId == payerId }.map { it.id }
        if (payerDebtIds.isNotEmpty()) {
            dsl.update(DEBTS).set(DEBTS.REJECTED_AT, at).where(DEBTS.ID.`in`(payerDebtIds)).execute()
        }
        return reopened
    }

    // --- Фиды шедулера ---

    override fun findForReputationPlus(): List<DebtWithContext> =
        contextSelect()
            .where(
                sharedRealDebt()
                    .and(DEBTS.STATUS.eq(DebtStatus.received))
                    .and(DEBTS.REPUTATION_PLUS_AT.isNull)
                    .and(DEBTS.REPUTATION_MINUS_AT.isNull)
                    .and(DEBTS.DUE_AT.isNotNull)
                    .and(DSL.coalesce(DEBTS.CLAIMED_AT, DEBTS.CONFIRMED_AT).le(DEBTS.DUE_AT))
            )
            .fetch(::toContext)

    override fun markReputationPlus(id: UUID, at: OffsetDateTime) {
        dsl.update(DEBTS).set(DEBTS.REPUTATION_PLUS_AT, at).where(DEBTS.ID.eq(id)).execute()
    }

    override fun findForReputationMinus(overdueBefore: OffsetDateTime): List<DebtWithContext> =
        contextSelect()
            .where(
                sharedRealDebt()
                    .and(DEBTS.STATUS.`in`(DebtStatus.waiting, DebtStatus.promised))
                    .and(DEBTS.REPUTATION_MINUS_AT.isNull)
                    .and(DEBTS.DUE_AT.isNotNull)
                    // greatest() в Postgres пропускает NULL: без «Не получил» точка отсчёта — срок.
                    .and(DSL.greatest(DEBTS.DUE_AT, DEBTS.REJECTED_AT).lt(overdueBefore))
            )
            .fetch(::toContext)

    override fun markReputationMinus(id: UUID, at: OffsetDateTime) {
        dsl.update(DEBTS).set(DEBTS.REPUTATION_MINUS_AT, at).where(DEBTS.ID.eq(id)).execute()
    }

    override fun findDueSoon(now: OffsetDateTime, until: OffsetDateTime): List<DebtWithContext> =
        contextSelect()
            .where(
                realDebt()
                    .and(DEBTS.STATUS.eq(DebtStatus.waiting))
                    .and(DEBTS.DUE_REMINDER_SENT_AT.isNull)
                    .and(DEBTS.DUE_AT.gt(now))
                    .and(DEBTS.DUE_AT.le(until))
            )
            .fetch(::toContext)

    override fun markDueReminderSent(id: UUID, at: OffsetDateTime) {
        dsl.update(DEBTS).set(DEBTS.DUE_REMINDER_SENT_AT, at).where(DEBTS.ID.eq(id)).execute()
    }

    override fun findOverdue(now: OffsetDateTime, remindedBefore: OffsetDateTime): List<DebtWithContext> =
        contextSelect()
            .where(
                realDebt()
                    .and(DEBTS.STATUS.eq(DebtStatus.waiting))
                    .and(DEBTS.DUE_AT.le(now))
                    .and(DEBTS.OVERDUE_REMINDED_AT.isNull.or(DEBTS.OVERDUE_REMINDED_AT.le(remindedBefore)))
            )
            .fetch(::toContext)

    override fun markOverdueReminded(id: UUID, at: OffsetDateTime) {
        dsl.update(DEBTS).set(DEBTS.OVERDUE_REMINDED_AT, at).where(DEBTS.ID.eq(id)).execute()
    }

    override fun findPromiseDue(today: LocalDate): List<DebtWithContext> =
        contextSelect()
            .where(
                realDebt()
                    .and(DEBTS.STATUS.eq(DebtStatus.promised))
                    .and(DEBTS.PROMISED_AT.le(today))
                    .and(DEBTS.PROMISE_REMINDED_AT.isNull)
            )
            .fetch(::toContext)

    override fun markPromiseReminded(id: UUID, at: OffsetDateTime) {
        dsl.update(DEBTS).set(DEBTS.PROMISE_REMINDED_AT, at).where(DEBTS.ID.eq(id)).execute()
    }

    override fun findClaimedStale(claimedBefore: OffsetDateTime, remindedBefore: OffsetDateTime): List<DebtWithContext> =
        contextSelect()
            .where(
                realDebt()
                    .and(DEBTS.STATUS.eq(DebtStatus.claimed))
                    .and(DEBTS.SETTLEMENT_ID.isNull)
                    .and(DEBTS.CLAIMED_AT.le(claimedBefore))
                    .and(DEBTS.CLAIM_REMINDED_AT.isNull.or(DEBTS.CLAIM_REMINDED_AT.le(remindedBefore)))
            )
            .fetch(::toContext)

    override fun markClaimReminded(id: UUID, at: OffsetDateTime) {
        dsl.update(DEBTS).set(DEBTS.CLAIM_REMINDED_AT, at).where(DEBTS.ID.eq(id)).execute()
    }

    // --- helpers ---

    /** Настоящий долг перед другим человеком: доля создателя (сам себе) в напоминания и репутацию не идёт. */
    private fun realDebt(): Condition =
        DEBTS.DEBTOR_ID.ne(DEBTS.CREDITOR_ID).and(CLUBS.IS_ACTIVE.isTrue)

    private fun sharedRealDebt(): Condition =
        realDebt().and(SKLADCHINAS.KIND.eq(SkladchinaKind.shared))

    private fun betweenCondition(a: UUID, b: UUID): Condition =
        DEBTS.DEBTOR_ID.eq(a).and(DEBTS.CREDITOR_ID.eq(b))
            .or(DEBTS.DEBTOR_ID.eq(b).and(DEBTS.CREDITOR_ID.eq(a)))

    private fun contextSelect(): SelectOnConditionStep<Record> =
        dsl.select(
            DEBTS.asterisk(),
            SKLADCHINAS.TITLE, SKLADCHINAS.KIND, SKLADCHINAS.ORDERED_AT, SKLADCHINAS.CLUB_ID,
            SKLADCHINAS.PAYMENT_LINK, SKLADCHINAS.PAYMENT_METHOD_NOTE,
            CLUBS.NAME,
            debtor.ID, debtor.FIRST_NAME, debtor.LAST_NAME, debtor.TELEGRAM_USERNAME, debtor.AVATAR_URL,
            creditor.ID, creditor.FIRST_NAME, creditor.LAST_NAME, creditor.TELEGRAM_USERNAME, creditor.AVATAR_URL
        )
            .from(DEBTS)
            .join(SKLADCHINAS).on(SKLADCHINAS.ID.eq(DEBTS.SKLADCHINA_ID))
            .join(CLUBS).on(CLUBS.ID.eq(SKLADCHINAS.CLUB_ID))
            .join(debtor).on(debtor.ID.eq(DEBTS.DEBTOR_ID))
            .join(creditor).on(creditor.ID.eq(DEBTS.CREDITOR_ID))

    private fun toContext(r: Record): DebtWithContext = DebtWithContext(
        debt = mapper.toDomain(r.into(DEBTS)),
        skladchinaTitle = r.get(SKLADCHINAS.TITLE)!!,
        skladchinaKind = r.get(SKLADCHINAS.KIND)!!,
        skladchinaOrderedAt = r.get(SKLADCHINAS.ORDERED_AT),
        clubId = r.get(SKLADCHINAS.CLUB_ID)!!,
        clubName = r.get(CLUBS.NAME)!!,
        paymentLink = r.get(SKLADCHINAS.PAYMENT_LINK)!!,
        paymentMethodNote = r.get(SKLADCHINAS.PAYMENT_METHOD_NOTE),
        debtor = DebtPerson(
            id = r.get(debtor.ID)!!,
            firstName = r.get(debtor.FIRST_NAME)!!,
            lastName = r.get(debtor.LAST_NAME),
            username = r.get(debtor.TELEGRAM_USERNAME),
            avatarUrl = r.get(debtor.AVATAR_URL)
        ),
        creditor = DebtPerson(
            id = r.get(creditor.ID)!!,
            firstName = r.get(creditor.FIRST_NAME)!!,
            lastName = r.get(creditor.LAST_NAME),
            username = r.get(creditor.TELEGRAM_USERNAME),
            avatarUrl = r.get(creditor.AVATAR_URL)
        )
    )

    private fun BigDecimal?.toLongOrZero(): Long = this?.toLong() ?: 0L
}
