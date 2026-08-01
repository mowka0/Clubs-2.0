package com.clubs.interest

import com.clubs.generated.jooq.enums.ClubCategory
import java.util.UUID

interface InterestRepository {

    /** Названия, чья каноническая форма начинается с [prefix], сначала самые используемые. */
    fun suggest(prefix: String, limit: Int): List<String>

    /**
     * Топ-темы полки [category] — из них собираются чипы при разметке клуба. Сортировка по
     * club_usage_count, а не usage_count: частый интерес в профилях ≠ частая тема клубов.
     */
    fun suggestByCategory(category: ClubCategory, limit: Int): List<String>

    /** Вставить отсутствующие названия (игнорируя конфликты) и вернуть name → id для всех. */
    fun upsertAll(names: List<String>): Map<String, UUID>

    fun findUserInterestIds(userId: UUID): Set<UUID>
    fun findUserInterestNames(userId: UUID): List<String>

    /**
     * Пакетный поиск названий интересов по пользователям (один SQL-запрос). Используется
     * инбоксом заявок для обогащения профилей заявителей без N+1.
     * Пустой вход → emptyMap (без SQL-запроса). Пользователи без интересов отсутствуют
     * в карте; вызывающие используют emptyList по умолчанию.
     */
    fun findUserInterestNamesByUserIds(userIds: Collection<UUID>): Map<UUID, List<String>>

    fun linkUserInterests(userId: UUID, interestIds: Collection<UUID>)
    fun unlinkUserInterests(userId: UUID, interestIds: Collection<UUID>)

    /** Скорректировать счётчики популярности (delta ограничена снизу нулём). */
    fun adjustUsage(interestIds: Collection<UUID>, delta: Int)

    // ── Темы клуба (club-interests) ─────────────────────────────────────────────────────

    fun findClubInterestIds(clubId: UUID): Set<UUID>
    fun findClubInterestNames(clubId: UUID): List<String>

    /**
     * Пакетное чтение тем по клубам (один SQL-запрос) — каталог рисует чипы на всей странице
     * выдачи, поштучный запрос дал бы N+1. Пустой вход → emptyMap без запроса; клубы без тем
     * в карте отсутствуют, вызывающие используют emptyList по умолчанию.
     */
    fun findClubInterestNamesByClubIds(clubIds: Collection<UUID>): Map<UUID, List<String>>

    /**
     * Перезаписывает набор тем клуба целиком, сохраняя порядок [orderedInterestIds] в колонке
     * position. Перезапись, а не точечные link/unlink: позиции должны соответствовать
     * присланному порядку даже когда состав не изменился, а темы лишь переставили местами.
     * Счётчики употребления двигает вызывающий — он знает разницу наборов.
     */
    fun replaceClubInterestLinks(clubId: UUID, orderedInterestIds: List<UUID>)

    /** Скорректировать счётчики употребления КЛУБАМИ (delta ограничена снизу нулём). */
    fun adjustClubUsage(interestIds: Collection<UUID>, delta: Int)
}
