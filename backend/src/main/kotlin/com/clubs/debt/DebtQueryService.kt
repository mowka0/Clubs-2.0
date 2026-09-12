package com.clubs.debt

import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.DebtStatus
import com.clubs.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Экран «Долги» (§ 9): личная книга через все клубы. Показывает только долги, где вызывающий
 * одна из сторон — чужих долгов не видит никто, включая владельца клуба.
 */
@Service
class DebtQueryService(
    private val debtRepository: DebtRepository,
    private val userRepository: UserRepository,
    private val mapper: DebtMapper
) {

    @Transactional(readOnly = true)
    fun overview(userId: UUID): DebtsOverviewDto {
        // Своя доля создателя — не долг перед человеком: в книге её нет.
        val open = debtRepository.findOpenForUser(userId).filter { it.debt.debtorId != it.debt.creditorId }
        val pendingSettlementPayees = debtRepository.findClaimedSettlementsForUser(userId)
        val people = open
            .groupBy { if (it.debt.debtorId == userId) it.creditor else it.debtor }
            .map { (person, debts) ->
                val owe = debts.filter { it.debt.debtorId == userId }.sumOf { it.debt.amountKopecks }
                val owed = debts.filter { it.debt.creditorId == userId }.sumOf { it.debt.amountKopecks }
                val settlementWaitsForMe = pendingSettlementPayees.any { it.payeeId == userId && it.payerId == person.id }
                val settlementWaitsForThem = pendingSettlementPayees.any { it.payerId == userId && it.payeeId == person.id }
                DebtCounterpartyDto(
                    user = mapper.toPersonDto(person),
                    balanceKopecks = owed - owe,
                    oweKopecks = owe,
                    owedKopecks = owed,
                    debtCount = debts.size,
                    nearestDueAt = debts.mapNotNull { it.debt.dueAt }.minOrNull(),
                    awaitingMyConfirmation = debts.count { it.debt.creditorId == userId && it.debt.status == DebtStatus.claimed && it.debt.settlementId == null } +
                        (if (settlementWaitsForMe) 1 else 0),
                    awaitingTheirConfirmation = debts.count { it.debt.debtorId == userId && it.debt.status == DebtStatus.claimed && it.debt.settlementId == null } +
                        (if (settlementWaitsForThem) 1 else 0)
                )
            }
            // Ждут моего ответа → ближайший срок → имя.
            .sortedWith(
                compareByDescending<DebtCounterpartyDto> { it.awaitingMyConfirmation > 0 }
                    .thenBy(nullsLast()) { it.nearestDueAt }
                    .thenBy { it.user.firstName }
            )
        return DebtsOverviewDto(
            oweKopecks = people.sumOf { it.oweKopecks },
            owedKopecks = people.sumOf { it.owedKopecks },
            awaitingMyConfirmation = people.sumOf { it.awaitingMyConfirmation },
            people = people
        )
    }

    @Transactional(readOnly = true)
    fun pair(userId: UUID, otherId: UUID): DebtPairDto {
        val now = OffsetDateTime.now()
        val open = debtRepository.findOpenBetween(userId, otherId)
        val other = open.firstOrNull()?.let { if (it.debt.debtorId == userId) it.creditor else it.debtor }
            ?: userRepository.findById(otherId)?.let {
                DebtPerson(it.id!!, it.firstName, it.lastName, it.telegramUsername, it.avatarUrl)
            }
            ?: throw NotFoundException("Пользователь не найден")
        // Внутри группы ближайший срок сверху, NULL последними (AC-14); репозиторий уже так сортирует.
        val owe = open.filter { it.debt.debtorId == userId }.map { mapper.toDto(it, now) }
        val owed = open.filter { it.debt.creditorId == userId }.map { mapper.toDto(it, now) }
        val oweKopecks = owe.sumOf { it.amountKopecks }
        val owedKopecks = owed.sumOf { it.amountKopecks }
        return DebtPairDto(
            user = mapper.toPersonDto(other),
            owe = owe,
            owed = owed,
            oweKopecks = oweKopecks,
            owedKopecks = owedKopecks,
            balanceKopecks = owedKopecks - oweKopecks,
            settlement = debtRepository.findClaimedSettlementBetween(userId, otherId)?.let(mapper::toSettlementDto)
        )
    }
}
