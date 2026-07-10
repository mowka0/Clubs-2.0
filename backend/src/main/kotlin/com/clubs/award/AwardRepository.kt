package com.clubs.award

import java.util.UUID

interface AwardRepository {

    /** Все награды, выданные [userId] в [clubId], сначала новые. Видны всем участникам (R3). */
    fun findByMember(clubId: UUID, userId: UUID): List<Award>

    /** Все награды пользователя по всем клубам (один запрос) — для карточек «Моих клубов». */
    fun findByUser(userId: UUID): List<Award>

    /** Все награды в [clubId] (сначала новые), для ростера — сервис группирует их по участнику. */
    fun findByClub(clubId: UUID): List<Award>

    /** Уникальные (emoji, label), когда-либо выданные в [clubId], сначала самые частые — автокомплит формы выдачи. */
    fun findSuggestions(clubId: UUID, limit: Int): List<AwardSuggestion>

    /** Сколько наград [userId] уже держит в [clubId] (проверка лимита на участника). */
    fun countByMember(clubId: UUID, userId: UUID): Int

    /** Есть ли уже у [userId] награда с точно таким [label] в [clubId] (без дублей). */
    fun existsByLabel(clubId: UUID, userId: UUID, label: String): Boolean

    fun insert(award: Award): Award

    /** Удаляет награду, только если она принадлежит (clubId, userId). Возвращает число затронутых строк (0 = не найдено). */
    fun delete(awardId: UUID, clubId: UUID, userId: UUID): Int

    /**
     * Строки синхронизации тегов (слайс 4): КАЖДЫЙ «живой» участник клуба (не cancelled)
     * с его последней наградой (label=null — наград нет). Для backfill тумблера и шедулера
     * полной сверки тег↔награда.
     */
    fun findTagSyncRows(clubId: UUID): List<TagSyncRow>
}

/** Строка сверки тегов: живой участник клуба + его последняя награда (null — наград нет). */
data class TagSyncRow(
    val userId: UUID,
    val telegramId: Long,
    val label: String?
)
