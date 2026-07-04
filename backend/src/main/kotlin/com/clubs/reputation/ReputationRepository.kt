package com.clubs.reputation

import java.util.UUID

interface ReputationRepository {

    // --- Чтение (используется MemberService, peer-signal) ---

    fun findByUserAndClub(userId: UUID, clubId: UUID): Reputation?

    /**
     * Кросс-клубовый агрегат репутации для пачки юзеров (один SQL-запрос).
     * Возвращает `userId → агрегат`. Юзеры, отсутствующие в `user_club_reputation`,
     * отсутствуют и в мапе; вызывающий код по умолчанию берёт [PeerStatsAggregate.EMPTY].
     * Пустой вход → emptyMap (без обращения к БД).
     */
    fun aggregateByUserIds(userIds: Collection<UUID>): Map<UUID, PeerStatsAggregate>

    /**
     * Все ledger-исходы юзера по ВСЕМ клубам (включая покинутые) — источник для P1b Trust
     * и глобального агрегата за всю историю, читаемый напрямую. Без фильтра по активному
     * членству / is_active: штраф из покинутого клуба остаётся в ledger и всё равно должен
     * учитываться в глобальном показателе.
     */
    fun findTrustOutcomesByUser(userId: UUID): List<ClubLedgerOutcome>

    /**
     * Batch-вариант [findTrustOutcomesByUser]: все ledger-исходы для набора юзеров одним
     * запросом, сгруппированные `userId → outcomes`. Питает кросс-клубовый сигнал заявителя
     * (уровень + «N из M») для инбокса организатора без N+1 по заявителям. Юзеры без исходов
     * отсутствуют в мапе; пустой вход → emptyMap (без обращения к БД).
     */
    fun findOutcomesByUserIds(userIds: Collection<UUID>): Map<UUID, List<ClubLedgerOutcome>>

    /**
     * Все ledger-исходы в одном клубе, одна строка на пару (юзер, исход) — batch-источник
     * для per-member Trust в списке участников клуба (избегает N+1 по участникам).
     */
    fun findClubMemberOutcomes(clubId: UUID): List<MemberLedgerOutcome>

    // --- Ledger pipeline (сторона записи, источник истины) ---

    /**
     * Атомарно захватывает событие для обработки репутации:
     * `UPDATE events SET reputation_processed=true WHERE id=? AND NOT reputation_processed`.
     * Возвращает true, только если именно этот вызов выиграл захват (строка была обновлена).
     * Делает event-листенер и часовой polling взаимоисключающими — проигравший ничего не делает.
     */
    fun claimEvent(eventId: UUID): Boolean

    /** Финализированные+отмеченные события, ещё не обработанные для репутации (страховочный poll). */
    fun findPendingFinalizedEventIds(): List<UUID>

    /** Id клуба + id владельца + дата-время события, для построения строк ledger посещаемости. */
    fun findEventContext(eventId: UUID): EventReputationContext?

    /** Подтверждённые отклики на событие (только они дают строку ledger). */
    fun findConfirmedResponses(eventId: UUID): List<ConfirmedResponse>

    /** Добавляет строки ledger, пропуская уже существующие (ON CONFLICT DO NOTHING). */
    fun appendLedgerIfAbsent(entries: List<LedgerEntry>)

    /**
     * Пересчитывает кэш-строку user_club_reputation для (юзер, клуб) чисто на основе
     * ledger через атомарный upsert (ON CONFLICT (user_id, club_id) DO UPDATE). Идемпотентно
     * и коммутативно при конкурентности — оба гонщика приходят к одинаковым значениям.
     */
    fun recompute(userId: UUID, clubId: UUID)
}
