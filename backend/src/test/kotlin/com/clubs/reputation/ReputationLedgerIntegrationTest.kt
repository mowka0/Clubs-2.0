package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationAxis
import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.enums.ReputationSource
import com.clubs.generated.jooq.tables.references.REPUTATION_LEDGER
import com.clubs.membership.MemberService
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
        assertReputation(noShow, reliability = -50, conf = 1, att = 0, spont = 0, pct = "0.00", outcome = 1)
        assertReputation(spontaneous, reliability = 100, conf = 1, att = 1, spont = 1, pct = "100.00", outcome = 1)
        assertReputation(spectator, reliability = -50, conf = 1, att = 0, spont = 0, pct = "0.00", outcome = 1)
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
                LedgerEntry(member, clubId, ReputationAxis.finance, ReputationKind.skladchina_expired, -25, OffsetDateTime.now(), ReputationSource.skladchina, UUID.randomUUID())
            )
        )

        assertReputation(member, reliability = 75, conf = 1, att = 1, spont = 0, pct = "100.00", outcome = 2)
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
    fun `read path suppresses sub-threshold reliability and sorts newcomers last (AC-4, AC-4b)`() {
        insertMembership(ownerId, "organizer")
        val veteran = insertUser("Veteran"); insertMembership(veteran, "member")
        val newcomer = insertUser("Newcomer"); insertMembership(newcomer, "member")

        // veteran: 3 ironclad events -> reliability 300, outcome_count 3 (>= threshold, shown)
        repeat(3) {
            val e = insertFinalizedEvent()
            insertConfirmed(e, veteran, "going", "attended")
            reputationService.processFinalizedEvent(e)
        }
        // newcomer: a single no_show -> reliability -50, outcome_count 1 (< threshold, suppressed)
        val e = insertFinalizedEvent()
        insertConfirmed(e, newcomer, "going", "absent")
        reputationService.processFinalizedEvent(e)

        val members = memberService.getClubMembers(clubId, ownerId)
        val vet = members.first { it.userId == veteran }
        val newb = members.first { it.userId == newcomer }
        assertEquals(300, vet.reliabilityIndex, "veteran (>=3 outcomes) shows the real number")
        assertNull(newb.reliabilityIndex, "sub-threshold reliability is suppressed to null (Новичок)")
        assertNull(newb.promiseFulfillmentPct, "sub-threshold siblings are suppressed too")

        // NULLS LAST: the scored veteran sorts before the suppressed newcomer.
        val vetIdx = members.indexOfFirst { it.userId == veteran }
        val newbIdx = members.indexOfFirst { it.userId == newcomer }
        assertTrue(vetIdx < newbIdx, "newcomer (null) sorts after the scored veteran")

        // Member profile read path applies the same suppression.
        assertNull(memberService.getMemberProfile(clubId, newcomer, ownerId).reliabilityIndex)
        assertEquals(300, memberService.getMemberProfile(clubId, veteran, ownerId).reliabilityIndex)
    }

    // --- helpers ---

    private fun insertMembership(userId: UUID, role: String) {
        dsl.execute(
            "INSERT INTO memberships (id, user_id, club_id, status, role, joined_at) " +
                "VALUES ('${UUID.randomUUID()}', '$userId', '$clubId', 'active', '$role'::membership_role, NOW())"
        )
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
}
