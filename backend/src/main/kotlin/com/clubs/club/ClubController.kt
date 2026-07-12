package com.clubs.club

import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.RequiresCapability
import com.clubs.common.dto.PageResponse
import com.clubs.common.security.AuthenticatedUser
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/clubs")
class ClubController(
    private val clubService: ClubService,
    private val financesService: FinancesService,
    private val organizerCardService: OrganizerCardService
) {

    private val log = LoggerFactory.getLogger(ClubController::class.java)

    // Карточка доверия организатора для шита оплаты взносов (de-Stars). Только JWT / видна другим
    // (без гейта на владение) — организатор публичный хост; участник должен видеть, кому платит,
    // прежде чем сделать перевод.
    @GetMapping("/{id}/organizer-card")
    fun organizerCard(@PathVariable id: UUID): ResponseEntity<OrganizerCardDto> =
        ResponseEntity.ok(organizerCardService.getOrganizerCard(id))

    @GetMapping
    fun getClubs(
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) city: String?,
        @RequestParam(required = false) accessType: String?,
        @RequestParam(required = false) minPrice: Int?,
        @RequestParam(required = false) maxPrice: Int?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<ClubListItemDto>> {
        val filters = ClubFilterParams(category, city, accessType, minPrice, maxPrice, search, page, size)
        return ResponseEntity.ok(clubService.getClubs(filters))
    }

    @PostMapping
    fun createClub(
        @RequestBody @Valid request: CreateClubRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ClubDetailDto> {
        log.info("Create club: name='{}' userId={}", request.name, user.userId)
        val club = clubService.createClub(request, user.userId)
        log.info("Club created: id={} name='{}' userId={}", club.id, club.name, user.userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(club)
    }

    @GetMapping("/{id}")
    fun getClub(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ClubDetailDto> =
        ResponseEntity.ok(clubService.getClub(id, user.userId))

    @PutMapping("/{id}")
    fun updateClub(
        @PathVariable id: UUID,
        @RequestBody @Valid request: UpdateClubRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ClubDetailDto> {
        log.info("Update club: id={} userId={}", id, user.userId)
        return ResponseEntity.ok(clubService.updateClub(id, request, user.userId))
    }

    @DeleteMapping("/{id}")
    fun deleteClub(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<Void> {
        log.info("Delete club: id={} userId={}", id, user.userId)
        clubService.deleteClub(id, user.userId)
        return ResponseEntity.noContent().build()
    }

    @RequiresCapability(ClubCapability.VIEW_FINANCES)
    @GetMapping("/{id}/finances")
    fun getFinances(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<FinancesDto> =
        ResponseEntity.ok(financesService.getFinances(id, user.userId))
}
