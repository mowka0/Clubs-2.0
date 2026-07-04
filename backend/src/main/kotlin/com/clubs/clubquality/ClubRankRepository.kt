package com.clubs.clubquality

import java.time.OffsetDateTime
import java.util.UUID

/** Устойчивые к манипуляциям организатором входные данные профиля аккаунта для веса
 *  доверия L3 (половина, не связанная с footprint). */
data class UserProfile(
    val userId: UUID,
    val createdAt: OffsetDateTime,
    val hasUsername: Boolean,
    val hasAvatar: Boolean,
)

interface ClubRankRepository {

    /**
     * Собирает сырые L3-сигналы по каждому активному клубу (групповые запросы, без N+1). Каждый
     * список distinct-аккаунтов уже отфильтрован по признаку «сформирован участниками» и с
     * исключением владельца прямо в запросе — репозиторий только формирует данные, не считает
     * скор. [now] детерминированно ограничивает все окна чтения.
     */
    fun findRankSignals(now: OffsetDateTime): List<ClubRankSignals>

    /** Профильная половина входных данных доверия для указанных пользователей (footprint-половина
     *  берётся из репутации [com.clubs.reputation.LedgerReadPort]). Пустой вход → пустой выход. */
    fun findUserProfiles(userIds: Collection<UUID>): Map<UUID, UserProfile>

    /** Идемпотентный upsert пересчитанных рангов (`ON CONFLICT (club_id) DO UPDATE`). */
    fun upsertRanks(ranks: List<ClubRank>)

    /** Все клубы с текущим рангом — входное множество для расчёта бейджа "★ Топ-5 в категории". */
    fun findRankedClubs(): List<RankedClub>
}
