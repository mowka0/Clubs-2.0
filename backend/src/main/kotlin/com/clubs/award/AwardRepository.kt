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
     * Кандидаты на титул в чате (слайс 4): по каждому «живому» участнику клуба (не cancelled)
     * с наградами — последняя награда и telegram_id. Для backfill при включении тумблера.
     */
    fun findTitleCandidates(clubId: UUID): List<AwardTitleCandidate>
}

/** Кандидат на титул: последняя награда живого участника клуба (см. [AwardRepository.findTitleCandidates]). */
data class AwardTitleCandidate(
    val userId: UUID,
    val telegramId: Long,
    val label: String,
    /** Статус membership — гейт «не титуловать должника при включённом строгом режиме». */
    val membershipStatus: String
)
