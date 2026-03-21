package com.clubs.membership

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.security.AuthenticatedUser
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.USER_CLUB_REPUTATION
import com.clubs.generated.jooq.tables.references.USERS
import com.clubs.user.MemberProfileDto
import org.jooq.DSLContext
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

@RestController
@RequestMapping("/api/clubs")
class MemberController(private val dsl: DSLContext) {

    @GetMapping("/{clubId}/members/{userId}")
    fun getMemberProfile(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MemberProfileDto> {
        dsl.selectFrom(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIPS.USER_ID.eq(caller.userId))
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
            )
            .fetchOne() ?: throw ForbiddenException("Not a member of this club")

        val user = dsl.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchOne()
            ?: throw NotFoundException("User not found")

        val reputation = dsl.selectFrom(USER_CLUB_REPUTATION)
            .where(USER_CLUB_REPUTATION.USER_ID.eq(userId).and(USER_CLUB_REPUTATION.CLUB_ID.eq(clubId)))
            .fetchOne()

        return ResponseEntity.ok(
            MemberProfileDto(
                userId = userId,
                clubId = clubId,
                firstName = user.firstName,
                username = user.telegramUsername,
                avatarUrl = user.avatarUrl,
                reliabilityIndex = reputation?.reliabilityIndex ?: 100,
                promiseFulfillmentPct = reputation?.promiseFulfillmentPct ?: BigDecimal.ZERO,
                totalConfirmations = reputation?.totalConfirmations ?: 0,
                totalAttendances = reputation?.totalAttendances ?: 0
            )
        )
    }
}
