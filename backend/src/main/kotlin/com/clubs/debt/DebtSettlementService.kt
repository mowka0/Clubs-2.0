package com.clubs.debt

import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.DebtSettlementStatus
import com.clubs.skladchina.SkladchinaLifecycleService
import com.clubs.skladchina.SkladchinaProgressChangedEvent
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Сальдо пары (§ 2.3): «Отдал Σ» разом по всем открытым долгам двух людей, подтверждает получатель
 * разницы. Между разными людьми ничего не схлопывается.
 */
@Service
class DebtSettlementService(
    private val debtRepository: DebtRepository,
    private val userRepository: UserRepository,
    private val lifecycleService: SkladchinaLifecycleService,
    private val queryService: DebtQueryService,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(DebtSettlementService::class.java)

    @Transactional
    fun settle(callerId: UUID, otherId: UUID): DebtPairDto {
        if (callerId == otherId) throw ValidationException("Сальдо считается с другим человеком")
        if (debtRepository.findClaimedSettlementBetween(callerId, otherId) != null) {
            throw ValidationException("Сальдо уже ждёт подтверждения")
        }
        val open = debtRepository.findOpenBetween(callerId, otherId)
        val iOwe = open.filter { it.debt.debtorId == callerId }.sumOf { it.debt.amountKopecks }
        val owedToMe = open.filter { it.debt.creditorId == callerId }.sumOf { it.debt.amountKopecks }
        val balance = iOwe - owedToMe
        if (balance <= 0) throw ValidationException("По сальдо платите не вы")

        val now = OffsetDateTime.now()
        val settlement = debtRepository.createSettlement(callerId, otherId, balance, now)
        val attached = debtRepository.attachToSettlement(open.map { it.debt.id }, settlement.id, now)
        if (attached != open.size) throw ConflictException("Долги пары изменились — обновите экран")
        log.info("Settlement claimed: id={} payer={} payee={} amount={} debts={}", settlement.id, callerId, otherId, balance, attached)

        val payerName = userRepository.findById(callerId)?.firstName ?: "Участник"
        eventPublisher.publishEvent(SettlementClaimedEvent(settlement, payerName, open.size, now))
        open.map { it.debt.skladchinaId }.distinct().forEach { eventPublisher.publishEvent(SkladchinaProgressChangedEvent(it)) }
        return queryService.pair(callerId, otherId)
    }

    /** «Получил Σ»: все долги сальдо → received, встречные долги получателя тоже зачтены. */
    @Transactional
    fun confirm(settlementId: UUID, callerId: UUID): DebtPairDto {
        val settlement = requireClaimedAsPayee(settlementId, callerId)
        val now = OffsetDateTime.now()
        if (debtRepository.resolveSettlement(settlementId, DebtSettlementStatus.received, now) == 0) {
            throw ConflictException("Сальдо уже разобрано — обновите экран")
        }
        val closed = debtRepository.confirmBySettlement(settlementId, now)
        log.info("Settlement confirmed: id={} payee={} debts={}", settlementId, callerId, closed.size)
        closed.map { it.skladchinaId }.distinct().forEach {
            eventPublisher.publishEvent(SkladchinaProgressChangedEvent(it))
            lifecycleService.maybeComplete(it)
        }
        return queryService.pair(callerId, settlement.payerId)
    }

    /** «Не получил» по сальдо: все долги снова открыты. */
    @Transactional
    fun reject(settlementId: UUID, callerId: UUID): DebtPairDto {
        val settlement = requireClaimedAsPayee(settlementId, callerId)
        val now = OffsetDateTime.now()
        if (debtRepository.resolveSettlement(settlementId, DebtSettlementStatus.rejected, now) == 0) {
            throw ConflictException("Сальдо уже разобрано — обновите экран")
        }
        val reopened = debtRepository.rejectBySettlement(settlementId, settlement.payerId, now)
        log.info("Settlement rejected: id={} payee={} debts={}", settlementId, callerId, reopened.size)
        reopened.map { it.skladchinaId }.distinct().forEach { eventPublisher.publishEvent(SkladchinaProgressChangedEvent(it)) }
        return queryService.pair(callerId, settlement.payerId)
    }

    private fun requireClaimedAsPayee(settlementId: UUID, callerId: UUID): DebtSettlement {
        val s = debtRepository.findSettlement(settlementId) ?: throw NotFoundException("Сальдо не найдено")
        if (s.payeeId != callerId) throw ForbiddenException("Подтверждает получатель по сальдо")
        if (s.status != DebtSettlementStatus.claimed) throw ValidationException("Сальдо уже разобрано")
        return s
    }
}
