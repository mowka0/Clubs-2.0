package com.clubs.clubquality

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
 * Facts are `others`-visible (public social proof) → JWT only, no ownership check.
 */
@RestController
@RequestMapping("/api/clubs")
class ClubQualityController(private val clubQualityService: ClubQualityService) {

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
}
