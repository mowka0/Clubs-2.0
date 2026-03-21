package com.clubs.club

import com.clubs.common.auth.RequiresOrganizer
import com.clubs.common.dto.PageResponse
import com.clubs.common.security.AuthenticatedUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class LinkGroupRequest(val telegramGroupId: Long)

@RestController
@RequestMapping("/api/clubs")
class ClubController(
    private val clubService: ClubService,
    private val financesService: FinancesService
) {

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
        val club = clubService.createClub(request, user.userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(club)
    }

    @GetMapping("/{id}")
    fun getClub(@PathVariable id: UUID): ResponseEntity<ClubDetailDto> =
        ResponseEntity.ok(clubService.getClub(id))

    @PutMapping("/{id}")
    fun updateClub(
        @PathVariable id: UUID,
        @RequestBody @Valid request: UpdateClubRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ClubDetailDto> =
        ResponseEntity.ok(clubService.updateClub(id, request, user.userId))

    @RequiresOrganizer
    @PostMapping("/{id}/link-group")
    fun linkGroup(
        @PathVariable id: UUID,
        @RequestBody request: LinkGroupRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ClubDetailDto> =
        ResponseEntity.ok(clubService.linkTelegramGroup(id, request.telegramGroupId, user.userId))

    @RequiresOrganizer
    @GetMapping("/{id}/finances")
    fun getFinances(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<FinancesDto> =
        ResponseEntity.ok(financesService.getFinances(id, user.userId))
}
