package com.clubs.skladchina

import com.clubs.debt.Debt
import com.clubs.debt.DebtRepository
import com.clubs.debt.NewDebt
import com.clubs.generated.jooq.enums.DebtStatus
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * «Сумму выбираете сами» (skladchina-v3 § 3.5), шаг по сроку: остаток счёта поровну уходит в долг тем,
 * кто промолчал. Молчун — приглашённый без долга в этом сборе (ни перевода, ни обещания, ни ранее
 * назначенного долга). Остаток = счёт − все живые долги (получено, говорят что отдали, обещано, ждём):
 * обещанные суммы вычитаются до раздела (PO 2026-09-14). Проход один — его вызывает тик «срок вышел»
 * (`claimCloseReminders`); повторный вызов ничего не создаёт: у молчунов уже есть долги.
 */
@Service
class SkladchinaRemainderService(
    private val skladchinaRepository: SkladchinaRepository,
    private val debtRepository: DebtRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(SkladchinaRemainderService::class.java)

    sealed interface Outcome {
        /** Остатка нет (счёт покрыт) или режим не тот — делить нечего. */
        data object Nothing : Outcome
        /** Все ответили, но счёт не закрыт: решает создатель. */
        data class Shortfall(val remainderKopecks: Long) : Outcome
        /** Остаток разделён: созданные долги молчунов. */
        data class Split(val remainderKopecks: Long, val debts: List<Debt>) : Outcome
    }

    @Transactional
    fun splitAmongSilent(s: Skladchina, now: OffsetDateTime): Outcome {
        val target = s.amountKopecks ?: return Outcome.Nothing
        if (!s.isFreeAmountRequired || !s.isActive) return Outcome.Nothing
        val debts = debtRepository.findBySkladchina(s.id).map { it.debt }
        val covered = debts.filter { it.status in LIVE_STATUSES }.sumOf { it.amountKopecks }
        val remainder = target - covered
        if (remainder <= 0) return Outcome.Nothing
        val answered = debts.map { it.debtorId }.toSet()
        val silent = skladchinaRepository.findEnrolledUserIds(s.id).filter { it != s.creatorId && it !in answered }
        if (silent.isEmpty()) return Outcome.Shortfall(remainder)
        val dueAt = (s.deadline ?: now).plusDays(SILENT_DUE_DAYS)
        val created = debtRepository.insertAll(
            SkladchinaShares.equal(remainder, silent)
                .filter { (_, share) -> share > 0 } // остаток меньше числа молчунов в копейках: нулевых долгов не бывает
                .map { (userId, share) -> NewDebt(skladchinaId = s.id, debtorId = userId, creditorId = s.creatorId, amountKopecks = share, dueAt = dueAt) }
        )
        log.info("Skladchina remainder split: id={} remainder={} silent={} dueAt={}", s.id, remainder, created.size, dueAt)
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(s.id))
        return Outcome.Split(remainder, created)
    }

    companion object {
        /** Срок долга молчуна: столько дней после срока сбора (PO 2026-09-14). */
        const val SILENT_DUE_DAYS = 3L
        private val LIVE_STATUSES = setOf(DebtStatus.received, DebtStatus.claimed, DebtStatus.promised, DebtStatus.waiting)
    }
}
