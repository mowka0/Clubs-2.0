package com.clubs.application

import com.clubs.generated.jooq.enums.ApplicationStatus
import java.time.OffsetDateTime
import java.util.UUID

interface ApplicationRepository {

    // Мутации
    fun create(userId: UUID, clubId: UUID, answerText: String?): Application
    fun updateStatus(id: UUID, status: ApplicationStatus, reason: String? = null): Application

    /**
     * Полностью удаляет любую pending- или approved-заявку для пары (user, club).
     * Используется `MembershipService.leaveClub`, чтобы вернувшийся пользователь
     * подавал заявку заново с нуля (бесплатный закрытый клуб) и не всплывал
     * в списке «Ожидают оплаты» с устаревшим одобрением (платный клуб). Строки
     * rejected / auto_rejected сохраняются — они остаются как аудиторская история
     * решений организатора в инбоксе.
     */
    fun deleteActiveByUserAndClub(userId: UUID, clubId: UUID): Int

    /**
     * Каскад soft-delete клуба: полностью удаляет каждую pending/approved заявку в [clubId]
     * (для всех пользователей), чтобы они не оставались осиротевшими строками в «Мои заявки»
     * заявителей — клуба больше нет, запрос теряет смысл. Строки rejected / auto_rejected
     * сохраняются как аудиторская история, зеркалит [deleteActiveByUserAndClub]. Возвращает
     * число удалённых строк.
     */
    fun deleteActiveByClub(clubId: UUID): Int

    // Поиск
    fun findById(id: UUID): Application?
    fun findByClubId(clubId: UUID, status: ApplicationStatus?): List<Application>
    fun findActiveByUserAndClub(userId: UUID, clubId: UUID): Application?
    fun findByUserId(userId: UUID): List<Application>

    /**
     * Pending-заявки по нескольким клубам, от старых к новым.
     * Используется кросс-клубовым инбоксом организатора. Пустой вход → пустой выход (без запроса к БД).
     */
    fun findPendingByClubIds(clubIds: Collection<UUID>): List<Application>

    /** Число pending-заявок по нескольким клубам. Пустой вход → 0 (без запроса к БД). */
    fun countPendingByClubIds(clubIds: Collection<UUID>): Int

    // Счётчики / rate limit
    fun countTodayByUser(userId: UUID): Int

    // Планировщик
    fun findPendingOlderThan(cutoff: OffsetDateTime): List<Application>
    fun markAutoRejected(cutoff: OffsetDateTime): Int
}
