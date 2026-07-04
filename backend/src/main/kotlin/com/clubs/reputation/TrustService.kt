package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Read-side вычисление P1b Trust. Считает per-club Trust 0-100 и агрегат по всей истории
 * ("надёжен в N из M клубов") ПРИ ЧТЕНИИ из леджера через [TrustPolicy].
 *
 * Decay зависит от текущего времени, поэтому ничего здесь не кэшируется — occurred_at читается
 * заново при каждом вызове. Отделён от [ReputationService] (который владеет write-side пайплайном
 * леджера + рекомпутом): один класс = одна причина для изменения.
 */
@Service
class TrustService(
    private val reputationRepository: ReputationRepository
) {

    /**
     * Per-club Trust + вид глобального агрегата по всей истории для одного пользователя. Гейт
     * отображения (ReputationPolicy.isShown(outcomeCount)) и метаданные клуба (имя/аватар/роль)
     * применяются вызывающим на границе DTO — это возвращает сырые вычисленные числа по клубам.
     */
    @Transactional(readOnly = true)
    fun computeForUser(userId: UUID, now: OffsetDateTime = OffsetDateTime.now()): UserTrust {
        val outcomes = reputationRepository.findTrustOutcomesByUser(userId)
        val clubs = outcomes
            .groupBy { it.clubId }
            .map { (clubId, rows) ->
                ClubTrust(
                    clubId = clubId,
                    trust = TrustPolicy.perClubTrust(rows.map { TrustPolicy.Outcome(it.kind, it.occurredAt) }, now),
                    outcomeCount = rows.size,
                    lastOccurredAt = rows.maxOf { it.occurredAt }
                )
            }
        return UserTrust(perClub = clubs, global = globalForOutcomes(outcomes, now))
    }

    /**
     * Агрегат по всей истории ("надёжен в N из M клубов") из заранее загруженного списка исходов.
     * Единственное место, вычисляющее [TrustPolicy.GlobalTrust] из исходов — используется и своим
     * обзором ([computeForUser]), и батчевым сигналом для заявителей ([ApplicantSignalService]),
     * который загружает данные один раз на много пользователей и потому не должен запрашивать
     * повторно на каждого.
     */
    fun globalForOutcomes(
        outcomes: List<ClubLedgerOutcome>,
        now: OffsetDateTime = OffsetDateTime.now()
    ): TrustPolicy.GlobalTrust {
        val standings = outcomes.groupBy { it.clubId }.map { (_, rows) ->
            TrustPolicy.ClubStanding(
                trust = TrustPolicy.perClubTrust(rows.map { TrustPolicy.Outcome(it.kind, it.occurredAt) }, now),
                outcomeCount = rows.size,
                lastOccurredAt = rows.maxOf { it.occurredAt }
            )
        }
        return TrustPolicy.global(standings, now)
    }

    /**
     * Per-member Trust для каждого участника клуба, у которого есть исходы в леджере (один батч-запрос,
     * без N+1). Участники без исходов отсутствуют в map; вызывающий рендерит их как "Новичок".
     */
    @Transactional(readOnly = true)
    fun trustForClubMembers(clubId: UUID, now: OffsetDateTime = OffsetDateTime.now()): Map<UUID, Int> =
        reputationRepository.findClubMemberOutcomes(clubId)
            .groupBy { it.userId }
            .mapValues { (_, rows) ->
                TrustPolicy.perClubTrust(rows.map { TrustPolicy.Outcome(it.kind, it.occurredAt) }, now)
            }

    /**
     * Per-club репутация одного участника для карточки участника — Trust + влияющая на репутацию
     * история складчин — из ОДНОГО чтения леджера. null, если у него нет исходов в леджере по клубу.
     * Оба кольца берутся из одного запроса: без повторного скана того же леджера (user, club).
     */
    @Transactional(readOnly = true)
    fun clubSummary(userId: UUID, clubId: UUID, now: OffsetDateTime = OffsetDateTime.now()): ClubReputationSummary? {
        val outcomes = reputationRepository.findTrustOutcomesByUser(userId).filter { it.clubId == clubId }
        if (outcomes.isEmpty()) return null
        return ClubReputationSummary(
            trust = TrustPolicy.perClubTrust(outcomes.map { TrustPolicy.Outcome(it.kind, it.occurredAt) }, now),
            skladchinaPaid = outcomes.count { it.kind == ReputationKind.skladchina_paid },
            skladchinaTotal = outcomes.count {
                it.kind == ReputationKind.skladchina_paid || it.kind == ReputationKind.skladchina_expired
            }
        )
    }
}

/**
 * Per-club репутация одного участника в том виде, в каком её показывает карточка участника, вся
 * из одного чтения леджера.
 *  - trust          = per-club Trust 0-100.
 *  - skladchinaPaid / skladchinaTotal = влияющая на репутацию история складчин (paid / paid+expired).
 */
data class ClubReputationSummary(
    val trust: Int,
    val skladchinaPaid: Int,
    val skladchinaTotal: Int
)

/** Вычисленный Trust пользователя в одном клубе. `trust` присутствует всегда; гейт отображения — на уровне UI. */
data class ClubTrust(
    val clubId: UUID,
    val trust: Int,
    val outcomeCount: Int,
    val lastOccurredAt: OffsetDateTime
)

data class UserTrust(
    val perClub: List<ClubTrust>,
    val global: TrustPolicy.GlobalTrust
)
