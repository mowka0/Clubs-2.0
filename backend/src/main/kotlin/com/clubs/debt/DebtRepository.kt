package com.clubs.debt

import com.clubs.generated.jooq.enums.DebtSettlementStatus
import com.clubs.generated.jooq.enums.DebtStatus
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Долги и сальдо пар. Каждый переход состояния — `UPDATE … WHERE status IN (…)` с возвратом числа
 * строк: 0 означает, что долг ушёл из ожидаемого состояния между чтением и записью (гонка двух
 * кнопок), и сервис отвечает 409, а не перезаписывает чужой переход.
 */
interface DebtRepository {

    fun insertAll(debts: List<NewDebt>): List<Debt>

    /** Вставка «только если долга (сбор, должник) ещё нет» — ON CONFLICT DO NOTHING; null = уже есть (второй тап). */
    fun insertIfAbsent(debt: NewDebt): Debt?

    fun findById(id: UUID): Debt?

    fun findWithContext(id: UUID): DebtWithContext?

    /** Долги сбора с контекстом, в порядке создания. */
    fun findBySkladchina(skladchinaId: UUID): List<DebtWithContext>

    fun findBySkladchinaAndDebtor(skladchinaId: UUID, debtorId: UUID): Debt?

    fun totals(skladchinaId: UUID): DebtTotals

    fun totalsBatch(skladchinaIds: Collection<UUID>): Map<UUID, DebtTotals>

    /** Открытые долги, где [userId] одна из сторон, в активных клубах. */
    fun findOpenForUser(userId: UUID): List<DebtWithContext>

    /** Открытые долги пары в обе стороны. */
    fun findOpenBetween(userA: UUID, userB: UUID): List<DebtWithContext>

    /** Открытые долги как должника + claimed как получателя — «нужно действовать». */
    fun countActionRequired(userId: UUID): Int

    // --- Переходы одиночного долга (§ 2.2) ---

    /** waiting/promised → promised, новая дата обещания; напоминание по обещанию сбрасывается. */
    fun promise(id: UUID, date: LocalDate): Int

    /** waiting/promised → claimed (только вне сальдо). */
    fun claim(id: UUID, at: OffsetDateTime): Int

    /** claimed → waiting по воле должника (только вне сальдо). */
    fun unclaim(id: UUID): Int

    /** waiting/promised/claimed → received (только вне сальдо). */
    fun confirm(id: UUID, at: OffsetDateTime): Int

    /** claimed → waiting: «Не получил» с заметкой (только вне сальдо). */
    fun reject(id: UUID, note: String?, at: OffsetDateTime): Int

    /** Открытый долг → dropped (per_head: «Передумал», «Заказываю», «Не получил» после заказа). */
    fun drop(id: UUID, fromStatuses: Set<DebtStatus>): Int

    /** Открытый долг → forgiven. */
    fun forgive(id: UUID): Int

    /** waiting/promised: новая сумма. */
    fun changeAmount(id: UUID, amountKopecks: Long): Int

    fun setReceipt(id: UUID, url: String): Int

    fun setNote(id: UUID, note: String): Int

    /** «Заказываю»: все waiting/promised долги сбора → dropped. Возвращает выбывших. */
    fun dropWaitingBySkladchina(skladchinaId: UUID): List<Debt>

    /** Отмена сбора: все открытые долги → forgiven. */
    fun forgiveOpenBySkladchina(skladchinaId: UUID): Int

    // --- Сальдо пары (§ 2.3) ---

    fun createSettlement(payerId: UUID, payeeId: UUID, amountKopecks: Long, at: OffsetDateTime): DebtSettlement

    fun findSettlement(id: UUID): DebtSettlement?

    /** Неразобранное сальдо между двумя людьми в любую сторону. */
    fun findClaimedSettlementBetween(userA: UUID, userB: UUID): DebtSettlement?

    /** Все неразобранные сальдо, где [userId] плательщик или получатель — для плашек экрана «Долги». */
    fun findClaimedSettlementsForUser(userId: UUID): List<DebtSettlement>

    /** Неразобранные сальдо, заявленные раньше [claimedBefore], без напоминания позже [remindedBefore]. */
    fun findStaleSettlements(claimedBefore: OffsetDateTime, remindedBefore: OffsetDateTime): List<DebtSettlement>

    fun markSettlementReminded(id: UUID, at: OffsetDateTime)

    /**
     * Отмена сбора: неразобранные сальдо, в которые вошли его долги, отклоняются (все долги пары
     * снова открыты) — иначе сальдо повисло бы с суммой, куда входит уже прощённый долг. Число сальдо.
     */
    fun rejectSettlementsTouching(skladchinaId: UUID, at: OffsetDateTime): Int

    /** Была ли между двумя людьми хоть одна запись долга (любого статуса) — гейт экрана пары. */
    fun existsAnyBetween(userA: UUID, userB: UUID): Boolean

    /** Открытые долги [debtIds] → claimed с [settlementId]; claimed_at = момент «Отдал Σ». */
    fun attachToSettlement(debtIds: Collection<UUID>, settlementId: UUID, at: OffsetDateTime): Int

    /** claimed → [status] у сальдо. 0 = уже разобрано. */
    fun resolveSettlement(id: UUID, status: DebtSettlementStatus, at: OffsetDateTime): Int

    /** «Получил Σ»: все claimed-долги сальдо → received. Возвращает закрытые долги. */
    fun confirmBySettlement(settlementId: UUID, at: OffsetDateTime): List<Debt>

    /**
     * «Не получил» по сальдо: все claimed-долги сальдо → waiting, связь с сальдо снята;
     * rejected_at ставится только долгам плательщика (это его перевод не дошёл).
     */
    fun rejectBySettlement(settlementId: UUID, payerId: UUID, at: OffsetDateTime): List<Debt>

    // --- Фиды шедулера (§ 4, § 5) ---

    /** shared, received, без штампа плюса, оплачено не позже срока. */
    fun findForReputationPlus(): List<DebtWithContext>

    fun markReputationPlus(id: UUID, at: OffsetDateTime)

    /** shared, waiting/promised, без штампа минуса, greatest(due_at, rejected_at) раньше [overdueBefore]. */
    fun findForReputationMinus(overdueBefore: OffsetDateTime): List<DebtWithContext>

    fun markReputationMinus(id: UUID, at: OffsetDateTime)

    /** waiting со сроком в (now, until], напоминание «завтра срок» ещё не уходило. */
    fun findDueSoon(now: OffsetDateTime, until: OffsetDateTime): List<DebtWithContext>

    fun markDueReminderSent(id: UUID, at: OffsetDateTime)

    /** waiting с прошедшим сроком: ещё не напоминали о просрочке или напоминали раньше [remindedBefore]. */
    fun findOverdue(now: OffsetDateTime, remindedBefore: OffsetDateTime): List<DebtWithContext>

    fun markOverdueReminded(id: UUID, at: OffsetDateTime)

    /** promised с датой обещания не позже [today], напоминание по обещанию ещё не уходило. */
    fun findPromiseDue(today: LocalDate): List<DebtWithContext>

    fun markPromiseReminded(id: UUID, at: OffsetDateTime)

    /** claimed вне сальдо дольше [claimedBefore]: получателю ещё не напоминали или напоминали раньше [remindedBefore]. */
    fun findClaimedStale(claimedBefore: OffsetDateTime, remindedBefore: OffsetDateTime): List<DebtWithContext>

    fun markClaimReminded(id: UUID, at: OffsetDateTime)
}
