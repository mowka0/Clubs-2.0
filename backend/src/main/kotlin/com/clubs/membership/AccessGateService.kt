package com.clubs.membership

import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Organizer-controlled access gate (de-Stars, Slice 2). The organizer admits or suspends a paid-club
 * member's content access (`active` ↔ `frozen`) and records the off-platform dues payment. Money flows
 * member→organizer outside the platform (honor-system, like skladchina) — these actions only move
 * membership status / dues markers, never money or reputation.
 *
 * Owner-check is declarative on the controller (@RequiresOrganizer); this service guards each status
 * transition with the same `WHERE status = expected` + rows-affected pattern the skladchina org-toggle
 * uses (0 rows → 409). The organizer's own membership cannot be managed.
 */
@Service
class AccessGateService(
    private val membershipRepository: MembershipRepository,
    private val mapper: MembershipMapper,
    // How long one confirmed dues payment grants access (honor-system monthly membership, default 30 days).
    @Value("\${membership.access-period-days:30}") private val accessPeriodDays: Long
) {
    private val log = LoggerFactory.getLogger(AccessGateService::class.java)

    @Transactional
    fun freezeAccess(clubId: UUID, targetUserId: UUID, callerId: UUID): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        if (membership.status == MembershipStatus.frozen) return mapper.toDto(membership) // idempotent
        if (membership.status != MembershipStatus.active) {
            throw ValidationException("Заморозить можно только активного участника")
        }
        guardApplied(membershipRepository.freezeAccess(membership.id))
        log.info("Access frozen: clubId={} targetUserId={} by={}", clubId, targetUserId, callerId)
        return mapper.toDto(membership.copy(status = MembershipStatus.frozen))
    }

    @Transactional
    fun unfreezeAccess(clubId: UUID, targetUserId: UUID, callerId: UUID): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        if (membership.status == MembershipStatus.active) return mapper.toDto(membership) // idempotent
        if (membership.status != MembershipStatus.frozen) {
            throw ValidationException("Разморозить можно только замороженного участника")
        }
        guardApplied(membershipRepository.unfreezeAccess(membership.id))
        log.info("Access unfrozen: clubId={} targetUserId={} by={}", clubId, targetUserId, callerId)
        return mapper.toDto(membership.copy(status = MembershipStatus.active))
    }

    @Transactional
    fun markDuesPaid(clubId: UUID, targetUserId: UUID, callerId: UUID): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        if (membership.status != MembershipStatus.active && membership.status != MembershipStatus.frozen) {
            throw ValidationException("Отметить взнос можно только у действующего участника")
        }
        // Honor-system monthly membership: confirming a dues payment opens access and extends it by one
        // period. Extend from the CURRENT expiry (or now, whichever is later) so paying early never loses
        // paid days — e.g. "до 28 июня" + payment → "до 28 июля". The scheduler later auto-expires overdue
        // access back to `frozen`, so we keep the date here as the single driver.
        val now = OffsetDateTime.now()
        val base = membership.subscriptionExpiresAt?.takeIf { it.isAfter(now) } ?: now
        val newExpiresAt = base.plusDays(accessPeriodDays)
        guardApplied(membershipRepository.markDuesPaid(membership.id, callerId, newExpiresAt))
        log.info("Dues marked paid: clubId={} targetUserId={} by={} accessUntil={}", clubId, targetUserId, callerId, newExpiresAt)
        // markDuesPaid grants access (active) regardless of any prior frozen state, valid until newExpiresAt.
        return mapper.toDto(membership.copy(status = MembershipStatus.active, subscriptionExpiresAt = newExpiresAt))
    }

    @Transactional
    fun unmarkDues(clubId: UUID, targetUserId: UUID, callerId: UUID): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        // Idempotent: 0 rows just means there was no dues record to clear — a no-op, not a race.
        // Un-marking never changes access status (symmetric with skladchina un-mark not auto-closing).
        membershipRepository.unmarkDues(membership.id)
        log.info("Dues unmarked: clubId={} targetUserId={} by={}", clubId, targetUserId, callerId)
        return mapper.toDto(membership)
    }

    private fun loadManageableMember(clubId: UUID, targetUserId: UUID): Membership {
        val membership = membershipRepository.findByUserAndClub(targetUserId, clubId)
            ?: throw NotFoundException("Участник не найден в этом клубе")
        if (membership.role == MembershipRole.organizer) {
            throw ValidationException("Нельзя управлять доступом организатора")
        }
        return membership
    }

    // 0 rows = the membership left the expected status between our read and the guarded UPDATE
    // (concurrent leave / another manage action) — refuse instead of reporting a change that didn't happen.
    private fun guardApplied(rowsAffected: Int) {
        if (rowsAffected == 0) throw ConflictException("Статус участника изменился — обновите экран")
    }
}
