package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.user.QuestFlags
import com.clubs.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Read-side вывод XP / уровня / бейджей (P1b). Считает панель геймификации на уровне аккаунта
 * ПРИ ЧТЕНИИ из ledger'а (один запрос [ReputationRepository.findTrustOutcomesByUser], тот же
 * источник, что и у [TrustService]). Чистая логика — веса, пороги уровней, предикаты бейджей —
 * живёт в [XpPolicy]; этот класс только агрегирует ledger в [XpPolicy.XpStats] и собирает DTO.
 *
 * XP вознаграждает только участие и никогда не уменьшается; анти-фарм «владелец в своём клубе»
 * наследуется (там нет ledger-строк → нет и XP). Trust по клубу пересчитывается здесь только
 * ради trust-бейджей.
 */
@Service
class XpService(
    private val reputationRepository: ReputationRepository,
    private val userRepository: UserRepository
) {

    @Transactional(readOnly = true)
    fun getGamification(userId: UUID, now: OffsetDateTime = OffsetDateTime.now()): GamificationDto {
        val stats = statsForOutcomes(reputationRepository.findTrustOutcomesByUser(userId), now)
        val quest = questFor(userRepository.findQuestFlags(userId))
        val xp = XpPolicy.totalXp(stats, quest)
        val idx = XpPolicy.levelIndexFor(xp)
        val isMax = idx == XpPolicy.LEVEL_NAMES.lastIndex
        return GamificationDto(
            xp = xp,
            level = idx + 1,
            levelName = XpPolicy.LEVEL_NAMES[idx],
            nextLevelName = if (isMax) null else XpPolicy.LEVEL_NAMES[idx + 1],
            xpIntoLevel = xp - XpPolicy.levelThreshold(idx),
            xpSpanToNext = if (isMax) null else XpPolicy.levelThreshold(idx + 1) - XpPolicy.levelThreshold(idx),
            badges = XpPolicy.badgesFor(stats, quest).map { BadgeDto(it.id, it.name, it.family.name) },
            quest = ProfileQuestDto(
                cityDone = quest.city,
                interestsDone = quest.interests,
                bioDone = quest.bio,
                completed = quest.completed
            )
        )
    }

    /** Вехи-метки → квест-флаги. null (пользователя нет) = пустой квест. */
    fun questFor(flags: QuestFlags?): XpPolicy.ProfileQuest =
        if (flags == null) XpPolicy.ProfileQuest.NONE
        else XpPolicy.ProfileQuest(
            city = flags.cityAt != null,
            interests = flags.interestsAt != null,
            bio = flags.bioAt != null
        )

    /**
     * Глобальный уровень (имя + индекс с 0) из заранее полученного списка outcome — проекция,
     * показываемая ДРУГИМ (например пилл заявителя на карточке рассмотрения), без XP/прогресса/
     * бейджей. Построена на outcome, чтобы batch-путь заявителей ([ApplicantSignalService]) делал
     * один запрос сразу для многих юзеров. Профиль-квест передаётся снаружи (батч-вехи) —
     * уровень «для других» обязан совпадать с self-уровнем (см. profile-quest.md AC-6).
     */
    fun levelForOutcomes(
        outcomes: List<ClubLedgerOutcome>,
        quest: XpPolicy.ProfileQuest,
        now: OffsetDateTime = OffsetDateTime.now()
    ): LevelInfo {
        val idx = XpPolicy.levelIndexFor(XpPolicy.totalXp(statsForOutcomes(outcomes, now), quest))
        return LevelInfo(level = idx + 1, name = XpPolicy.LEVEL_NAMES[idx], index = idx)
    }

    private fun statsForOutcomes(outcomes: List<ClubLedgerOutcome>, now: OffsetDateTime): XpPolicy.XpStats {
        var ironclad = 0
        var spontaneous = 0
        var skladchinaPaid = 0
        var distinctKeptClubs = 0
        var reliableClubs = 0
        var maxTrustWithRecord = 0

        outcomes
            .groupBy { it.clubId }
            .forEach { (_, rows) ->
                var clubHasKept = false
                rows.forEach { o ->
                    when (o.kind) {
                        ReputationKind.ironclad -> { ironclad++; clubHasKept = true }
                        ReputationKind.spontaneous -> { spontaneous++; clubHasKept = true }
                        ReputationKind.skladchina_paid -> { skladchinaPaid++; clubHasKept = true }
                        else -> Unit
                    }
                }
                if (clubHasKept) distinctKeptClubs++
                // Trust-бейджи учитывают только клубы с показанной историей (тот же гейт, что и в UI).
                if (rows.size >= ReputationPolicy.MIN_OUTCOMES_FOR_DISPLAY) {
                    val trust = TrustPolicy.perClubTrust(rows.map { TrustPolicy.Outcome(it.kind, it.occurredAt) }, now)
                    if (XpPolicy.isReliable(trust)) reliableClubs++
                    if (trust > maxTrustWithRecord) maxTrustWithRecord = trust
                }
            }

        return XpPolicy.XpStats(
            ironcladCount = ironclad,
            spontaneousCount = spontaneous,
            skladchinaPaidCount = skladchinaPaid,
            distinctKeptClubs = distinctKeptClubs,
            reliableClubs = reliableClubs,
            maxTrustWithRecord = maxTrustWithRecord
        )
    }
}

/** Проекция уровня для «других»: уровень с 1, его имя и индекс с 0 (для тиринга). */
data class LevelInfo(val level: Int, val name: String, val index: Int)
