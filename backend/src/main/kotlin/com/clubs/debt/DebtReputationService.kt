package com.clubs.debt

import com.clubs.club.ClubRepository
import com.clubs.generated.jooq.enums.ReputationAxis
import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.enums.ReputationSource
import com.clubs.reputation.LedgerEntry
import com.clubs.reputation.ReputationPolicy
import com.clubs.reputation.ReputationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** Долг, за который только что списано −40: шедулер шлёт должнику DM. */
data class DebtPenalty(val debt: DebtWithContext)

/**
 * Репутация по долгам shared-сборов (§ 4): +10 за долг, закрытый до срока; −40 один раз за
 * просрочку дольше `debts.overdue-weeks` в waiting/promised (claimed часы останавливает).
 * Идемпотентно по штампам reputation_plus_at / reputation_minus_at и UNIQUE леджера.
 * Владелец клуба в своём клубе очков не получает (анти-фарм правило 1).
 */
@Service
class DebtReputationService(
    private val debtRepository: DebtRepository,
    private val clubRepository: ClubRepository,
    private val reputationService: ReputationService,
    @Value("\${debts.overdue-weeks:3}") private val overdueWeeks: Long
) {
    private val log = LoggerFactory.getLogger(DebtReputationService::class.java)

    @Transactional
    fun applyPlus(now: OffsetDateTime): Int {
        val due = debtRepository.findForReputationPlus()
        if (due.isEmpty()) return 0
        val owners = ownersOf(due)
        var awarded = 0
        due.forEach { d ->
            if (owners[d.clubId] != d.debt.debtorId) {
                reputationService.appendAndRecompute(listOf(
                    entry(d, ReputationKind.skladchina_paid, occurredAt = d.debt.claimedAt ?: d.debt.confirmedAt ?: now)
                ))
                awarded++
            }
            debtRepository.markReputationPlus(d.debt.id, now)
        }
        log.info("Debt reputation plus: candidates={} awarded={}", due.size, awarded)
        return awarded
    }

    @Transactional
    fun applyMinus(now: OffsetDateTime): List<DebtPenalty> {
        val overdue = debtRepository.findForReputationMinus(now.minusWeeks(overdueWeeks))
        if (overdue.isEmpty()) return emptyList()
        val owners = ownersOf(overdue)
        val penalized = mutableListOf<DebtPenalty>()
        overdue.forEach { d ->
            if (owners[d.clubId] != d.debt.debtorId) {
                reputationService.appendAndRecompute(listOf(
                    entry(d, ReputationKind.skladchina_expired, occurredAt = d.debt.dueAt ?: now)
                ))
                penalized += DebtPenalty(d)
            }
            debtRepository.markReputationMinus(d.debt.id, now)
        }
        log.info("Debt reputation minus: candidates={} penalized={}", overdue.size, penalized.size)
        return penalized
    }

    private fun ownersOf(debts: List<DebtWithContext>): Map<UUID, UUID?> =
        debts.map { it.clubId }.distinct().associateWith { clubRepository.findById(it)?.ownerId }

    private fun entry(d: DebtWithContext, kind: ReputationKind, occurredAt: OffsetDateTime) = LedgerEntry(
        userId = d.debt.debtorId,
        clubId = d.clubId,
        axis = ReputationAxis.finance,
        kind = kind,
        points = ReputationPolicy.pointsFor(kind),
        occurredAt = occurredAt,
        sourceType = ReputationSource.skladchina,
        sourceId = d.debt.skladchinaId
    )
}
