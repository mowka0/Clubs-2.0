package com.clubs.reputation

import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.ReputationAxis
import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.enums.ReputationSource
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.REPUTATION_LEDGER
import com.clubs.generated.jooq.tables.references.SKLADCHINAS
import com.clubs.generated.jooq.tables.references.USER_CLUB_REPUTATION
import com.clubs.membership.MemberService
import com.clubs.membership.MembershipRepository
import com.clubs.membership.MembershipService
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test for the reputation v2 ledger pipeline against a real Postgres.
 * Covers the 5-kind attendance mapping, recompute parity, idempotency (bug B is dead),
 * anti-farm rule 1 (owner does not accrue in own club), confirmed_unresolved, the
 * finance axis, and the ownership-transfer characterization (PR-2 landmine).
 */
@SpringBootTest(
    properties = [
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=0",
        "telegram.bot-token=test-bot-token"
    ]
)
@Testcontainers
@ActiveProfiles("test")
class ReputationLedgerIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("clubs_test")
            .withUsername("test")
            .withPassword("test")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var reputationService: ReputationService
    @Autowired lateinit var reputationRepository: ReputationRepository
    @Autowired lateinit var memberService: MemberService
    @Autowired lateinit var membershipService: MembershipService
    @Autowired lateinit var membershipRepository: MembershipRepository
    @Autowired lateinit var xpService: XpService
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private lateinit var clubId: UUID
    private var nextTelegramId = 9000L

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM reputation_ledger")
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM skladchina_participants")
        dsl.execute("DELETE FROM skladchinas")
        dsl.execute("DELETE FROM user_club_reputation")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")

        ownerId = insertUser("Owner")
        clubId = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price, is_active)
            VALUES ('$clubId', '$ownerId', 'Club', 'desc', 'sport', 'open', 'Moscow', 20, 0, true)
            """.trimIndent()
        )
    }

    @Test
    fun `five-kind mapping produces exact reliability and counters`() {
        val eventId = insertFinalizedEvent()
        val ironclad = insertUser("Ironclad")
        val noShow = insertUser("NoShow")
        val spontaneous = insertUser("Spontaneous")
        val spectator = insertUser("Spectator")
        insertConfirmed(eventId, ironclad, "going", "attended")
        insertConfirmed(eventId, noShow, "going", "absent")
        insertConfirmed(eventId, spontaneous, "maybe", "attended")
        insertConfirmed(eventId, spectator, "maybe", "absent")

        reputationService.processFinalizedEvent(eventId)

        assertReputation(ironclad, reliability = 100, conf = 1, att = 1, spont = 0, pct = "100.00", outcome = 1)
        assertReputation(noShow, reliability = -200, conf = 1, att = 0, spont = 0, pct = "0.00", outcome = 1)
        assertReputation(spontaneous, reliability = 100, conf = 1, att = 1, spont = 1, pct = "100.00", outcome = 1)
        assertReputation(spectator, reliability = -200, conf = 1, att = 0, spont = 0, pct = "0.00", outcome = 1)
    }

    @Test
    fun `confirmed with null or disputed attendance is confirmed_unresolved`() {
        val eventId = insertFinalizedEvent()
        val unmarked = insertUser("Unmarked")
        val disputed = insertUser("Disputed")
        insertConfirmed(eventId, unmarked, "going", null)
        insertConfirmed(eventId, disputed, "maybe", "disputed")

        reputationService.processFinalizedEvent(eventId)

        // conf counts, attendance does not, reliability 0 — exact parity with legacy code.
        assertReputation(unmarked, reliability = 0, conf = 1, att = 0, spont = 0, pct = "0.00", outcome = 1)
        assertReputation(disputed, reliability = 0, conf = 1, att = 0, spont = 0, pct = "0.00", outcome = 1)
        assertEquals(ReputationKind.confirmed_unresolved, soleKind(unmarked, eventId))
    }

    @Test
    fun `non-confirmed responses produce no ledger row`() {
        val eventId = insertFinalizedEvent()
        val declined = insertUser("Declined")
        val noFinal = insertUser("NoFinal")
        // expired_no_confirm (Feature A) must score 0 exactly like declined / null — the
        // pipeline reads only final_status=confirmed, so "бронь сгорела" never reaches it.
        val expired = insertUser("Expired")
        insertResponse(eventId, declined, "going", "declined", "absent")
        insertResponse(eventId, noFinal, "maybe", null, null)
        insertResponse(eventId, expired, "going", "expired_no_confirm", null)

        reputationService.processFinalizedEvent(eventId)

        assertNull(reputationRepository.findByUserAndClub(declined, clubId))
        assertNull(reputationRepository.findByUserAndClub(noFinal, clubId))
        assertNull(reputationRepository.findByUserAndClub(expired, clubId))
        assertEquals(0, ledgerRows(declined, eventId))
        assertEquals(0, ledgerRows(expired, eventId))
    }

    @Test
    fun `processing twice is idempotent - one ledger row, no inflation`() {
        val eventId = insertFinalizedEvent()
        val member = insertUser("Member")
        insertConfirmed(eventId, member, "going", "attended")

        reputationService.processFinalizedEvent(eventId)
        reputationService.processFinalizedEvent(eventId) // bug B would inflate to 200 / count 2

        assertReputation(member, reliability = 100, conf = 1, att = 1, spont = 0, pct = "100.00", outcome = 1)
        assertEquals(1, ledgerRows(member, eventId))
        assertFalse(reputationRepository.claimEvent(eventId), "event already claimed")
    }

    @Test
    fun `anti-farm rule 1 - owner does not accrue in own club`() {
        val eventId = insertFinalizedEvent()
        insertConfirmed(eventId, ownerId, "going", "attended") // owner self-deals on own event

        reputationService.processFinalizedEvent(eventId)

        assertNull(reputationRepository.findByUserAndClub(ownerId, clubId), "owner has no cache row in own club")
        assertEquals(0, ledgerRows(ownerId, eventId), "owner has no ledger row")
    }

    @Test
    fun `poll finds pending finalized events and skips processed ones`() {
        val eventId = insertFinalizedEvent()
        val member = insertUser("Member")
        insertConfirmed(eventId, member, "going", "attended")

        assertTrue(eventId in reputationRepository.findPendingFinalizedEventIds())
        reputationService.processFinalizedEvent(eventId)
        assertFalse(eventId in reputationRepository.findPendingFinalizedEventIds())
    }

    @Test
    fun `neutrally finalized unmarked event yields no reputation (EXP-2)`() {
        // EXP-2 closes an unmarked past event with finalized=true / marked=false. The pipeline
        // claims only marked+finalized events, so such an event is invisible to the poll and a
        // direct claim no-ops — a confirmed-but-never-marked participant accrues nothing.
        val eventId = insertNeutrallyFinalizedEvent()
        val member = insertUser("Unscored")
        insertConfirmed(eventId, member, "going", null)

        assertFalse(eventId in reputationRepository.findPendingFinalizedEventIds())
        assertFalse(reputationRepository.claimEvent(eventId), "unmarked event cannot be claimed")
        reputationService.processFinalizedEvent(eventId)

        assertNull(reputationRepository.findByUserAndClub(member, clubId), "no reputation for a neutral close")
        assertEquals(0, ledgerRows(member, eventId))
    }

    @Test
    fun `finance entries contribute to reliability but not attendance counters`() {
        val member = insertUser("Payer")
        val skladchinaId = UUID.randomUUID()
        val entry = LedgerEntry(
            userId = member,
            clubId = clubId,
            axis = ReputationAxis.finance,
            kind = ReputationKind.skladchina_paid,
            points = ReputationPolicy.pointsFor(ReputationKind.skladchina_paid),
            occurredAt = OffsetDateTime.now(),
            sourceType = ReputationSource.skladchina,
            sourceId = skladchinaId
        )

        reputationService.appendAndRecompute(listOf(entry))

        assertReputation(member, reliability = 10, conf = 0, att = 0, spont = 0, pct = "0.00", outcome = 1)
    }

    @Test
    fun `mixed axes - reliability sums attendance and finance, counters stay attendance-only`() {
        val eventId = insertFinalizedEvent()
        val member = insertUser("Mixed")
        insertConfirmed(eventId, member, "going", "attended") // +100, conf/att
        reputationService.processFinalizedEvent(eventId)
        reputationService.appendAndRecompute(
            listOf(
                LedgerEntry(
                    member, clubId, ReputationAxis.finance, ReputationKind.skladchina_expired,
                    ReputationPolicy.pointsFor(ReputationKind.skladchina_expired), // -40
                    OffsetDateTime.now(), ReputationSource.skladchina, UUID.randomUUID()
                )
            )
        )

        assertReputation(member, reliability = 60, conf = 1, att = 1, spont = 0, pct = "100.00", outcome = 2)
    }

    @Test
    fun `recompute fills kept-broke-neutral counts by kind, invariant outcome = kept + broke + neutral (AC-P1b-1)`() {
        // P1b PR-0: Trust 0-100 is a Bayesian fraction over KEPT promises, classified BY KIND.
        // kept = {ironclad, spontaneous, skladchina_paid}; broke = {no_show, spectator,
        // skladchina_expired}; neutral = {confirmed_unresolved, skladchina_declined}.
        val member = insertUser("Counted")
        val e1 = insertFinalizedEvent(); insertConfirmed(e1, member, "going", "attended") // ironclad -> kept
        val e2 = insertFinalizedEvent(); insertConfirmed(e2, member, "going", "absent")   // no_show  -> broke
        val e3 = insertFinalizedEvent(); insertConfirmed(e3, member, "going", null)       // confirmed_unresolved -> neutral
        reputationService.processFinalizedEvent(e1)
        reputationService.processFinalizedEvent(e2)
        reputationService.processFinalizedEvent(e3)
        reputationService.appendAndRecompute(
            listOf(
                financeEntry(member, ReputationKind.skladchina_paid),   // kept
                financeEntry(member, ReputationKind.skladchina_expired) // broke
            )
        )

        val (kept, broke, neutral) = counts(member)
        assertEquals(2, kept, "kept_count (ironclad + skladchina_paid)")
        assertEquals(2, broke, "broke_count (no_show + skladchina_expired)")
        assertEquals(1, neutral, "neutral_count (confirmed_unresolved)")
        val outcome = reputationRepository.findByUserAndClub(member, clubId)!!.outcomeCount
        assertEquals(outcome, kept + broke + neutral, "invariant: outcome = kept + broke + neutral")
    }

    @Test
    fun `getMyReputation splits active vs История and aggregates global over ALL clubs (closes hole A)`() {
        val member = insertUser("Member")
        val seedTime = OffsetDateTime.now()
        // club1 (fixture clubId): ACTIVE membership + 3 kept → Trust high, reliable
        insertMembership(member, "member")
        reputationService.appendAndRecompute((1..3).map { financeEntry(member, ReputationKind.skladchina_paid, seedTime) })
        // club2: a LEFT club (cancelled membership, no remaining subscription) + 3 broke → Trust low
        val club2 = insertExtraClub("Left Club")
        dsl.execute(
            "INSERT INTO memberships (id, user_id, club_id, status, role, joined_at) " +
                "VALUES ('${UUID.randomUUID()}', '$member', '$club2', 'cancelled', 'member'::membership_role, NOW())"
        )
        reputationService.appendAndRecompute((1..3).map {
            LedgerEntry(
                member, club2, ReputationAxis.finance, ReputationKind.skladchina_expired,
                ReputationPolicy.pointsFor(ReputationKind.skladchina_expired), seedTime,
                ReputationSource.skladchina, UUID.randomUUID()
            )
        })

        val result = membershipService.getMyReputation(member)

        // Global is all-history: BOTH clubs have a track record, only the active one is reliable.
        assertEquals(2, result.global.trackRecordClubs, "left club still counts toward M")
        assertEquals(1, result.global.reliableClubs)
        assertTrue(result.global.score != null && result.global.score!! in 55..65, "score between the two clubs")
        // The left club lives in История, not the active list — but it is NOT dropped (hole A).
        assertEquals(listOf(clubId), result.activeClubs.map { it.clubId })
        assertEquals(listOf(club2), result.historyClubs.map { it.clubId })
        assertTrue(result.activeClubs.single().trust!! >= 90, "3 kept → high Trust")
        assertTrue(result.historyClubs.single().trust!! < 40, "3 broken promises → low Trust survives leaving")
    }

    @Test
    fun `TrustService computeForUser derives per-club Trust and an all-history global from the ledger`() {
        val member = insertUser("Trusted")
        val seedTime = OffsetDateTime.now()
        // 3 kept finance outcomes at age 0 (decay = 1) → keptW = 3.0
        // Trust = round(100 * (3 + 3*0.85) / (3 + 2*0 + 3)) = round(92.5) = 93
        reputationService.appendAndRecompute((1..3).map { financeEntry(member, ReputationKind.skladchina_paid, seedTime) })

        val result = TrustService(reputationRepository).computeForUser(member, seedTime)

        val club = result.perClub.single()
        assertEquals(clubId, club.clubId)
        assertEquals(3, club.outcomeCount)
        assertEquals(93, club.trust)
        assertEquals(1, result.global.trackRecordClubs)
        assertEquals(1, result.global.reliableClubs, "Trust 93 >= 70 → reliable")
        assertEquals(93, result.global.score)
    }

    @Test
    fun `recompute is owner-agnostic on existing ledger rows (PR-2 transfer landmine)`() {
        // Earned reputation survives an ownership transfer: recompute only sums existing
        // ledger rows, it does NOT re-apply rule 1 from the current owner. The real risk
        // is re-running the V18 backfill after a transfer (it re-reads owner_id) — PR-2
        // must freeze owner attribution at write time. See reputation-v2.md § Риски.
        val eventId = insertFinalizedEvent()
        val member = insertUser("FutureOwner")
        insertConfirmed(eventId, member, "going", "attended")
        reputationService.processFinalizedEvent(eventId)
        assertReputation(member, reliability = 100, conf = 1, att = 1, spont = 0, pct = "100.00", outcome = 1)

        dsl.execute("UPDATE clubs SET owner_id = '$member' WHERE id = '$clubId'")
        reputationRepository.recompute(member, clubId)

        // Still 100 — the member's earned row is not retroactively suppressed by recompute.
        assertReputation(member, reliability = 100, conf = 1, att = 1, spont = 0, pct = "100.00", outcome = 1)
    }

    @Test
    fun `read path suppresses sub-threshold Trust and sorts newcomers last (AC-4, AC-4b)`() {
        insertMembership(ownerId, "organizer")
        val veteran = insertUser("Veteran"); insertMembership(veteran, "member")
        val newcomer = insertUser("Newcomer"); insertMembership(newcomer, "member")

        // veteran: 3 ironclad events -> 3 kept, no broke -> Trust in [85, 93], outcome_count 3 (shown)
        repeat(3) {
            val e = insertFinalizedEvent()
            insertConfirmed(e, veteran, "going", "attended")
            reputationService.processFinalizedEvent(e)
        }
        // newcomer: a single no_show -> Trust low, but outcome_count 1 (< threshold, suppressed)
        val e = insertFinalizedEvent()
        insertConfirmed(e, newcomer, "going", "absent")
        reputationService.processFinalizedEvent(e)

        val members = memberService.getClubMembers(clubId, ownerId)
        val vet = members.first { it.userId == veteran }
        val newb = members.first { it.userId == newcomer }
        assertTrue(vet.trust != null && vet.trust >= 70, "veteran (>=3 kept outcomes) shows a high Trust")
        assertNull(newb.trust, "sub-threshold Trust is suppressed to null (Новичок)")
        assertNull(newb.promiseFulfillmentPct, "sub-threshold siblings are suppressed too")

        // Sorted by displayed Trust: the scored veteran sorts before the suppressed newcomer.
        val vetIdx = members.indexOfFirst { it.userId == veteran }
        val newbIdx = members.indexOfFirst { it.userId == newcomer }
        assertTrue(vetIdx < newbIdx, "newcomer (null Trust) sorts after the scored veteran")

        // Member profile read path applies the same suppression.
        assertNull(memberService.getMemberProfile(clubId, newcomer, ownerId).trust)
        assertTrue(memberService.getMemberProfile(clubId, veteran, ownerId).trust!! >= 70)
    }

    // --- PR-b: exit-with-obligations (AC-P1b-5) ---

    @Test
    fun `leaving a free club penalizes a confirmed booking before the cascade deletes it (AC-P1b-5)`() {
        val member = insertUser("Leaver"); insertMembership(member, "member")
        val eventId = insertActiveEvent()
        insertConfirmed(eventId, member, "going", null) // active confirmed booking, not yet happened

        membershipService.leaveClub(clubId, member)

        // −200 no_show written for the abandoned booking; counters match a natural no_show.
        assertReputation(member, reliability = -200, conf = 1, att = 0, spont = 0, pct = "0.00", outcome = 1)
        assertEquals(ReputationKind.no_show, soleKind(member, eventId))
        // occurred_at is the behaviour time (event datetime), not leave time — the decay anchor.
        assertEquals(eventDatetime(eventId).toInstant(), ledgerOccurredAt(member, eventId).toInstant())
        // the source booking is cascaded away AFTER the penalty landed.
        assertFalse(eventResponseExists(eventId, member), "confirmed booking cascaded away")
    }

    @Test
    fun `exit no_show is not doubled by a later natural close on the same event (UNIQUE, AC-P1b-5)`() {
        val member = insertUser("Leaver")
        val eventId = insertActiveEvent()
        reputationService.penalizeExit(
            member, clubId, listOf(ExitObligation(eventId, OffsetDateTime.now().plusDays(3))), emptyList()
        )
        assertReputation(member, reliability = -200, conf = 1, att = 0, spont = 0, pct = "0.00", outcome = 1)
        assertEquals(1, ledgerRows(member, eventId))

        // A later natural finalize tries to write a no_show for the same (user, event) → UNIQUE
        // collision, ON CONFLICT DO NOTHING. No second row, reliability stays −200 (not −400).
        reputationService.appendAndRecompute(
            listOf(
                LedgerEntry(
                    member, clubId, ReputationAxis.attendance, ReputationKind.no_show,
                    ReputationPolicy.pointsFor(ReputationKind.no_show), OffsetDateTime.now().plusDays(3),
                    ReputationSource.event, eventId
                )
            )
        )

        assertReputation(member, reliability = -200, conf = 1, att = 0, spont = 0, pct = "0.00", outcome = 1)
        assertEquals(1, ledgerRows(member, eventId), "still exactly one ledger row")
    }

    @Test
    fun `leaving penalizes a pending reputation skladchina with an unexpired deadline (−40)`() {
        val member = insertUser("Leaver"); insertMembership(member, "member")
        val deadline = OffsetDateTime.now().plusDays(2)
        val skladchinaId = insertActiveSkladchina(affectsReputation = true, deadline = deadline)
        insertPendingParticipant(skladchinaId, member)

        membershipService.leaveClub(clubId, member)

        assertReputation(member, reliability = -40, conf = 0, att = 0, spont = 0, pct = "0.00", outcome = 1)
        assertEquals(ReputationKind.skladchina_expired, soleKind(member, skladchinaId))
        assertEquals(skladchinaDeadline(skladchinaId).toInstant(), ledgerOccurredAt(member, skladchinaId).toInstant())
    }

    @Test
    fun `leaving penalizes a pending reputation skladchina even with a passed deadline (cascade would erase it)`() {
        val member = insertUser("Leaver"); insertMembership(member, "member")
        // Deadline already passed but the skladchina is still active (the expiry sweep hasn't fired).
        // The cascade deletes the pending row, so the exit path must own the −40 — otherwise it
        // escapes both the exit penalty and natural expiry (the reopened symmetric hole B).
        val skladchinaId = insertActiveSkladchina(affectsReputation = true, deadline = OffsetDateTime.now().minusHours(1))
        insertPendingParticipant(skladchinaId, member)

        membershipService.leaveClub(clubId, member)

        assertReputation(member, reliability = -40, conf = 0, att = 0, spont = 0, pct = "0.00", outcome = 1)
        assertEquals(ReputationKind.skladchina_expired, soleKind(member, skladchinaId))
        assertEquals(skladchinaDeadline(skladchinaId).toInstant(), ledgerOccurredAt(member, skladchinaId).toInstant())
    }

    @Test
    fun `leaving preserves a finalized-but-unprocessed event so the pipeline still writes the real outcome`() {
        val member = insertUser("Leaver"); insertMembership(member, "member")
        // attendance_finalized=true but status still stage_2 (completion is a separate later sweep);
        // reputation not yet processed. A real no_show the pipeline still owns.
        val eventId = insertFinalizedStage2Event()
        insertConfirmed(eventId, member, "going", "absent")

        membershipService.leaveClub(clubId, member)

        // No exit penalty for a finalized event, and the booking survives the cascade.
        assertEquals(0, ledgerRows(member, eventId), "no exit penalty for a finalized event")
        assertTrue(eventResponseExists(eventId, member), "finalized event's booking preserved for the pipeline")

        // The pipeline still produces the real outcome (−200 no_show) — not erased by leaving.
        reputationService.processFinalizedEvent(eventId)
        assertReputation(member, reliability = -200, conf = 1, att = 0, spont = 0, pct = "0.00", outcome = 1)
    }

    @Test
    fun `leaving does not penalize a pending non-reputation skladchina`() {
        val member = insertUser("Leaver"); insertMembership(member, "member")
        val skladchinaId = insertActiveSkladchina(affectsReputation = false, deadline = OffsetDateTime.now().plusDays(2))
        insertPendingParticipant(skladchinaId, member)

        membershipService.leaveClub(clubId, member)

        assertNull(reputationRepository.findByUserAndClub(member, clubId), "non-reputation skladchina never scores")
        assertEquals(0, ledgerRows(member, skladchinaId))
    }

    @Test
    fun `leaving a paid club writes no exit penalty and keeps the booking`() {
        val paidClub = insertPaidClub()
        val member = insertUser("PaidLeaver"); insertMembershipInClub(member, paidClub)
        val eventId = insertActiveEvent(club = paidClub)
        insertConfirmed(eventId, member, "going", null)

        membershipService.leaveClub(paidClub, member)

        // Paid leave keeps access until expire → no obligation broken, no cascade.
        assertNull(reputationRepository.findByUserAndClub(member, paidClub), "paid leave does not penalize")
        assertEquals(0, ledgerRows(member, eventId))
        assertTrue(eventResponseExists(eventId, member), "paid leave keeps the booking until expire")
    }

    @Test
    fun `free-club membership is created without a subscription expiry (no phantom paid period)`() {
        val member = insertUser("FreeJoiner")
        val created = membershipRepository.create(member, clubId) // fixture club is free (price 0)
        assertNull(created.subscriptionExpiresAt, "a free membership carries no subscription expiry")
    }

    @Test
    fun `leaving a free-priced club with an active paid period is a soft cancel (no penalty, no cascade)`() {
        val member = insertUser("PaidPeriodLeaver")
        // Free-priced club (fixture clubId, subscription_price=0) but the membership still holds a
        // future paid period (club was switched paid→free). Leave must route to the soft cancel —
        // no obligations-penalty, no cascade — because the user can still attend until expire.
        insertMembershipWithPaidPeriod(member, clubId, OffsetDateTime.now().plusMonths(1))
        val eventId = insertActiveEvent()
        insertConfirmed(eventId, member, "going", null)

        assertEquals(0, membershipService.getLeavePreview(clubId, member).totalObligations, "paid-period preview is zero")

        membershipService.leaveClub(clubId, member)

        assertNull(reputationRepository.findByUserAndClub(member, clubId), "soft cancel writes no penalty")
        assertEquals(0, ledgerRows(member, eventId))
        assertTrue(eventResponseExists(eventId, member), "soft cancel keeps the booking until expire")
    }

    @Test
    fun `leaving promotes the first waitlisted member into the vacated confirmed slot`() {
        val leaver = insertUser("Leaver"); insertMembership(leaver, "member")
        val waiter = insertUser("Waiter"); insertMembership(waiter, "member")
        val eventId = insertActiveEvent(limit = 1)
        insertConfirmed(eventId, leaver, "going", null)
        insertWaitlisted(eventId, waiter, OffsetDateTime.now().minusHours(1))

        membershipService.leaveClub(clubId, leaver)

        assertFalse(eventResponseExists(eventId, leaver), "leaver's booking removed")
        assertEquals(FinalStatus.confirmed, finalStatusOf(eventId, waiter), "waitlisted member promoted into the freed slot")
        // The leaver still eats the −200 — the slot refill mitigates the org's plan, it doesn't absolve the broken promise.
        assertReputation(leaver, reliability = -200, conf = 1, att = 0, spont = 0, pct = "0.00", outcome = 1)
    }

    @Test
    fun `leave preview counts open obligations for a free club, zero for a paid club`() {
        val member = insertUser("Previewer"); insertMembership(member, "member")
        val eventId = insertActiveEvent(); insertConfirmed(eventId, member, "going", null)
        val skladchinaId = insertActiveSkladchina(affectsReputation = true, deadline = OffsetDateTime.now().plusDays(2))
        insertPendingParticipant(skladchinaId, member)

        val preview = membershipService.getLeavePreview(clubId, member)
        assertEquals(1, preview.eventObligations)
        assertEquals(1, preview.skladchinaObligations)
        assertEquals(2, preview.totalObligations)

        // Paid club: obligations stay valid until expire → preview is all zeros.
        val paidClub = insertPaidClub()
        val paidMember = insertUser("PaidPreviewer"); insertMembershipInClub(paidMember, paidClub)
        val paidEvent = insertActiveEvent(club = paidClub); insertConfirmed(paidEvent, paidMember, "going", null)
        assertEquals(0, membershipService.getLeavePreview(paidClub, paidMember).totalObligations)
    }

    // --- PR-c: XP / уровни / бейджи (gamification) ---

    @Test
    fun `gamification sums participation XP plus diversity, derives level and badges from the ledger`() {
        val member = insertUser("Gamer")
        val seed = OffsetDateTime.now()
        // club1 (fixture): 3 ironclad attendances → 30 XP, trust ~93 (track record, reliable + rock-solid)
        repeat(3) {
            val e = insertFinalizedEvent()
            insertConfirmed(e, member, "going", "attended")
            reputationService.processFinalizedEvent(e)
        }
        // club2: 1 skladchina_paid → 3 XP. Two distinct kept clubs → 2×20 diversity.
        val club2 = insertExtraClub("Club2")
        reputationService.appendAndRecompute(
            listOf(
                LedgerEntry(
                    member, club2, ReputationAxis.finance, ReputationKind.skladchina_paid,
                    ReputationPolicy.pointsFor(ReputationKind.skladchina_paid), seed,
                    ReputationSource.skladchina, UUID.randomUUID()
                )
            )
        )

        val g = xpService.getGamification(member, seed)

        assertEquals(3 * 10 + 1 * 3 + 2 * 20, g.xp, "30 (ironclad) + 3 (paid) + 40 (diversity) = 73")
        assertEquals(2, g.level)        // 73 ∈ [40,144) → level index 1 → "Свой"
        assertEquals("Свой", g.levelName)
        assertEquals("Участник", g.nextLevelName)
        val badgeIds = g.badges.map { it.id }.toSet()
        assertTrue("first_step" in badgeIds)
        assertTrue("reliable_1" in badgeIds, "club1 trust ≥70")
        assertTrue("rock_solid" in badgeIds, "club1 trust ≥90")
        assertFalse("diverse_3" in badgeIds, "only 2 kept clubs")
    }

    @Test
    fun `a broken promise adds no XP and never lowers the level`() {
        val member = insertUser("NoShow")
        val eventId = insertFinalizedEvent()
        insertConfirmed(eventId, member, "going", "absent") // no_show
        reputationService.processFinalizedEvent(eventId)

        val g = xpService.getGamification(member)
        assertEquals(0, g.xp, "a broken promise is 0 XP, not a minus")
        assertEquals(1, g.level)
        assertEquals("Гость", g.levelName)
        assertTrue(g.badges.isEmpty())
    }

    // --- helpers ---

    private fun insertMembership(userId: UUID, role: String) {
        dsl.execute(
            "INSERT INTO memberships (id, user_id, club_id, status, role, joined_at) " +
                "VALUES ('${UUID.randomUUID()}', '$userId', '$clubId', 'active', '$role'::membership_role, NOW())"
        )
    }

    private fun insertExtraClub(name: String): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price, is_active)
            VALUES ('$id', '$ownerId', '$name', 'desc', 'sport', 'open', 'Moscow', 20, 0, true)
            """.trimIndent()
        )
        return id
    }

    private fun insertUser(name: String): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', ${nextTelegramId++}, '$name')")
        return id
    }

    private fun insertFinalizedEvent(): UUID {
        val id = UUID.randomUUID()
        val past = OffsetDateTime.now().minusDays(3)
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime,
                                participant_limit, voting_opens_days_before, status,
                                attendance_marked, attendance_finalized)
            VALUES ('$id', '$clubId', '$ownerId', 'Event', 'Place', '$past', 10, 14, 'completed', true, true)
            """.trimIndent()
        )
        return id
    }

    /** EXP-2: a past event closed neutrally — finalized but never marked. */
    private fun insertNeutrallyFinalizedEvent(): UUID {
        val id = UUID.randomUUID()
        val past = OffsetDateTime.now().minusDays(3)
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime,
                                participant_limit, voting_opens_days_before, status,
                                attendance_marked, attendance_finalized)
            VALUES ('$id', '$clubId', '$ownerId', 'Event', 'Place', '$past', 10, 14, 'completed', false, true)
            """.trimIndent()
        )
        return id
    }

    private fun insertConfirmed(eventId: UUID, userId: UUID, stage1: String, attendance: String?) =
        insertResponse(eventId, userId, stage1, "confirmed", attendance)

    /** An active (not-yet-finalized) event a member can hold a live confirmed booking on. */
    private fun insertActiveEvent(
        limit: Int = 10,
        datetime: OffsetDateTime = OffsetDateTime.now().plusDays(3),
        club: UUID = clubId
    ): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime,
                                participant_limit, voting_opens_days_before, status,
                                attendance_marked, attendance_finalized)
            VALUES ('$id', '$club', '$ownerId', 'Active', 'Place', '$datetime', $limit, 14, 'stage_2', false, false)
            """.trimIndent()
        )
        return id
    }

    /** A past event already attendance-finalized but still `stage_2` (completion is a later sweep). */
    private fun insertFinalizedStage2Event(): UUID {
        val id = UUID.randomUUID()
        val past = OffsetDateTime.now().minusHours(2)
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime,
                                participant_limit, voting_opens_days_before, status,
                                attendance_marked, attendance_finalized)
            VALUES ('$id', '$clubId', '$ownerId', 'Finalized', 'Place', '$past', 10, 14, 'stage_2', true, true)
            """.trimIndent()
        )
        return id
    }

    private fun insertWaitlisted(eventId: UUID, userId: UUID, stage1Timestamp: OffsetDateTime) {
        dsl.execute(
            """
            INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, stage_2_vote, final_status, stage_1_timestamp)
            VALUES ('${UUID.randomUUID()}', '$eventId', '$userId',
                    'going'::stage_1_vote, 'waitlisted'::stage_2_vote, 'waitlisted'::final_status, '$stage1Timestamp')
            """.trimIndent()
        )
    }

    private fun insertActiveSkladchina(affectsReputation: Boolean, deadline: OffsetDateTime): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO skladchinas (id, club_id, creator_id, title, payment_mode, payment_link, deadline, affects_reputation, status)
            VALUES ('$id', '$clubId', '$ownerId', 'Sbor', 'voluntary'::skladchina_mode, 'http://pay',
                    '$deadline', $affectsReputation, 'active'::skladchina_status)
            """.trimIndent()
        )
        return id
    }

    private fun insertPendingParticipant(skladchinaId: UUID, userId: UUID) {
        dsl.execute(
            """
            INSERT INTO skladchina_participants (skladchina_id, user_id, status)
            VALUES ('$skladchinaId', '$userId', 'pending'::skladchina_participant_status)
            """.trimIndent()
        )
    }

    private fun insertPaidClub(): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price, is_active)
            VALUES ('$id', '$ownerId', 'Paid', 'desc', 'sport', 'open', 'Moscow', 20, 100, true)
            """.trimIndent()
        )
        return id
    }

    private fun insertMembershipWithPaidPeriod(userId: UUID, club: UUID, expiresAt: OffsetDateTime) {
        dsl.execute(
            "INSERT INTO memberships (id, user_id, club_id, status, role, joined_at, subscription_expires_at) " +
                "VALUES ('${UUID.randomUUID()}', '$userId', '$club', 'active', 'member'::membership_role, NOW(), '$expiresAt')"
        )
    }

    private fun insertMembershipInClub(userId: UUID, club: UUID, role: String = "member") {
        dsl.execute(
            "INSERT INTO memberships (id, user_id, club_id, status, role, joined_at) " +
                "VALUES ('${UUID.randomUUID()}', '$userId', '$club', 'active', '$role'::membership_role, NOW())"
        )
    }

    private fun eventResponseExists(eventId: UUID, userId: UUID): Boolean =
        dsl.fetchCount(EVENT_RESPONSES, EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.USER_ID.eq(userId))) > 0

    private fun finalStatusOf(eventId: UUID, userId: UUID): FinalStatus? =
        dsl.select(EVENT_RESPONSES.FINAL_STATUS).from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.USER_ID.eq(userId)))
            .fetchOne(EVENT_RESPONSES.FINAL_STATUS)

    private fun eventDatetime(eventId: UUID): OffsetDateTime =
        dsl.select(EVENTS.EVENT_DATETIME).from(EVENTS).where(EVENTS.ID.eq(eventId)).fetchOne(EVENTS.EVENT_DATETIME)!!

    private fun skladchinaDeadline(skladchinaId: UUID): OffsetDateTime =
        dsl.select(SKLADCHINAS.DEADLINE).from(SKLADCHINAS).where(SKLADCHINAS.ID.eq(skladchinaId)).fetchOne(SKLADCHINAS.DEADLINE)!!

    private fun ledgerOccurredAt(userId: UUID, sourceId: UUID): OffsetDateTime =
        dsl.select(REPUTATION_LEDGER.OCCURRED_AT).from(REPUTATION_LEDGER)
            .where(REPUTATION_LEDGER.USER_ID.eq(userId).and(REPUTATION_LEDGER.SOURCE_ID.eq(sourceId)))
            .fetchOne(REPUTATION_LEDGER.OCCURRED_AT)!!

    private fun insertResponse(eventId: UUID, userId: UUID, stage1: String?, finalStatus: String?, attendance: String?) {
        val s1 = stage1?.let { "'$it'::stage_1_vote" } ?: "NULL"
        val fs = finalStatus?.let { "'$it'::final_status" } ?: "NULL"
        val att = attendance?.let { "'$it'::attendance_status" } ?: "NULL"
        dsl.execute(
            """
            INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, final_status, attendance)
            VALUES ('${UUID.randomUUID()}', '$eventId', '$userId', $s1, $fs, $att)
            """.trimIndent()
        )
    }

    private fun ledgerRows(userId: UUID, sourceId: UUID): Int =
        dsl.fetchCount(REPUTATION_LEDGER, REPUTATION_LEDGER.USER_ID.eq(userId).and(REPUTATION_LEDGER.SOURCE_ID.eq(sourceId)))

    private fun soleKind(userId: UUID, sourceId: UUID): ReputationKind =
        dsl.select(REPUTATION_LEDGER.KIND).from(REPUTATION_LEDGER)
            .where(REPUTATION_LEDGER.USER_ID.eq(userId).and(REPUTATION_LEDGER.SOURCE_ID.eq(sourceId)))
            .fetchOne(REPUTATION_LEDGER.KIND)!!

    private fun assertReputation(
        userId: UUID, reliability: Int, conf: Int, att: Int, spont: Int, pct: String, outcome: Int
    ) {
        val r = reputationRepository.findByUserAndClub(userId, clubId)
            ?: error("expected a reputation row for $userId")
        assertEquals(reliability, r.reliabilityIndex, "reliabilityIndex")
        assertEquals(conf, r.totalConfirmations, "totalConfirmations")
        assertEquals(att, r.totalAttendances, "totalAttendances")
        assertEquals(spont, r.spontaneityCount, "spontaneityCount")
        assertEquals(outcome, r.outcomeCount, "outcomeCount")
        assertEquals(0, BigDecimal(pct).compareTo(r.promiseFulfillmentPct), "promiseFulfillmentPct ($pct vs ${r.promiseFulfillmentPct})")
    }

    private fun financeEntry(userId: UUID, kind: ReputationKind, occurredAt: OffsetDateTime = OffsetDateTime.now()) = LedgerEntry(
        userId, clubId, ReputationAxis.finance, kind, ReputationPolicy.pointsFor(kind),
        occurredAt, ReputationSource.skladchina, UUID.randomUUID()
    )

    /** Reads the P1b kept/broke/neutral cache columns directly — PR-0 writes them; the read
     *  path (TrustService) lands in PR-a, so the test queries the table straight through dsl. */
    private fun counts(userId: UUID): Triple<Int, Int, Int> {
        val r = dsl.select(USER_CLUB_REPUTATION.KEPT_COUNT, USER_CLUB_REPUTATION.BROKE_COUNT, USER_CLUB_REPUTATION.NEUTRAL_COUNT)
            .from(USER_CLUB_REPUTATION)
            .where(USER_CLUB_REPUTATION.USER_ID.eq(userId).and(USER_CLUB_REPUTATION.CLUB_ID.eq(clubId)))
            .fetchOne() ?: error("no reputation row for $userId")
        return Triple(r.value1()!!, r.value2()!!, r.value3()!!)
    }
}
