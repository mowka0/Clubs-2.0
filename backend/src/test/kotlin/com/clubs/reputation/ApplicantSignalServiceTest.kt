package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.user.QuestFlags
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class ApplicantSignalServiceTest {

    private val reputationRepository: ReputationRepository = mockk()
    private val userRepository: UserRepository = mockk {
        // По умолчанию — без достигнутых вех профиль-квеста (переопределяется в тестах квеста).
        every { findQuestFlagsByIds(any()) } returns emptyMap()
    }

    // Real Trust/Xp policies — only findOutcomesByUserIds is stubbed; globalForOutcomes /
    // levelForOutcomes are pure and never touch the repository.
    private val service = ApplicantSignalService(
        reputationRepository,
        TrustService(reputationRepository),
        XpService(reputationRepository, userRepository),
        userRepository
    )

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-06-14T12:00:00Z")

    private fun outcome(clubId: UUID, kind: ReputationKind) = ClubLedgerOutcome(clubId, kind, now)

    @Test
    fun `derives N из M plus level from the ledger in one batch`() {
        val userId = UUID.randomUUID()
        val reliableClub = UUID.randomUUID()      // 3 kept → Trust ~93 (reliable, counts toward N and M)
        val unreliableClub = UUID.randomUUID()    // 3 broke → Trust ~28 (track record, NOT reliable)
        val belowGateClub = UUID.randomUUID()     // 2 kept → below MIN_OUTCOMES_FOR_DISPLAY, not in M

        every { reputationRepository.findOutcomesByUserIds(listOf(userId)) } returns mapOf(
            userId to listOf(
                outcome(reliableClub, ReputationKind.ironclad),
                outcome(reliableClub, ReputationKind.ironclad),
                outcome(reliableClub, ReputationKind.ironclad),
                outcome(unreliableClub, ReputationKind.no_show),
                outcome(unreliableClub, ReputationKind.no_show),
                outcome(unreliableClub, ReputationKind.no_show),
                outcome(belowGateClub, ReputationKind.ironclad),
                outcome(belowGateClub, ReputationKind.ironclad)
            )
        )

        val signal = service.signalsFor(listOf(userId), now).getValue(userId)

        assertEquals(1, signal.reliableClubs, "only the all-kept club is reliable (Trust ≥ 70)")
        assertEquals(2, signal.trackRecordClubs, "below-gate club (2 outcomes) is excluded from M")
        // XP = 5 kept ironclad ×10 + 2 distinct kept clubs ×20 = 90 → level index 1 (Свой).
        assertEquals(2, signal.level)
        assertEquals("Свой", signal.levelName)
        assertEquals("base", signal.levelTier)
    }

    @Test
    fun `an applicant with no ledger outcomes gets the empty signal`() {
        val userId = UUID.randomUUID()
        every { reputationRepository.findOutcomesByUserIds(listOf(userId)) } returns emptyMap()

        val signal = service.signalsFor(listOf(userId), now).getValue(userId)

        assertEquals(ApplicantSignal.EMPTY, signal)
    }

    @Test
    fun `empty input short-circuits without a query`() {
        assertEquals(emptyMap<UUID, ApplicantSignal>(), service.signalsFor(emptyList(), now))
    }

    @Test
    fun `others-projection counts profile-quest XP - full quest alone shows level 2`() {
        // AC-6 profile-quest.md: организатор видит уровень С УЧЁТОМ профильного XP —
        // заявитель без единого ledger-исхода, но с заполненным профилем = «Свой», не «Гость».
        val userId = UUID.randomUUID()
        val at = now
        every { reputationRepository.findOutcomesByUserIds(listOf(userId)) } returns emptyMap()
        every { userRepository.findQuestFlagsByIds(listOf(userId)) } returns mapOf(
            userId to QuestFlags(cityAt = at, interestsAt = at, bioAt = at)
        )

        val signal = service.signalsFor(listOf(userId), now).getValue(userId)

        assertEquals(2, signal.level)
        assertEquals("Свой", signal.levelName)
        assertEquals(0, signal.trackRecordClubs, "профильный XP не создаёт track record")
    }
}
