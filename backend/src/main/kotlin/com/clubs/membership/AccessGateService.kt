package com.clubs.membership

import com.clubs.application.ApplicationRepository
import com.clubs.bot.NotificationService
import com.clubs.club.ClubRepository
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
    private val clubRepository: ClubRepository,
    private val applicationRepository: ApplicationRepository,
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
        notifyFrozen(clubId, targetUserId)
        return mapper.toDto(membership.copy(status = MembershipStatus.frozen))
    }

    // Best-effort DM to the member whose access the organizer just closed: «доступ закрыт — оплатите взнос»
    // with an inline button deep-linking to the club page (where «Оплатить взнос» lives). Never aborts the freeze.
    private fun notifyFrozen(clubId: UUID, targetUserId: UUID) {
        try {
            val member = userRepository.findById(targetUserId) ?: return
            val clubName = clubRepository.findById(clubId)?.name ?: "клуб"
            notificationService.sendAccessFrozenDM(member.telegramId, clubName, clubId)
        } catch (e: Exception) {
            log.warn("Failed to DM frozen member (non-fatal): clubId={} targetUserId={} error={}", clubId, targetUserId, e.message)
        }
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
        notifyOrganizerOfClaim(clubId, callerId, normalizedMethod)
        return mapper.toDto(
            membership.copy(duesClaimedAt = OffsetDateTime.now(), duesClaimMethod = normalizedMethod, duesProofUrl = proof)
        )
    }

    // Best-effort DM to the club's organizer that a member paid off-platform and awaits admission.
    // Never aborts the claim — the claim is already committed and surfaced in «Ждут оплаты» regardless.
    private fun notifyOrganizerOfClaim(clubId: UUID, memberUserId: UUID, method: String) {
        try {
            val club = clubRepository.findById(clubId) ?: return
            val organizer = userRepository.findById(club.ownerId) ?: return
            val member = userRepository.findById(memberUserId)
            val memberName = member?.let {
                if (it.lastName.isNullOrBlank()) it.firstName else "${it.firstName} ${it.lastName}"
            } ?: "Участник"
            notificationService.sendDuesClaimedDM(organizer.telegramId, memberName, club.name, method)
        } catch (e: Exception) {
            log.warn("Failed to DM organizer of dues claim (non-fatal): clubId={} memberUserId={} error={}", clubId, memberUserId, e.message)
        }
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
        // Clear the approved/pending application too (mirrors /leave) — otherwise an orphaned `approved`
        // application leaves the user stuck on «Заявка одобрена» and unable to re-apply.
        val cascaded = applicationRepository.deleteActiveByUserAndClub(targetUserId, clubId)
        log.info("Join rejected (refund): clubId={} targetUserId={} by={} hasReason={} cascadedApplications={}", clubId, targetUserId, callerId, reason != null, cascaded)
        notifyRejected(targetUserId, reason)
        return mapper.toDto(membership.copy(status = MembershipStatus.cancelled))
    }

    // Organizer kick (member admin): remove any member (active or frozen) from the club for cause. Unlike
    // «Закрыть доступ» (freeze = reversible pause, still a member) this cancels the membership and clears the
    // paid window so access is lost immediately. Reason is mandatory and DM'd to the member. Owner-only
    // (controller gate); the organizer can't be removed. Refund (if a paid member) is the organizer's offline
    // call — like reject, the platform is outside the money flow.
    @Transactional
    fun removeMember(clubId: UUID, targetUserId: UUID, callerId: UUID, reason: String): MembershipDto {
        val membership = loadManageableMember(clubId, targetUserId)
        if (membership.status == MembershipStatus.cancelled) {
            throw ValidationException("Участник уже не в клубе")
        }
        val cleanReason = reason.trim()
        if (cleanReason.length < MIN_REASON_LENGTH) {
            throw ValidationException("Причина должна быть не короче $MIN_REASON_LENGTH символов")
        }
        guardApplied(membershipRepository.remove(membership.id))
        // Clear any approved/pending application so the removed member can re-apply cleanly (no orphan
        // «Заявка одобрена»). Mirrors /leave + reject.
        val cascaded = applicationRepository.deleteActiveByUserAndClub(targetUserId, clubId)
        log.info("Member removed (kick): clubId={} targetUserId={} by={} cascadedApplications={}", clubId, targetUserId, callerId, cascaded)
        notifyRemoved(targetUserId, clubId, cleanReason)
        return mapper.toDto(membership.copy(status = MembershipStatus.cancelled, subscriptionExpiresAt = null))
    }

    // Best-effort DM to the removed member with the organizer's reason. Never aborts the removal.
    private fun notifyRemoved(targetUserId: UUID, clubId: UUID, reason: String) {
        try {
            val telegramId = userRepository.findById(targetUserId)?.telegramId ?: return
            val clubName = clubRepository.findById(clubId)?.name ?: "клуб"
            notificationService.sendDirectMessage(
                telegramId,
                "Организатор удалил вас из клуба «$clubName».\nПричина: $reason"
            )
        } catch (e: Exception) {
            log.warn("Failed to DM removed member (non-fatal): targetUserId={} error={}", targetUserId, e.message)
        }
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
        // Mirrors RemoveMemberRequest @Size(min=5); re-checked after trim in removeMember.
        private const val MIN_REASON_LENGTH = 5
        // The storage-relative path our uploader writes: "uploads/{uuid}.{ext}" (StorageController).
        private val UPLOADS_PATH = Regex("^uploads/[\\w.-]+\\.(jpg|jpeg|png)$", RegexOption.IGNORE_CASE)
    }
}
