package com.clubs.common.auth

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.security.AuthenticatedUser
import com.clubs.membership.MembershipRepository
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.util.UUID

@Aspect
@Component
class AuthorizationAspect(
    private val membershipRepository: MembershipRepository,
    private val clubRepository: ClubRepository
) {

    @Around("@annotation(requiresMembership)")
    fun checkMembership(joinPoint: ProceedingJoinPoint, requiresMembership: RequiresMembership): Any? {
        val userId = currentUserId()
        val clubId = extractUUID(joinPoint, requiresMembership.clubIdParam)
        if (!membershipRepository.isActiveMemberInActiveClub(userId, clubId)) {
            throw ForbiddenException("You must be an active member of this club")
        }
        return joinPoint.proceed()
    }

    @Around("@annotation(requiresOrganizer)")
    fun checkOrganizer(joinPoint: ProceedingJoinPoint, requiresOrganizer: RequiresOrganizer): Any? {
        val userId = currentUserId()
        val clubId = extractUUID(joinPoint, requiresOrganizer.clubIdParam)
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
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
