package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.reputation.ReputationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class SkladchinaService(
    private val skladchinaRepository: SkladchinaRepository,
    private val clubRepository: ClubRepository,
    private val mapper: SkladchinaMapper,
    private val reputationService: ReputationService,
    private val notifier: SkladchinaNotifier
) {
    private val log = LoggerFactory.getLogger(SkladchinaService::class.java)

    @Transactional
    fun createSkladchina(clubId: UUID, request: CreateSkladchinaRequest, creatorId: UUID): SkladchinaDetailDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != creatorId) throw ForbiddenException("Only the club organizer can create skladchina")

        val mode = parseMode(request.paymentMode)
        validateRequest(request, mode)

        val now = OffsetDateTime.now()
        val deadlineMinAge = ChronoUnit.HOURS.between(now, request.deadline)
        val deadlineMaxAge = ChronoUnit.DAYS.between(now, request.deadline)
        if (deadlineMinAge < MIN_DEADLINE_HOURS) {
            throw ValidationException("Deadline must be at least $MIN_DEADLINE_HOURS hour ahead")
        }
        if (deadlineMaxAge > MAX_DEADLINE_DAYS) {
            throw ValidationException("Deadline must be at most $MAX_DEADLINE_DAYS days ahead")
        }

        val userIds = request.participants.map { it.userId }.distinct()
        if (userIds.size != request.participants.size) {
            throw ValidationException("Duplicate userId in participants list")
        }
        val notActive = skladchinaRepository.findNonActiveMembers(clubId, userIds)
        if (notActive.isNotEmpty()) {
            throw ForbiddenException("Some participants are not active members of this club")
        }

        val participants = buildParticipantsForCreate(mode, request)

        val skladchinaId = UUID.randomUUID()
        val domain = Skladchina(
            id = skladchinaId,
            clubId = clubId,
            creatorId = creatorId,
            title = request.title,
            description = request.description,
            rules = request.rules,
            photoUrl = request.photoUrl,
            paymentMode = mode,
            totalGoalKopecks = when (mode) {
                SkladchinaMode.voluntary -> null
                SkladchinaMode.fixed_equal -> request.totalGoalKopecks
                SkladchinaMode.fixed_individual -> participants.sumOf { it.second ?: 0L }
            },
            paymentLink = request.paymentLink,
            paymentMethodNote = request.paymentMethodNote,
            deadline = request.deadline,
            affectsReputation = request.affectsReputation,
            status = SkladchinaStatus.active,
            closedAt = null,
            closedBy = null,
            createdAt = now,
            updatedAt = now
        )

        val created = skladchinaRepository.create(domain, participants)
        log.info("Skladchina created: id={} clubId={} creatorId={} mode={} participants={}",
            created.id, clubId, creatorId, mode, participants.size)

        notifier.sendCreated(created, club.name, userIds)

        return getDetail(created.id, creatorId)
    }

    @Transactional(readOnly = true)
    fun getClubActiveSkladchinas(clubId: UUID, callerId: UUID): List<MySkladchinaListItemDto> {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        val skladchinas = skladchinaRepository.findActiveByClub(clubId)
        return skladchinas.map { s ->
            val collected = skladchinaRepository.sumCollectedKopecks(s.id)
            val totalParticipants = skladchinaRepository.countParticipants(s.id)
            val paid = skladchinaRepository.countParticipantsByStatus(s.id, SkladchinaParticipantStatus.paid)
            val callerParticipant = skladchinaRepository.findParticipant(s.id, callerId)
            mapper.toMyFeedItemDto(
                MySkladchinaFeedItem(
                    skladchina = s,
                    clubName = club.name,
                    clubAvatarUrl = club.avatarUrl,
                    myStatus = callerParticipant?.status,
                    collectedKopecks = collected,
                    participantCount = totalParticipants,
                    paidCount = paid
                ),
                callerId
            )
        }
    }

    @Transactional(readOnly = true)
    fun getDetail(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        val club = clubRepository.findById(skladchina.clubId)
            ?: throw NotFoundException("Club not found")

        // Access: creator OR active participant
        val isCreator = skladchina.creatorId == callerId
        val callerParticipant = skladchinaRepository.findParticipant(skladchinaId, callerId)
        if (!isCreator && callerParticipant == null) {
            throw ForbiddenException("Not allowed to view this skladchina")
        }

        val participants = skladchinaRepository.findParticipantsWithInfo(skladchinaId)
        val collected = skladchinaRepository.sumCollectedKopecks(skladchinaId)
        return mapper.toDetailDto(skladchina, club.name, club.avatarUrl, callerId, participants, collected)
    }

    @Transactional
    fun markPaid(skladchinaId: UUID, callerId: UUID, declaredAmountKopecks: Long): SkladchinaDetailDto {
        if (declaredAmountKopecks <= 0) throw ValidationException("Declared amount must be positive")

        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is not active")
        }
        val participant = skladchinaRepository.findParticipant(skladchinaId, callerId)
            ?: throw ForbiddenException("Not a participant of this skladchina")

        if (participant.status == SkladchinaParticipantStatus.paid) {
            // Idempotent — silently return current state.
            return getDetail(skladchinaId, callerId)
        }
        if (participant.status == SkladchinaParticipantStatus.declined) {
            throw ValidationException("You have already declined this skladchina")
        }

        skladchinaRepository.setParticipantPaid(skladchinaId, callerId, declaredAmountKopecks, OffsetDateTime.now())
        log.info("Skladchina mark-paid: id={} userId={} amount={}", skladchinaId, callerId, declaredAmountKopecks)

        maybeAutoCloseAfterStateChange(skladchinaId)

        return getDetail(skladchinaId, callerId)
    }

    @Transactional
    fun decline(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is not active")
        }
        val participant = skladchinaRepository.findParticipant(skladchinaId, callerId)
            ?: throw ForbiddenException("Not a participant of this skladchina")
        if (participant.status == SkladchinaParticipantStatus.declined) {
            throw ValidationException("Already declined")
        }
        if (participant.status == SkladchinaParticipantStatus.paid) {
            throw ValidationException("Already paid, cannot decline")
        }
        skladchinaRepository.setParticipantDeclined(skladchinaId, callerId, OffsetDateTime.now())
        log.info("Skladchina declined: id={} userId={}", skladchinaId, callerId)
        maybeAutoCloseAfterStateChange(skladchinaId)
        return getDetail(skladchinaId, callerId)
    }

    /**
     * Auto-close trigger that fires after mark-paid or decline. Closes if:
     *  - goal is reached (fixed-modes), OR
     *  - all participants are in a terminal status (paid / declined) — no more pending.
     * Quiet no-op otherwise. Errors don't propagate up — they're logged in closeInternal.
     */
    private fun maybeAutoCloseAfterStateChange(skladchinaId: UUID) {
        val skladchina = skladchinaRepository.findById(skladchinaId) ?: return
        if (skladchina.status != SkladchinaStatus.active) return

        if (skladchina.totalGoalKopecks != null) {
            val collected = skladchinaRepository.sumCollectedKopecks(skladchinaId)
            if (collected >= skladchina.totalGoalKopecks) {
                closeInternal(skladchinaId, closedBy = null, manualClose = false)
                return
            }
        }

        val pendingCount = skladchinaRepository.countParticipantsByStatus(
            skladchinaId, SkladchinaParticipantStatus.pending
        )
        if (pendingCount == 0) {
            closeInternal(skladchinaId, closedBy = null, manualClose = false)
        }
    }

    @Transactional
    fun closeManually(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.creatorId != callerId) {
            throw ForbiddenException("Only creator can close skladchina")
        }
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is already closed")
        }
        closeInternal(skladchinaId, closedBy = callerId, manualClose = true)
        return getDetail(skladchinaId, callerId)
    }

    /**
     * Internal helper, used by both manual close (creator) and auto-close (scheduler / goal-reached).
     * Determines final status, expires pending participants, applies reputation deltas idempotently,
     * notifies organizer.
     */
    fun closeInternal(skladchinaId: UUID, closedBy: UUID?, manualClose: Boolean) {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) {
            log.warn("closeInternal called on non-active skladchina {}: status={}", skladchinaId, skladchina.status)
            return
        }
        val club = clubRepository.findById(skladchina.clubId)
            ?: throw NotFoundException("Club not found")

        // Expire all pending participants first
        skladchinaRepository.expirePendingParticipants(skladchinaId)

        val collected = skladchinaRepository.sumCollectedKopecks(skladchinaId)
        val totalParticipants = skladchinaRepository.countParticipants(skladchinaId)
        val paidCount = skladchinaRepository.countParticipantsByStatus(skladchinaId, SkladchinaParticipantStatus.paid)

        val finalStatus = computeFinalStatus(skladchina, collected, manualClose)
        skladchinaRepository.updateStatus(skladchinaId, finalStatus, closedBy, OffsetDateTime.now())
        log.info("Skladchina closed: id={} status={} collected={} paid={}/{}",
            skladchinaId, finalStatus, collected, paidCount, totalParticipants)

        if (skladchina.affectsReputation) {
            applyReputationDeltas(skladchinaId, skladchina.clubId)
        }

        notifier.sendClosed(skladchina, club.name, finalStatus, collected, paidCount, totalParticipants)
    }

    private fun computeFinalStatus(
        skladchina: Skladchina,
        collected: Long,
        manualClose: Boolean
    ): SkladchinaStatus {
        val goal = skladchina.totalGoalKopecks
        return when {
            manualClose && (goal == null || collected < goal) -> SkladchinaStatus.cancelled
            goal == null && collected > 0 -> SkladchinaStatus.closed_success     // voluntary with any payments
            goal != null && collected >= goal -> SkladchinaStatus.closed_success
            goal != null && collected.toDouble() / goal >= SUCCESS_THRESHOLD -> SkladchinaStatus.closed_success
            else -> SkladchinaStatus.closed_failed
        }
    }

    private fun applyReputationDeltas(skladchinaId: UUID, clubId: UUID) {
        val participants = skladchinaRepository.findParticipants(skladchinaId)
        participants.forEach { p ->
            if (p.reputationApplied) return@forEach
            val delta = when (p.status) {
                SkladchinaParticipantStatus.paid -> REP_DELTA_PAID
                SkladchinaParticipantStatus.declined -> REP_DELTA_DECLINED
                SkladchinaParticipantStatus.expired_no_response -> REP_DELTA_NO_RESPONSE
                SkladchinaParticipantStatus.pending -> 0  // should be expired by now, fallback
            }
            if (delta != 0) {
                try {
                    reputationService.addReliabilityDelta(p.userId, clubId, delta, "skladchina_${p.status.literal}")
                } catch (e: Exception) {
                    log.error("Failed to apply reputation delta for user ${p.userId} skladchina $skladchinaId", e)
                }
            }
            skladchinaRepository.markReputationApplied(skladchinaId, p.userId)
        }
    }

    private fun parseMode(modeStr: String): SkladchinaMode =
        SkladchinaMode.values().find { it.literal == modeStr }
            ?: throw ValidationException("Invalid payment mode: $modeStr")

    private fun validateRequest(request: CreateSkladchinaRequest, mode: SkladchinaMode) {
        when (mode) {
            SkladchinaMode.fixed_equal -> {
                val total = request.totalGoalKopecks
                    ?: throw ValidationException("totalGoalKopecks required for fixed_equal mode")
                if (total <= 0) throw ValidationException("totalGoalKopecks must be positive")
            }
            SkladchinaMode.fixed_individual -> {
                request.participants.forEach { p ->
                    if (p.expectedAmountKopecks == null || p.expectedAmountKopecks <= 0) {
                        throw ValidationException("expectedAmountKopecks required for each participant in fixed_individual mode")
                    }
                }
            }
            SkladchinaMode.voluntary -> {
                // total ignored, expected ignored. Nothing to validate.
            }
        }
    }

    /**
     * For fixed_equal: split total equally with remainder going to last participant
     * (deterministic — last in sorted UUID order, see test).
     */
    private fun buildParticipantsForCreate(
        mode: SkladchinaMode,
        request: CreateSkladchinaRequest
    ): List<Pair<UUID, Long?>> {
        return when (mode) {
            SkladchinaMode.voluntary -> request.participants.map { it.userId to null }
            SkladchinaMode.fixed_individual -> request.participants.map { it.userId to it.expectedAmountKopecks }
            SkladchinaMode.fixed_equal -> {
                val total = request.totalGoalKopecks!!
                val n = request.participants.size
                val baseShare = total / n
                val remainder = total - baseShare * n
                val sorted = request.participants.sortedBy { it.userId }
                sorted.mapIndexed { idx, p ->
                    val amount = if (idx == n - 1) baseShare + remainder else baseShare
                    p.userId to amount
                }
            }
        }
    }

    companion object {
        private const val MIN_DEADLINE_HOURS = 1L
        private const val MAX_DEADLINE_DAYS = 90L
        private const val SUCCESS_THRESHOLD = 0.80     // fixed-mode: ≥80% → success at deadline
        private const val REP_DELTA_PAID = 10
        // Явный отказ слабее чем «не ответил» — мы наказываем безответственность,
        // не несогласие. См. фидбек #6 по skladchina-mvp staging.
        private const val REP_DELTA_DECLINED = -5
        private const val REP_DELTA_NO_RESPONSE = -25
    }
}
