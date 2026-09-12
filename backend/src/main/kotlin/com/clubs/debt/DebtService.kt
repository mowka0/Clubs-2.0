package com.clubs.debt

import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.common.util.UploadedImageUrls
import com.clubs.generated.jooq.enums.DebtStatus
import com.clubs.generated.jooq.enums.SkladchinaKind
import com.clubs.skladchina.SkladchinaLifecycleService
import com.clubs.skladchina.SkladchinaProgressChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Переходы одиночного долга — таблица docs/modules/skladchina-v3.md § 2.2. Кто может: должник —
 * «Оплачу позже», «Отдал», «Отменить», чек, заметка; получатель — «Получил», «Не получил»,
 * «Простить», «Изменить сумму». Долг в составе сальдо пары одиночными кнопками не трогается.
 */
@Service
class DebtService(
    private val debtRepository: DebtRepository,
    private val lifecycleService: SkladchinaLifecycleService,
    private val eventPublisher: ApplicationEventPublisher,
    private val mapper: DebtMapper,
    @Value("\${s3.base-url:}") private val storageBaseUrl: String
) {
    private val log = LoggerFactory.getLogger(DebtService::class.java)

    @Transactional
    fun promise(debtId: UUID, callerId: UUID, date: LocalDate): DebtDto {
        val d = requireAsDebtor(debtId, callerId)
        requireStatus(d, DebtStatus.waiting, DebtStatus.promised)
        val today = LocalDate.now(MSK)
        if (date.isBefore(today)) throw ValidationException("Дата обещания уже прошла")
        if (date.isAfter(today.plusDays(MAX_PROMISE_DAYS))) throw ValidationException("Не дальше $MAX_PROMISE_DAYS дней")
        applied(debtRepository.promise(debtId, date))
        log.info("Debt promised: id={} debtor={} date={}", debtId, callerId, date)
        return refreshed(debtId, d.debt.skladchinaId)
    }

    @Transactional
    fun claim(debtId: UUID, callerId: UUID): DebtDto {
        val d = requireAsDebtor(debtId, callerId)
        requireStatus(d, DebtStatus.waiting, DebtStatus.promised)
        applied(debtRepository.claim(debtId, OffsetDateTime.now()))
        log.info("Debt claimed: id={} debtor={}", debtId, callerId)
        val updated = debtRepository.findWithContext(debtId)!!
        eventPublisher.publishEvent(DebtClaimedEvent(updated))
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(d.debt.skladchinaId))
        return mapper.toDto(updated)
    }

    @Transactional
    fun unclaim(debtId: UUID, callerId: UUID): DebtDto {
        val d = requireAsDebtor(debtId, callerId)
        requireStatus(d, DebtStatus.claimed)
        applied(debtRepository.unclaim(debtId))
        log.info("Debt unclaimed: id={} debtor={}", debtId, callerId)
        return refreshed(debtId, d.debt.skladchinaId)
    }

    /** «Получил»: из любого открытого состояния; без «Отдал» это наличные. Последний долг закрывает сбор. */
    @Transactional
    fun confirm(debtId: UUID, callerId: UUID): DebtDto {
        val d = requireAsCreditor(debtId, callerId)
        requireStatus(d, DebtStatus.waiting, DebtStatus.promised, DebtStatus.claimed)
        applied(debtRepository.confirm(debtId, OffsetDateTime.now()))
        log.info("Debt confirmed: id={} creditor={} debtor={}", debtId, callerId, d.debt.debtorId)
        val dto = refreshed(debtId, d.debt.skladchinaId)
        lifecycleService.maybeComplete(d.debt.skladchinaId)
        return dto
    }

    /** «Не получил»: обратно в waiting, должнику «приложите чек». У per_head после заказа — выбыл. */
    @Transactional
    fun reject(debtId: UUID, callerId: UUID, note: String?): DebtDto {
        val d = requireAsCreditor(debtId, callerId)
        requireStatus(d, DebtStatus.claimed)
        val now = OffsetDateTime.now()
        val dropped = d.skladchinaKind == SkladchinaKind.per_head && d.skladchinaOrderedAt != null
        if (dropped) {
            applied(debtRepository.drop(debtId, setOf(DebtStatus.claimed)))
        } else {
            applied(debtRepository.reject(debtId, note?.trim()?.takeIf { it.isNotEmpty() }, now))
        }
        log.info("Debt rejected: id={} creditor={} dropped={}", debtId, callerId, dropped)
        val updated = debtRepository.findWithContext(debtId)!!
        eventPublisher.publishEvent(DebtRejectedEvent(updated, dropped))
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(d.debt.skladchinaId))
        if (dropped) lifecycleService.maybeComplete(d.debt.skladchinaId)
        return mapper.toDto(updated)
    }

    @Transactional
    fun forgive(debtId: UUID, callerId: UUID): DebtDto {
        val d = requireAsCreditor(debtId, callerId)
        requireStatus(d, DebtStatus.waiting, DebtStatus.promised, DebtStatus.claimed)
        applied(debtRepository.forgive(debtId))
        log.info("Debt forgiven: id={} creditor={} debtor={}", debtId, callerId, d.debt.debtorId)
        val dto = refreshed(debtId, d.debt.skladchinaId)
        lifecycleService.maybeComplete(d.debt.skladchinaId)
        return dto
    }

    @Transactional
    fun changeAmount(debtId: UUID, callerId: UUID, amountKopecks: Long): DebtDto {
        val d = requireAsCreditor(debtId, callerId)
        if (d.skladchinaKind != SkladchinaKind.shared) throw ValidationException("Сумму можно менять только у сбора «Скинуться»")
        requireStatus(d, DebtStatus.waiting, DebtStatus.promised)
        if (amountKopecks > MAX_DEBT_KOPECKS) throw ValidationException("Сумма не может превышать ${MAX_DEBT_KOPECKS / 100} ₽")
        if (amountKopecks == d.debt.amountKopecks) return mapper.toDto(d)
        applied(debtRepository.changeAmount(debtId, amountKopecks))
        log.info("Debt amount changed: id={} {} -> {} by={}", debtId, d.debt.amountKopecks, amountKopecks, callerId)
        val updated = debtRepository.findWithContext(debtId)!!
        eventPublisher.publishEvent(DebtAmountChangedEvent(updated, d.debt.amountKopecks))
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(d.debt.skladchinaId))
        return mapper.toDto(updated)
    }

    @Transactional
    fun setReceipt(debtId: UUID, callerId: UUID, url: String): DebtDto {
        val d = requireAsDebtor(debtId, callerId)
        requireStatus(d, DebtStatus.waiting, DebtStatus.promised, DebtStatus.claimed)
        if (!UploadedImageUrls.isUploadedImageUrl(url.trim(), storageBaseUrl)) {
            throw ValidationException("Чек должен быть загружен через приложение")
        }
        applied(debtRepository.setReceipt(debtId, url.trim()))
        log.info("Debt receipt attached: id={} debtor={}", debtId, callerId)
        return mapper.toDto(debtRepository.findWithContext(debtId)!!)
    }

    @Transactional
    fun setNote(debtId: UUID, callerId: UUID, note: String): DebtDto {
        val d = requireAsDebtor(debtId, callerId)
        requireStatus(d, DebtStatus.waiting, DebtStatus.promised, DebtStatus.claimed)
        applied(debtRepository.setNote(debtId, note.trim()))
        return mapper.toDto(debtRepository.findWithContext(debtId)!!)
    }

    private fun requireAsDebtor(debtId: UUID, callerId: UUID): DebtWithContext {
        val d = debtRepository.findWithContext(debtId) ?: throw NotFoundException("Долг не найден")
        if (d.debt.debtorId != callerId) throw ForbiddenException("Это действие доступно только должнику")
        requireNotInSettlement(d)
        return d
    }

    private fun requireAsCreditor(debtId: UUID, callerId: UUID): DebtWithContext {
        val d = debtRepository.findWithContext(debtId) ?: throw NotFoundException("Долг не найден")
        if (d.debt.creditorId != callerId) throw ForbiddenException("Это действие доступно только получателю")
        if (d.debt.debtorId == d.debt.creditorId) throw ValidationException("Это ваша собственная доля")
        requireNotInSettlement(d)
        return d
    }

    private fun requireNotInSettlement(d: DebtWithContext) {
        if (d.debt.settlementId != null) throw ValidationException("Долг в составе сальдо пары — ответьте по сальдо целиком")
    }

    private fun requireStatus(d: DebtWithContext, vararg allowed: DebtStatus) {
        if (d.debt.status !in allowed) throw ValidationException("Для этого долга такое действие недоступно")
    }

    // 0 строк = долг ушёл из ожидаемого состояния между чтением и записью (второй тап, другая кнопка).
    private fun applied(rows: Int) {
        if (rows == 0) throw ConflictException("Долг уже изменился — обновите экран")
    }

    private fun refreshed(debtId: UUID, skladchinaId: UUID): DebtDto {
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
        return mapper.toDto(debtRepository.findWithContext(debtId)!!)
    }

    companion object {
        private val MSK: ZoneId = ZoneId.of("Europe/Moscow")
        // «Оплачу позже» не дальше трёх месяцев: обещание на год — не обещание.
        private const val MAX_PROMISE_DAYS = 90L
        // Верхняя граница суммы долга: гигиена, не защита.
        private const val MAX_DEBT_KOPECKS = 10_000_000L
    }
}
