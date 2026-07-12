package com.clubs.clubquality

import java.util.UUID

interface ClubStatsRepository {

    /**
     * Считает статистику клуба, видную менеджеру (владелец или активный со-организатор), через
     * read-only оконные агрегации. Возвращает `null`, если строки клуба для [clubId] не существует
     * (вызывающий код мапит в 404). На практике aspect `@RequiresCapability(VIEW_STATS)` уже отклоняет
     * отсутствующий клуб до запуска сервиса.
     */
    fun findClubStats(clubId: UUID): ClubStats?

    /**
     * Ростер для win-back: уникальные участники, ушедшие/истёкшие в пределах окна удержания и
     * сейчас не active/grace, отсортированные от самого недавно ушедшего. Питает drill-down
     * «Верните N ушедших» — его размер по построению равен [ClubStats.churnedThisPeriod] (тот же предикат).
     */
    fun findChurnedMembers(clubId: UUID): List<ChurnedMember>
}
