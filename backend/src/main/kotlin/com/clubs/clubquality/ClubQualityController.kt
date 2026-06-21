package com.clubs.clubquality

import com.clubs.common.auth.RequiresOrganizer
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Club-quality surface (subject = place, anchor = club_id). Separate controller in the
 * `clubquality` module — not in `ClubController` — to keep the module boundary clean (§10).
 *
 * Facts are `others`-visible (public social proof) → JWT only, no ownership check. The owner
 * «Статистика» panel (`/{clubId}/stats`) is the exception — private, `@RequiresOrganizer`.
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
     * Batch facts for the Discovery feed (one request per page of clubs, not per card → no N+1).
     * `?ids=uuid1,uuid2,...`. Literal `/quality/batch` doesn't collide with `/{clubId}/quality`.
     */
    @GetMapping("/quality/batch")
    fun getQualityBatch(@RequestParam ids: List<UUID>): ResponseEntity<List<ClubCardFactsDto>> =
        ResponseEntity.ok(clubQualityService.getClubCardFacts(ids))

    /**
     * Owner-only statistics panel (§9). `@RequiresOrganizer` rejects a missing club (404) and a
     * non-owner (403) before the body runs. Literal `/{clubId}/stats` doesn't collide with the routes above.
     */
    @RequiresOrganizer(clubIdParam = "clubId")
    @GetMapping("/{clubId}/stats")
    fun getStats(@PathVariable clubId: UUID): ResponseEntity<ClubStatsDto> =
        ResponseEntity.ok(clubStatsService.getClubStats(clubId))

    /**
     * Owner-only win-back roster — the members behind the «Верните N ушедших» nudge (§9.5). Same
     * `@RequiresOrganizer` gate as `/stats`. Literal `/{clubId}/churned-members` doesn't collide with
     * the routes above or `MemberController`'s `/{id}/members`.
     */
    @RequiresOrganizer(clubIdParam = "clubId")
    @GetMapping("/{clubId}/churned-members")
    fun getChurnedMembers(@PathVariable clubId: UUID): ResponseEntity<List<ChurnedMemberDto>> =
        ResponseEntity.ok(clubStatsService.getChurnedMembers(clubId))
}
