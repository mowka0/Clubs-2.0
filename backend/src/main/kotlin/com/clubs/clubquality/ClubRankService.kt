package com.clubs.clubquality

import com.clubs.reputation.LedgerReadPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Сервис скрытого ранга L3. Собирает сырые сигналы + входы credibility, ВЕСЬ скоринг делегирует
 * [ClubRankPolicy] и сохраняет результат. Леджер репутации читает только через [LedgerReadPort]
 * (никогда через Trust-тип) — так структурный инвариант *L3 клуба ≠ средний Trust участников*
 * выполняется по построению. Логирует только количества, никогда не очки (очко в логах —
 * утечка расклада, см. security.md).
 */
@Service
class ClubRankService(
    private val clubRankRepository: ClubRankRepository,
    private val ledgerReadPort: LedgerReadPort,
    // Deploy-флаг бейджа «★ Топ-5 в категории»: пока выключен — badgedAmong всегда пуст.
    @Value("\${club.rank.badge-enabled:false}") private val badgeEnabled: Boolean,
) {

    private val log = LoggerFactory.getLogger(ClubRankService::class.java)

    /** Полный пересчёт ранга всех активных клубов (единица работы шедулера). */
    @Transactional
    fun recomputeAll() {
        val now = OffsetDateTime.now()
        val signals = clubRankRepository.findRankSignals(now)
        if (signals.isEmpty()) return

        val userIds = signals.flatMap { it.core + it.payers + it.renewers + it.voters }
            .map { it.userId }.toSet()
        val profiles = clubRankRepository.findUserProfiles(userIds)
        val footprint = ledgerReadPort.footprintByUser(userIds)
        val credibilityInputs = profiles.mapValues { (userId, p) ->
            CredibilityInput(userId, p.createdAt, p.hasUsername, p.hasAvatar, footprint[userId].orEmpty())
        }

        val ranks = signals.map { ClubRankPolicy.computeRank(it, credibilityInputs, now) }
        clubRankRepository.upsertRanks(ranks)
        log.info("Club-rank recompute: {} clubs, {} ranked", ranks.size, ranks.count { it.isRanked })
    }

    /**
     * Какие из переданных клубов заслуживают «★ Топ-5 в категории». Пусто, пока не включён
     * deploy-флаг И не пройден глобальный порог ранга (оба проверяются в
     * [ClubRankPolicy.topInCategory]). Это ЕДИНСТВЕННОЕ, что ранг вообще наружу отдаёт —
     * булево множество, никогда не очки.
     */
    @Transactional(readOnly = true)
    fun badgedAmong(clubIds: Collection<UUID>): Set<UUID> {
        // Ранний выход экономит чтение ranked-клубов на частом пути (флаг выключен / пустой список).
        // Флаг авторитетен и внутри topInCategory — двойная проверка намеренная, defence-in-depth.
        if (!badgeEnabled || clubIds.isEmpty()) return emptySet()
        val badged = ClubRankPolicy.topInCategory(clubRankRepository.findRankedClubs(), badgeEnabled)
        return badged.intersect(clubIds.toSet())
    }
}
