package com.clubs.clubquality

import java.util.UUID

interface ClubQualityRepository {

    /**
     * Считает L1-факты клуба read-only-агрегациями.
     * Возвращает `null`, если строки клуба для [clubId] нет (вызывающий мапит в 404).
     */
    fun findClubFacts(clubId: UUID): ClubFacts?

    /**
     * Батчево считает факты Discovery-карточек для указанных клубов (один БАТЧЕВЫЙ запрос
     * на метрику, без N+1). Id без строки клуба пропускаются. Пустой вход → пустой выход
     * (без похода в SQL). Порядок не определён; вызывающий ключует результат по
     * [ClubCardFacts.clubId].
     */
    fun findClubCardFacts(clubIds: Collection<UUID>): List<ClubCardFacts>
}
