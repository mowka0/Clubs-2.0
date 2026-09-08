package com.clubs.skladchina

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Тики сверки оплат (V89). Сбор больше не закрывается по дедлайну сам — вместо этого шедулер
 * зовёт организатора свести сбор, а если тот не пришёл за неделю, сводит за него и решает
 * отложенные исходы. Ошибка по одной складчине логируется и не рвёт батч.
 */
@Service
class SkladchinaScheduler(
    private val skladchinaRepository: SkladchinaRepository,
    private val lifecycleService: SkladchinaLifecycleService
) {
    private val log = LoggerFactory.getLogger(SkladchinaScheduler::class.java)

    /** Наступил срок (или все уже ответили), а решения есть не по всем → DM организатору «сведите сбор». */
    @Scheduled(fixedDelayString = CONFIRMATION_POLL_MS)
    fun requestPaymentConfirmations() {
        val ready = skladchinaRepository.findNeedingConfirmationRequest(OffsetDateTime.now())
        if (ready.isEmpty()) return
        log.info("Requesting payment confirmation for {} skladchinas", ready.size)
        ready.forEach { s ->
            runSafely("request confirmation", s.id) { lifecycleService.requestConfirmation(s.id) }
        }
    }

    /**
     * Организатор не свёл сбор за [SkladchinaConfirmationPolicy.ABANDONED_CONFIRMATION_DAYS] дней
     * после дедлайна — сводим за него: заявкам верим, молчание стоит −40.
     */
    @Scheduled(fixedDelayString = CONFIRMATION_POLL_MS)
    fun settleAbandoned() {
        val cutoff = OffsetDateTime.now().minusDays(SkladchinaConfirmationPolicy.ABANDONED_CONFIRMATION_DAYS)
        val abandoned = skladchinaRepository.findAbandonedActive(cutoff)
        if (abandoned.isEmpty()) return
        log.info("Auto-settling {} skladchinas the organizer never closed", abandoned.size)
        abandoned.forEach { s ->
            runSafely("auto-settle", s.id) { lifecycleService.autoSettleAbandoned(s.id) }
        }
    }

    /**
     * Отложенные исходы: у отклонённой оплаты истекло окно на чек (−40), а спор, который
     * организатор не разобрал за [SkladchinaConfirmationPolicy.DISPUTE_RESOLUTION_DAYS] дней,
     * закрывается нейтрально — участник своё сделал, прислав чек.
     */
    @Scheduled(fixedDelayString = CONFIRMATION_POLL_MS)
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
        // Период всех трёх тиков сверки. Вынесен в конфиг тем же приёмом, что
        // skladchinas.reminder-poll-ms: staging ужимает его до секунд, чтобы сквозной тест
        // «срок вышел → свели → добили исходы» не ждал по 10 минут на каждом шаге.
        private const val CONFIRMATION_POLL_MS = "\${skladchinas.confirmation-poll-ms:600000}"
    }
}
