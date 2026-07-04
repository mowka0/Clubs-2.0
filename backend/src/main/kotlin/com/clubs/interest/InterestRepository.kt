package com.clubs.interest

import java.util.UUID

interface InterestRepository {

    /** Названия, чья каноническая форма начинается с [prefix], сначала самые используемые. */
    fun suggest(prefix: String, limit: Int): List<String>

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
}
