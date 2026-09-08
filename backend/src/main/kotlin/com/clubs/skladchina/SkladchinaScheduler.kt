package com.clubs.skladchina

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Тики сверки оплат (V89). Сбор больше не закрывается по дедлайну сам — вместо этого шедулер
 * зовёт организатора сверить деньги, а если тот не пришёл, закрывает сбор нейтрально и решает
 * за него отложенные исходы. Ошибка по одной складчине логируется и не рвёт батч.
 */
@Service
class SkladchinaScheduler(
    private val skladchinaRepository: SkladchinaRepository,
    private val lifecycleService: SkladchinaLifecycleService
) {
    private val log = LoggerFactory.getLogger(SkladchinaScheduler::class.java)

    /** «Все ответили» или наступил срок → DM организатору «сверьте деньги» (ровно один раз на сбор). */
    @Scheduled(fixedDelay = SCHEDULER_PERIOD_MS)
    fun requestPaymentConfirmations() {
        val ready = skladchinaRepository.findNeedingConfirmationRequest(OffsetDateTime.now())
        if (ready.isEmpty()) return
        log.info("Requesting payment confirmation for {} skladchinas", ready.size)
        ready.forEach { s ->
            runSafely("request confirmation", s.id) { lifecycleService.requestConfirmation(s.id) }
        }
    }

    /**
     * Срок вышел, а все заявки организатор разобрал — закрываем: ждать больше нечего, молчуны
     * получают своё. Обычно сбор закрывается сам в момент последнего решения организатора
     * ([SkladchinaLifecycleService.maybeCloseWhenSettled]); этот тик добирает случай, когда
     * последними остались не ответившие.
     */
    @Scheduled(fixedDelay = SCHEDULER_PERIOD_MS)
    fun closeSettledAfterDeadline() {
        val settled = skladchinaRepository.findSettledAfterDeadline(OffsetDateTime.now())
        if (settled.isEmpty()) return
        log.info("Closing {} skladchinas whose claims are all settled", settled.size)
        settled.forEach { s ->
            runSafely("close settled", s.id) {
                lifecycleService.closeInternal(s.id, closedBy = null, manualClose = false)
            }
        }
    }

    /**
     * Организатор не пришёл сверять деньги за
     * [SkladchinaConfirmationPolicy.ABANDONED_CONFIRMATION_DAYS] дней после дедлайна — закрываем
     * нейтрально: ни плюсов, ни минусов никому.
     */
    @Scheduled(fixedDelay = SCHEDULER_PERIOD_MS)
    fun closeAbandoned() {
        val cutoff = OffsetDateTime.now().minusDays(SkladchinaConfirmationPolicy.ABANDONED_CONFIRMATION_DAYS)
        val abandoned = skladchinaRepository.findAbandonedActive(cutoff)
        if (abandoned.isEmpty()) return
        log.info("Neutrally closing {} skladchinas the organizer never confirmed", abandoned.size)
        abandoned.forEach { s ->
            runSafely("neutral close", s.id) { lifecycleService.neutrallyCloseAbandoned(s.id) }
        }
    }

    /**
     * Отложенные исходы: у отклонённой оплаты истекло окно на чек (−40), а спор, который
     * организатор не разобрал за [SkladchinaConfirmationPolicy.DISPUTE_RESOLUTION_DAYS] дней,
     * закрывается нейтрально — участник своё сделал, прислав чек.
     */
    @Scheduled(fixedDelay = SCHEDULER_PERIOD_MS)
    fun finalizeOverduePaymentOutcomes() {
        val now = OffsetDateTime.now()

        val unchallenged = skladchinaRepository.findRejectedPaymentsDueForPenalty(
            now.minusHours(SkladchinaConfirmationPolicy.RECEIPT_WINDOW_HOURS)
        )
        unchallenged.forEach { key ->
            runSafely("finalize rejected payment", key.skladchinaId) {
                lifecycleService.applyDeferredReputation(key.skladchinaId, key.userId)
            }
        }

        val staleDisputes = skladchinaRepository.findStaleDisputes(
            now.minusDays(SkladchinaConfirmationPolicy.DISPUTE_RESOLUTION_DAYS)
        )
        staleDisputes.forEach { key ->
            runSafely("release stale dispute", key.skladchinaId) {
                // released → financeKind = null, поэтому строки в леджере не появится.
                skladchinaRepository.releaseParticipant(key.skladchinaId, key.userId)
                lifecycleService.applyDeferredReputation(key.skladchinaId, key.userId)
            }
        }

        if (unchallenged.isNotEmpty() || staleDisputes.isNotEmpty()) {
            log.info("Finalized overdue payment outcomes: penalties={} releasedDisputes={}",
                unchallenged.size, staleDisputes.size)
        }
    }

    private fun runSafely(action: String, skladchinaId: java.util.UUID, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.error("Failed to $action for skladchina $skladchinaId", e)
        }
    }

    companion object {
        private const val SCHEDULER_PERIOD_MS = 600_000L  // 10 минут
    }
}
