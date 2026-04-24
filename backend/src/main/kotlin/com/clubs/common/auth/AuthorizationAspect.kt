package com.clubs.common.auth

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.security.AuthenticatedUser
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.jooq.DSLContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.util.UUID

@Aspect
@Component
class AuthorizationAspect(private val dsl: DSLContext) {

    @Around("@annotation(requiresMembership)")
    fun checkMembership(joinPoint: ProceedingJoinPoint, requiresMembership: RequiresMembership): Any? {
        val userId = currentUserId()
        val clubId = extractUUID(joinPoint, requiresMembership.clubIdParam)
        val exists = dsl.fetchExists(
            dsl.selectOne().from(MEMBERSHIPS)
                .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
                .where(
                    MEMBERSHIPS.USER_ID.eq(userId)
                        .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                        .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
                        .and(CLUBS.IS_ACTIVE.eq(true))
                )
        )
        if (!exists) throw ForbiddenException("You must be an active member of this club")
        return joinPoint.proceed()
    }

    @Around("@annotation(requiresOrganizer)")
    fun checkOrganizer(joinPoint: ProceedingJoinPoint, requiresOrganizer: RequiresOrganizer): Any? {
        val userId = currentUserId()
        val clubId = extractUUID(joinPoint, requiresOrganizer.clubIdParam)
        val club = dsl.selectFrom(CLUBS)
            .where(CLUBS.ID.eq(clubId).and(CLUBS.IS_ACTIVE.eq(true)))
            .fetchOne()
            ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club organizer can perform this action")
        return joinPoint.proceed()
    }

    private fun currentUserId(): UUID {
        val principal = SecurityContextHolder.getContext().authentication?.principal
            as? AuthenticatedUser ?: throw ForbiddenException("Not authenticated")
        return principal.userId
    }

    private fun extractUUID(joinPoint: ProceedingJoinPoint, paramName: String): UUID {
        val signature = joinPoint.signature as MethodSignature
        val names = signature.parameterNames
        val index = names.indexOf(paramName)
        if (index < 0) throw IllegalStateException("Parameter '$paramName' not found in ${signature.name}")
        return joinPoint.args[index] as UUID
    }
}
