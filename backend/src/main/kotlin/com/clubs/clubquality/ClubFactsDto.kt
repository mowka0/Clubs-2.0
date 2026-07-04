package com.clubs.clubquality

/**
 * HTTP-форма [ClubFacts] для `GET /api/clubs/{clubId}/quality`.
 *
 * @property meetingsPerMonth проведённые события за последние 90 дней ÷ 3 (Активность)
 * @property avgAttendance     среднее число уникальных посетителей на завершённую встречу за
 *                             последние 90 дней (Приходит)
 * @property coreSize          уникальные пользователи с ≥3 посещёнными событиями за всё время
 *                             (Сплочённость / ядро)
 * @property ageMonths         полных месяцев с момента создания клуба
 * @property totalMeetings     всего проведённых (прошедших, не отменённых) событий за всё время
 *                             (milestone «N встреч»)
 * @property successfulSkladchinas складчин, закрытых как успешные (milestone «первый сбор»)
 */
data class ClubFactsDto(
    val meetingsPerMonth: Double,
    val avgAttendance: Int,
    val coreSize: Int,
    val ageMonths: Int,
    val totalMeetings: Int,
    val successfulSkladchinas: Int,
)

/**
 * HTTP-форма [ClubCardFacts] для `GET /api/clubs/quality/batch?ids=...` — решающая тройка карточки
 * ленты Discovery (возраст · участники · вовлечённость), один элемент на существующий клуб.
 * «участники» приходит из `ClubListItemDto.memberCount`, не отсюда.
 *
 * @property clubId            клуб, к которому относятся эти факты (вызывающий индексирует ответ по нему)
 * @property ageDays           полных дней с момента создания клуба (возраст)
 * @property engagementPercent уникальные недавние отвечающие ÷ живые участники, 0..100 (вовлечённость)
 * @property topInCategory     "★ Топ-5 в категории" — ЕДИНСТВЕННАЯ внешне видимая производная L3.
 *                             Чистый boolean: внутренний score/разбивку ранга сервер НИКОГДА не отдаёт
 *                             наружу (дизайн §4 "L3 невидим и необъясним"). False, если feature-флаг
 *                             деплоя выключен или не достигнут глобальный порог ранга.
 */
data class ClubCardFactsDto(
    val clubId: java.util.UUID,
    val ageDays: Int,
    val engagementPercent: Int,
    val topInCategory: Boolean,
)
