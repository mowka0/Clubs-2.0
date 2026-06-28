package com.clubs.membership

import com.clubs.bot.NotificationService
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.user.UserRepository
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
    @Value("\${membership.access-period-days:30}") private val accessPeriodDays: Long,
    // Storage origin our uploader prefixes to screenshots ("{base}/uploads/..."); empty in prod → URLs
    // come back root-relative ("/uploads/..."). Used to validate a dues-claim proof is OUR upload.
    @Value("\${s3.base-url:}") private val storageBaseUrl: String,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService
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

    // Member admin profile (S1): organizer manually sets the access window end («своя дата»).
    @Transactional
    fun setAccessUntil(clubId: UUID, targetUserId: UUID, until: OffsetDateTime, callerId: UUID): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        if (!until.isAfter(OffsetDateTime.now())) {
            throw ValidationException("Дата окончания доступа должна быть в будущем")
        }
        guardApplied(membershipRepository.setAccessUntil(membership.id, until))
        log.info("Access window set: clubId={} targetUserId={} by={} until={}", clubId, targetUserId, callerId, until)
        return mapper.toDto(membership.copy(status = MembershipStatus.active, subscriptionExpiresAt = until))
    }

    // Member admin profile (S1): organizer sets/clears the private note. Blank → null.
    @Transactional
    fun updateNote(clubId: UUID, targetUserId: UUID, note: String?, callerId: UUID): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        val clean = note?.trim()?.takeIf { it.isNotEmpty() }
        membershipRepository.updateOrganizerNote(membership.id, clean)
        log.info("Organizer note updated: clubId={} targetUserId={} by={} present={}", clubId, targetUserId, callerId, clean != null)
        return mapper.toDto(membership.copy(organizerNote = clean))
    }

    // De-Stars: the MEMBER (callerId) declares they paid the off-platform dues. Not organizer-gated —
    // the caller acts on their own membership. Creates a claim the organizer reviews; access still opens
    // only when the organizer presses «Взнос получен» (honor-system preserved). sbp requires a screenshot;
    // cash is a plain attestation (no proof).
    @Transactional
    fun claimDues(clubId: UUID, callerId: UUID, method: String, proofUrl: String?): MembershipDto {
        val membership = membershipRepository.findByUserAndClub(callerId, clubId)
            ?: throw NotFoundException("Вы не состоите в этом клубе")
        if (membership.status != MembershipStatus.frozen) {
            throw ValidationException("Заявить об оплате можно только пока доступ закрыт")
        }
        val normalizedMethod = when (method.trim().lowercase()) {
            CLAIM_SBP -> CLAIM_SBP
            CLAIM_CASH -> CLAIM_CASH
            else -> throw ValidationException("Неизвестный способ оплаты")
        }
        val cleanProof = proofUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedMethod == CLAIM_SBP) {
            if (cleanProof == null) throw ValidationException("Прикрепите скриншот оплаты")
            // The proof must be an image from our own uploader — a client could otherwise submit an
            // arbitrary/external/`javascript:` URL that the organizer's review renders as a clickable link.
            if (!isUploadedImageUrl(cleanProof)) throw ValidationException("Некорректная ссылка на скриншот")
        }
        // Cash never carries a screenshot, even if the client sent one.
        val proof = if (normalizedMethod == CLAIM_SBP) cleanProof else null
        guardApplied(membershipRepository.claimDues(membership.id, normalizedMethod, proof))
        log.info("Dues claim submitted: clubId={} userId={} method={} hasProof={}", clubId, callerId, normalizedMethod, proof != null)
        return mapper.toDto(
            membership.copy(duesClaimedAt = OffsetDateTime.now(), duesClaimMethod = normalizedMethod, duesProofUrl = proof)
        )
    }

    // De-Stars B+C: the organizer rejects a paid join (instead of «Взнос получен») — the member paid but
    // the organizer doesn't admit them. Removes them from the club; the refund is the organizer's offline
    // responsibility (platform is outside the money flow). Frozen-only — an already-admitted member is
    // managed via freeze, not reject.
    @Transactional
    fun rejectMember(clubId: UUID, targetUserId: UUID, callerId: UUID, reason: String?): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        if (membership.status != MembershipStatus.frozen) {
            throw ValidationException("Отклонить вступление можно только пока доступ не открыт")
        }
        membershipRepository.cancel(membership.id)
        log.info("Join rejected (refund): clubId={} targetUserId={} by={} hasReason={}", clubId, targetUserId, callerId, reason != null)
        notifyRejected(targetUserId, reason)
        return mapper.toDto(membership.copy(status = MembershipStatus.cancelled))
    }

    // Best-effort DM — the member paid off-platform, so tell them to expect a refund. Never aborts the reject.
    private fun notifyRejected(targetUserId: UUID, reason: String?) {
        try {
            val telegramId = userRepository.findById(targetUserId)?.telegramId ?: return
            val base = "Организатор отклонил ваше вступление в платный клуб и вернёт перевод."
            val text = reason?.trim()?.takeIf { it.isNotEmpty() }?.let { "$base\nПричина: $it" } ?: base
            notificationService.sendDirectMessage(telegramId, text)
        } catch (e: Exception) {
            log.warn("Failed to DM rejected member (non-fatal): targetUserId={} error={}", targetUserId, e.message)
        }
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

    /**
     * True only for a screenshot URL OUR uploader produced: "{s3.base-url}/uploads/{uuid}.{ext}" — where
     * base-url is empty in prod, so the URL is root-relative "/uploads/...". Strips exactly the configured
     * origin, then checks the remainder is an "uploads/<name>.<imgext>" path. This blocks javascript:/data:
     * URLs AND arbitrary external hosts (e.g. evil.com/uploads/x.png) from reaching the organizer's
     * clickable review link — the proof must come from our own storage.
     */
    private fun isUploadedImageUrl(url: String): Boolean {
        val prefix = storageBaseUrl.trimEnd('/')
        val relative = when {
            prefix.isNotEmpty() && url.startsWith("$prefix/") -> url.removePrefix("$prefix/")
            prefix.isEmpty() && url.startsWith("/") -> url.removePrefix("/")
            else -> return false
        }
        return UPLOADS_PATH.matches(relative)
    }

    companion object {
        const val CLAIM_SBP = "sbp"
        const val CLAIM_CASH = "cash"
        // The storage-relative path our uploader writes: "uploads/{uuid}.{ext}" (StorageController).
        private val UPLOADS_PATH = Regex("^uploads/[\\w.-]+\\.(jpg|jpeg|png)$", RegexOption.IGNORE_CASE)
    }
}
