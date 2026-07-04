package com.clubs.clubquality

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Периодический пересчёт L3. Ранг — медленно меняющийся скрытый сигнал, поэтому полного пересчёта
 * раз в 6 часов достаточно (тривиально дёшево на небольшом проде) — событийная инкрементальность
 * не нужна (YAGNI). Зеркалит [com.clubs.reputation.ReputationScheduler]: ошибка логируется,
 * следующий тик повторит попытку.
 *
 * Первый запуск — через 5 минут после старта (не полные 6ч): каждый продакшен-редеплой
 * перезапускает приложение и сбрасывает таймер, поэтому initial delay в 6ч означал, что джоба
 * почти никогда не успевала сработать между частыми деплоями. Короткая начальная задержка
 * гарантирует пересчёт вскоре после каждого старта, но всё же даёт приложению сначала прогреться
 * (пул БД, кэши). Установившийся интервал 6ч между запусками не меняется.
 */
@Component
class ClubRankScheduler(private val clubRankService: ClubRankService) {

    private val log = LoggerFactory.getLogger(ClubRankScheduler::class.java)

    @Scheduled(fixedDelay = 21_600_000, initialDelay = 300_000) // первый запуск через 5 мин после старта, далее каждые 6 часов
    fun recompute() {
        try {
            clubRankService.recomputeAll()
        } catch (e: Exception) {
            log.error("Club-rank recompute failed", e)
        }
    }
}
