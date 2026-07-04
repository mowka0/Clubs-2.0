package com.clubs.activity.dto

/**
 * Лента активности клуба из двух групп.
 *
 * Лента разбита по собственной дате каждой активности (не по дате создания), чтобы UI мог
 * рендерить предстоящие элементы полными карточками, а прошедшие — сворачивать:
 * - [upcoming] — ещё не завершённые, отсортированы по `relevantDate` по возрастанию (ближайшие первыми)
 * - [past] — завершённые, отсортированы по `relevantDate` по убыванию (самые недавние первыми)
 *
 * `relevantDate` — это `eventDatetime` для событий и `deadline` для складчин.
 * Правила разбиения и сортировки см. в [com.clubs.activity.ActivityService].
 */
data class ClubActivityFeedDto(
    val upcoming: List<ActivityItemDto>,
    val past: List<ActivityItemDto>
)
