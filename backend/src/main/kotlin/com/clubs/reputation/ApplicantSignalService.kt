package com.clubs.reputation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Кросс-клубовый сигнал заявителя для экрана рассмотрения организатором: глобальный агрегат
 * "надёжен в N из M клубов" плюс глобальная проекция уровня (имя + tier). Вычисляется ON READ
 * из ledger одним batch-запросом на всех заявителей, дальше делегируется каноническим
 * [TrustService.globalForOutcomes] и [XpService.levelForOutcomes] — без N+1 на заявителя,
 * без дублирования формулы Trust/XP.
 *
 * По конструкции слеп к владельцу: в ledger нет строк для собственного клуба владельца
 * (анти-фарм правило 1), поэтому этот сигнал отражает историю УЧАСТНИКА, а не опыт организатора.
 * Карточка рассмотрения соответственно называет это «Активность на платформе».
 */
@Service
class ApplicantSignalService(
    private val reputationRepository: ReputationRepository,
    private val trustService: TrustService,
    private val xpService: XpService
) {

    @Transactional(readOnly = true)
    fun signalsFor(
        userIds: Collection<UUID>,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Map<UUID, ApplicantSignal> {
        if (userIds.isEmpty()) return emptyMap()
        val outcomesByUser = reputationRepository.findOutcomesByUserIds(userIds)
        return userIds.associateWith { userId ->
            val outcomes = outcomesByUser[userId].orEmpty()
            val global = trustService.globalForOutcomes(outcomes, now)
            val level = xpService.levelForOutcomes(outcomes, now)
            ApplicantSignal(
                reliableClubs = global.reliableClubs,
                trackRecordClubs = global.trackRecordClubs,
                level = level.level,
                levelName = level.name,
                levelTier = tierFor(level.index)
            )
        }
    }

    /** Индекс уровня (с 0) → tier пилюли. Top tier = Столп сообщества/Легенда/Амбассадор (золотая пилюля). */
    private fun tierFor(levelIndex: Int): String = when {
        levelIndex >= 7 -> "top"
        levelIndex >= 3 -> "mid"
        else -> "base"
    }
}

/**
 * Кросс-клубовая репутация заявителя, как её показывает карточка рассмотрения организатора:
 *  - reliableClubs / trackRecordClubs — донат "N из M" (клубы, где Trust ≥ reliable, из клубов
 *    с показанной историей). 0/0, если у заявителя ещё нет истории.
 *  - level / levelName — глобальный геймификационный уровень (проекция «для других»).
 *  - levelTier — "base" | "mid" | "top" для цвета пилюли.
 */
data class ApplicantSignal(
    val reliableClubs: Int,
    val trackRecordClubs: Int,
    val level: Int,
    val levelName: String,
    val levelTier: String
) {
    companion object {
        /** Значение по умолчанию для заявителя без записей в ledger: нет истории, уровень 1 (Гость). */
        val EMPTY = ApplicantSignal(
            reliableClubs = 0,
            trackRecordClubs = 0,
            level = 1,
            levelName = XpPolicy.LEVEL_NAMES.first(),
            levelTier = "base"
        )
    }
}
