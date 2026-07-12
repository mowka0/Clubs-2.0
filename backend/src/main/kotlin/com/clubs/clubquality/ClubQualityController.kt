package com.clubs.clubquality

import com.clubs.common.auth.RequiresClubManager
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Поверхность качества клуба (subject = место, anchor = club_id). Отдельный контроллер в модуле
 * `clubquality` — не в `ClubController` — чтобы сохранить чистоту границы модуля (§10).
 *
 * Факты видны `others` (публичное social proof) → достаточно JWT, без проверки владения. Панель
 * владельца «Статистика» (`/{clubId}/stats`) — исключение: приватна, `@RequiresClubManager`
 * (владелец или активный со-организатор).
 */
@RestController
@RequestMapping("/api/clubs")
class ClubQualityController(
    private val clubQualityService: ClubQualityService,
    private val clubStatsService: ClubStatsService,
) {

    @GetMapping("/{clubId}/quality")
    fun getQuality(@PathVariable clubId: UUID): ResponseEntity<ClubFactsDto> =
        ResponseEntity.ok(clubQualityService.getClubFacts(clubId))

    /**
     * Пакетные факты для ленты Discovery (один запрос на страницу клубов, не на карточку → без N+1).
     * `?ids=uuid1,uuid2,...`. Литерал `/quality/batch` не конфликтует с `/{clubId}/quality`.
     */
    @GetMapping("/quality/batch")
    fun getQualityBatch(@RequestParam ids: List<UUID>): ResponseEntity<List<ClubCardFactsDto>> =
        ResponseEntity.ok(clubQualityService.getClubCardFacts(ids))

    /**
     * Панель статистики для менеджера клуба (§9, co-organizers). `@RequiresClubManager` отклоняет
     * отсутствующий клуб (404) и не-менеджера (403) до выполнения тела метода. Литерал
     * `/{clubId}/stats` не конфликтует с маршрутами выше.
     */
    @RequiresClubManager(clubIdParam = "clubId")
    @GetMapping("/{clubId}/stats")
    fun getStats(@PathVariable clubId: UUID): ResponseEntity<ClubStatsDto> =
        ResponseEntity.ok(clubStatsService.getClubStats(clubId))

    /**
     * Ростер для возврата участников для менеджера клуба — участники за напоминанием
     * «Верните N ушедших» (§9.5). Та же защита `@RequiresClubManager`, что и у `/stats`. Литерал
     * `/{clubId}/churned-members` не конфликтует с маршрутами выше или `/{id}/members` из `MemberController`.
     */
    @RequiresClubManager(clubIdParam = "clubId")
    @GetMapping("/{clubId}/churned-members")
    fun getChurnedMembers(@PathVariable clubId: UUID): ResponseEntity<List<ChurnedMemberDto>> =
        ResponseEntity.ok(clubStatsService.getChurnedMembers(clubId))
}
