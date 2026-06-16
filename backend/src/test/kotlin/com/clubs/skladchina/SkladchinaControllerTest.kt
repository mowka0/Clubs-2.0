package com.clubs.skladchina

import com.clubs.auth.JwtService
import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.tables.references.REPUTATION_LEDGER
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=0",
        "telegram.bot-token=test-bot-token"
    ]
)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class SkladchinaControllerTest {

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

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var dsl: DSLContext
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var skladchinaService: SkladchinaService
    @Autowired lateinit var skladchinaRepository: SkladchinaRepository
    @Autowired lateinit var rateLimitFilter: com.clubs.common.security.RateLimitFilter

    private lateinit var organizerId: UUID
    private lateinit var memberAId: UUID
    private lateinit var memberBId: UUID
    private lateinit var outsiderId: UUID
    private lateinit var clubId: UUID
    private lateinit var organizerToken: String
    private lateinit var memberAToken: String
    private lateinit var memberBToken: String
    private lateinit var outsiderToken: String

    @BeforeEach
    fun setUp() {
        // Filter runs before auth → MockMvc requests share one ip:127.0.0.1 bucket. Reset per
        // test so the shared 60/min API bucket isn't drained across the whole suite.
        rateLimitFilter.resetBuckets()
        dsl.execute("DELETE FROM reputation_ledger")
        dsl.execute("DELETE FROM skladchina_participants")
        dsl.execute("DELETE FROM skladchinas")
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM applications")
        dsl.execute("DELETE FROM transactions")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM user_club_reputation")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")

        organizerId = UUID.randomUUID()
        memberAId = UUID.randomUUID()
        memberBId = UUID.randomUUID()
        outsiderId = UUID.randomUUID()
        clubId = UUID.randomUUID()

        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$organizerId', 3001, 'Organizer')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$memberAId', 3002, 'MemberA')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$memberBId', 3003, 'MemberB')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$outsiderId', 3004, 'Outsider')")

        organizerToken = jwtService.generateToken(organizerId, 3001L)
        memberAToken = jwtService.generateToken(memberAId, 3002L)
        memberBToken = jwtService.generateToken(memberBId, 3003L)
        outsiderToken = jwtService.generateToken(outsiderId, 3004L)

        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price)
            VALUES ('$clubId', '$organizerId', 'Test Club', 'desc', 'sport', 'open', 'Moscow', 20, 0)
            """.trimIndent()
        )
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$organizerId', '$clubId', 'active', 'organizer')")
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$memberAId', '$clubId', 'active', 'member')")
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$memberBId', '$clubId', 'active', 'member')")
    }

    @Test
    fun `POST create skladchina as organizer returns 201 and DTO with split amounts`() {
        val body = """
            {
              "title": "Booking the court",
              "description": "Saturday morning",
              "paymentMode": "fixed_equal",
              "totalGoalKopecks": 100000,
              "paymentLink": "https://tinkoff.ru/pay/xyz",
              "deadline": "${OffsetDateTime.now().plusDays(2)}",
              "affectsReputation": false,
              "participants": [
                {"userId": "$memberAId"},
                {"userId": "$memberBId"}
              ]
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.paymentMode").value("fixed_equal"))
            .andExpect(jsonPath("$.totalGoalKopecks").value(100000))
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.isOrganizerView").value(true))
            .andExpect(jsonPath("$.participants.length()").value(2))
            .andExpect(jsonPath("$.participantCount").value(2))
            .andExpect(jsonPath("$.paidCount").value(0))
    }

    @Test
    fun `POST create as non-organizer member returns 403`() {
        val body = createBodyFor(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `POST create with non-member participant returns 403`() {
        val body = createBodyFor(listOf(memberAId, outsiderId))
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `mark-paid sets status returns updated DTO and is idempotent on repeat`() {
        val id = createSkladchina(listOf(memberAId, memberBId))

        // First mark-paid
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 50000}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myStatus").value("paid"))
            .andExpect(jsonPath("$.myDeclaredAmountKopecks").value(50000))
            .andExpect(jsonPath("$.collectedKopecks").value(50000))

        // Idempotent repeat — no error, current state
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 50000}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myStatus").value("paid"))
    }

    @Test
    fun `decline transitions to declined and cannot mark-paid afterwards`() {
        val id = createSkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/skladchinas/$id/decline")
                .header("Authorization", "Bearer $memberAToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myStatus").value("declined"))

        // Cannot mark-paid after decline
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 50000}""")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET detail as non-participant non-creator returns 403`() {
        val id = createSkladchina(listOf(memberAId))  // memberB not participant
        mockMvc.perform(
            get("/api/skladchinas/$id")
                .header("Authorization", "Bearer $memberBToken")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `GET detail as member hides participants list`() {
        val id = createSkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            get("/api/skladchinas/$id")
                .header("Authorization", "Bearer $memberAToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isOrganizerView").value(false))
            .andExpect(jsonPath("$.participants").doesNotExist())
            .andExpect(jsonPath("$.participantCount").value(2))
    }

    @Test
    fun `all-answered triggers auto-close to closed_success`() {
        // Goal = 100, two members fixed_equal → 50 each. Both pay → no pending left → closed.
        // (Phase A: closure is by everyone-answered, not by goal-reached — see the test below.)
        val id = createSkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberBToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("closed_success"))
    }

    @Test
    fun `goal reached while a participant is still pending does NOT auto-close (Phase A A-4)`() {
        // Voluntary with goal 50000; memberA declares 60000 (≥ goal) but memberB stays pending.
        // Pre-Phase-A this force-closed on goal-reached; now money is decoration → stays active.
        val id = createVoluntaryWithGoal(listOf(memberAId, memberBId), 50000)
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 60000}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.collectedKopecks").value(60000))
        assertEquals("active", skladchinaStatus(id))
        assertEquals("pending", participantStatus(id, memberBId))
    }

    @Test
    fun `manual close as creator works but non-creator gets 403`() {
        val id = createSkladchina(listOf(memberAId))
        // Non-creator cannot close
        mockMvc.perform(
            post("/api/skladchinas/$id/close")
                .header("Authorization", "Bearer $memberAToken")
        )
            .andExpect(status().isForbidden)

        // Creator closes — collected = 0, no goal reached → cancelled
        mockMvc.perform(
            post("/api/skladchinas/$id/close")
                .header("Authorization", "Bearer $organizerToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("cancelled"))
    }

    @Test
    fun `GET me-skladchinas returns active skladchinas only for participant`() {
        val id1 = createSkladchina(listOf(memberAId))   // memberA participant
        createSkladchina(listOf(memberBId))             // memberB participant, memberA not — must not leak into A's feed

        mockMvc.perform(
            get("/api/users/me/skladchinas")
                .header("Authorization", "Bearer $memberAToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(id1.toString()))
    }

    @Test
    fun `GET clubs id skladchinas active as organizer returns list`() {
        createSkladchina(listOf(memberAId))
        createSkladchina(listOf(memberBId))
        mockMvc.perform(
            get("/api/clubs/$clubId/skladchinas/active")
                .header("Authorization", "Bearer $organizerToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `validation - deadline in past returns 400`() {
        val body = """
            {
              "title": "Bad",
              "paymentMode": "fixed_equal",
              "totalGoalKopecks": 100000,
              "paymentLink": "https://x.com",
              "deadline": "${OffsetDateTime.now().minusDays(1)}",
              "participants": [{"userId": "$memberAId"}]
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `validation - voluntary mode allows missing goal`() {
        val body = """
            {
              "title": "Voluntary fund",
              "paymentMode": "voluntary",
              "paymentLink": "https://x.com",
              "deadline": "${OffsetDateTime.now().plusDays(2)}",
              "participants": [{"userId": "$memberAId"}]
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.paymentMode").value("voluntary"))
            .andExpect(jsonPath("$.totalGoalKopecks").doesNotExist())
    }

    @Test
    fun `create voluntary with optional goal persists it and rejects non-positive`() {
        // Staging feedback 2026-06-12: gift pools want an indicative target too.
        val participants = """{"userId": "$memberAId"}"""
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "title": "Voluntary with goal",
                      "paymentMode": "voluntary",
                      "totalGoalKopecks": 500000,
                      "paymentLink": "https://x.com",
                      "deadline": "${OffsetDateTime.now().plusDays(2)}",
                      "participants": [$participants]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.paymentMode").value("voluntary"))
            .andExpect(jsonPath("$.totalGoalKopecks").value(500000))

        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "title": "Voluntary bad goal",
                      "paymentMode": "voluntary",
                      "totalGoalKopecks": 0,
                      "paymentLink": "https://x.com",
                      "deadline": "${OffsetDateTime.now().plusDays(2)}",
                      "participants": [$participants]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
    }

    // ---- declared-amount validation (redesign § Валидации) ----

    @Test
    fun `mark-paid in fixed mode records the assigned share, ignoring the client value`() {
        // Server-authoritative recording (staging bug 2026-06-12): the UI rounds kopecks
        // to whole rubles, so a strict declared == expected check rejected honest payments
        // of non-divisible shares. The client value must not be able to inflate `collected`
        // (F5-02 amplifier) — nor block a payment over a 33-kopeck rounding gap.
        val id = createSkladchina(listOf(memberAId, memberBId))  // fixed_equal, 50000 each
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 100000}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myStatus").value("paid"))
            .andExpect(jsonPath("$.myDeclaredAmountKopecks").value(50000))
            .andExpect(jsonPath("$.collectedKopecks").value(50000))
    }

    @Test
    fun `mark-paid voluntary above sanity cap returns 400, below cap is accepted`() {
        val id = createVoluntarySkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 10000001}""")
        )
            .andExpect(status().isBadRequest)

        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 10000000}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myStatus").value("paid"))
    }

    // ---- "важный сбор" toggle gates ----

    @Test
    fun `create reputation-affecting voluntary skladchina returns 400`() {
        val body = """
            {
              "title": "Voluntary punitive",
              "paymentMode": "voluntary",
              "paymentLink": "https://x.com",
              "deadline": "${OffsetDateTime.now().plusDays(2)}",
              "affectsReputation": true,
              "participants": [{"userId": "$memberAId"}]
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `create reputation-affecting skladchina with deadline under 24h returns 400`() {
        // 2h ahead passes the generic 1h-minimum but fails the 24h reputation gate.
        val body = """
            {
              "title": "Ambush",
              "paymentMode": "fixed_equal",
              "totalGoalKopecks": 100000,
              "paymentLink": "https://x.com",
              "deadline": "${OffsetDateTime.now().plusHours(2)}",
              "affectsReputation": true,
              "participants": [{"userId": "$memberAId"}]
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `fourth reputation-affecting skladchina within 7 days returns 400`() {
        repeat(3) {
            mockMvc.perform(
                post("/api/clubs/$clubId/skladchinas")
                    .header("Authorization", "Bearer $organizerToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRepBodyFor(listOf(memberAId, memberBId)))
            ).andExpect(status().isCreated)
        }
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRepBodyFor(listOf(memberAId, memberBId)))
        )
            .andExpect(status().isBadRequest)

        // A non-reputation skladchina is NOT rate-limited.
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBodyFor(listOf(memberAId, memberBId)))
        )
            .andExpect(status().isCreated)
    }

    // ---- released vs expired predicate (F5-02) ----

    @Test
    fun `early close releases pending participants - no ledger row, paid keeps +10`() {
        val id = createRepSkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 50000}""")
        ).andExpect(status().isOk)

        // Manual close BEFORE the deadline: memberB never answered, but the deadline
        // never came — released, neutral.
        mockMvc.perform(
            post("/api/skladchinas/$id/close")
                .header("Authorization", "Bearer $organizerToken")
        ).andExpect(status().isOk)

        assertEquals("released", participantStatus(id, memberBId))
        assertEquals(0, ledgerRows(memberBId, id), "released participant must have NO ledger row")
        assertEquals(1, ledgerRows(memberAId, id), "paid participant accrues +10")
        assertEquals(ReputationKind.skladchina_paid, soleLedgerKind(memberAId, id))
        assertEquals(10, soleLedgerPoints(memberAId, id))
    }

    @Test
    fun `close at deadline expires pending participants with -40 ledger row`() {
        val id = createRepSkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 50000}""")
        ).andExpect(status().isOk)

        // Simulate the deadline passing, then the scheduler path closing it.
        dsl.execute("UPDATE skladchinas SET deadline = NOW() - INTERVAL '1 hour' WHERE id = '$id'")
        skladchinaService.closeInternal(id, closedBy = null, manualClose = false)

        assertEquals("expired_no_response", participantStatus(id, memberBId))
        assertEquals(ReputationKind.skladchina_expired, soleLedgerKind(memberBId, id))
        assertEquals(-40, soleLedgerPoints(memberBId, id))
        assertEquals(10, soleLedgerPoints(memberAId, id))
    }

    // ---- F5-03: pending-only transition guard ----

    @Test
    fun `mark-paid after concurrent expiry returns 409 and keeps the terminal status`() {
        val id = createSkladchina(listOf(memberAId, memberBId))
        // The race's intermediate state: skladchina still active, participant already resolved.
        dsl.execute(
            "UPDATE skladchina_participants SET status = 'expired_no_response' " +
                "WHERE skladchina_id = '$id' AND user_id = '$memberAId'"
        )
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 50000}""")
        )
            .andExpect(status().isConflict)
        assertEquals("expired_no_response", participantStatus(id, memberAId))
    }

    @Test
    fun `decline after concurrent expiry returns 409`() {
        val id = createSkladchina(listOf(memberAId, memberBId))
        dsl.execute(
            "UPDATE skladchina_participants SET status = 'expired_no_response' " +
                "WHERE skladchina_id = '$id' AND user_id = '$memberAId'"
        )
        mockMvc.perform(
            post("/api/skladchinas/$id/decline")
                .header("Authorization", "Bearer $memberAToken")
        )
            .andExpect(status().isConflict)
        assertEquals("expired_no_response", participantStatus(id, memberAId))
    }

    // ---- F5-12: atomic close claim ----

    @Test
    fun `claimClose flips active exactly once`() {
        val id = createSkladchina(listOf(memberAId, memberBId))
        val now = OffsetDateTime.now()
        assertTrue(skladchinaRepository.claimClose(id, SkladchinaStatus.cancelled, organizerId, now))
        assertFalse(
            skladchinaRepository.claimClose(id, SkladchinaStatus.closed_failed, organizerId, now),
            "second closer must lose the claim"
        )
        assertEquals("cancelled", skladchinaStatus(id))
    }

    @Test
    fun `double closeInternal is a no-op the second time`() {
        val id = createRepSkladchina(listOf(memberAId, memberBId))
        skladchinaService.closeInternal(id, closedBy = organizerId, manualClose = true)
        val statusAfterFirst = skladchinaStatus(id)
        // No exception, status untouched, no duplicate participant resolution.
        skladchinaService.closeInternal(id, closedBy = null, manualClose = false)
        assertEquals(statusAfterFirst, skladchinaStatus(id))
        assertEquals("released", participantStatus(id, memberAId))
        assertEquals(0, ledgerRows(memberAId, id))
    }

    // ---- Phase A: organizer mark-paid / unmark / redistribute ----

    @Test
    fun `organizer marks a pending participant paid in fixed mode (records the share)`() {
        val id = createSkladchina(listOf(memberAId, memberBId)) // fixed_equal 100000 → 50000 each
        mockMvc.perform(
            post("/api/skladchinas/$id/participants/$memberAId/mark-paid")
                .header("Authorization", "Bearer $organizerToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.collectedKopecks").value(50000))
        assertEquals("paid", participantStatus(id, memberAId))
        assertEquals(50000L, participantDeclared(id, memberAId))
    }

    @Test
    fun `organizer mark-paid is rejected for voluntary mode with 400`() {
        val id = createVoluntarySkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/skladchinas/$id/participants/$memberAId/mark-paid")
                .header("Authorization", "Bearer $organizerToken")
        )
            .andExpect(status().isBadRequest)
        assertEquals("pending", participantStatus(id, memberAId))
    }

    @Test
    fun `organizer mark-paid by a non-creator returns 403`() {
        val id = createSkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/skladchinas/$id/participants/$memberBId/mark-paid")
                .header("Authorization", "Bearer $memberAToken") // member, not creator
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `organizer unmark reverts a paid participant to pending and clears the amount`() {
        val id = createSkladchina(listOf(memberAId, memberBId))
        // Member pays themselves first (fixed → empty body, server records the share).
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        ).andExpect(status().isOk)
        assertEquals("paid", participantStatus(id, memberAId))

        mockMvc.perform(
            post("/api/skladchinas/$id/participants/$memberAId/unmark")
                .header("Authorization", "Bearer $organizerToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.collectedKopecks").value(0))
        assertEquals("pending", participantStatus(id, memberAId))
        assertEquals(null, participantDeclared(id, memberAId))
    }

    @Test
    fun `organizer mark-paid in an important skladchina accrues +10 at close (org vouches)`() {
        val id = createRepSkladchina(listOf(memberAId, memberBId)) // fixed_equal, affectsReputation
        // Organizer marks BOTH paid (cash) → no pending left → early auto-close → +10 each.
        mockMvc.perform(
            post("/api/skladchinas/$id/participants/$memberAId/mark-paid")
                .header("Authorization", "Bearer $organizerToken")
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/api/skladchinas/$id/participants/$memberBId/mark-paid")
                .header("Authorization", "Bearer $organizerToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("closed_success"))
        assertEquals(ReputationKind.skladchina_paid, soleLedgerKind(memberAId, id))
        assertEquals(10, soleLedgerPoints(memberAId, id))
        assertEquals(10, soleLedgerPoints(memberBId, id))
    }

    @Test
    fun `redistribute spreads the deficit onto pending only, leaving paid untouched`() {
        // A 3rd member makes the split meaningful (paid A, declined B, pending C).
        val memberCId = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$memberCId', 3005, 'MemberC')")
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$memberCId', '$clubId', 'active', 'member')")

        // fixed_equal goal 90000, 3 participants → 30000 each
        val id = createFromBody(
            """
            {
              "title": "Court",
              "paymentMode": "fixed_equal",
              "totalGoalKopecks": 90000,
              "paymentLink": "https://x.com",
              "deadline": "${OffsetDateTime.now().plusDays(2)}",
              "participants": [{"userId":"$memberAId"},{"userId":"$memberBId"},{"userId":"$memberCId"}]
            }
            """.trimIndent()
        )
        // A pays → collected 30000
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON).content("{}")
        ).andExpect(status().isOk)
        // B declines → still goal 90000, collected 30000, pending = {C}
        mockMvc.perform(
            post("/api/skladchinas/$id/decline").header("Authorization", "Bearer $memberBToken")
        ).andExpect(status().isOk)

        // Redistribute: deficit 60000 across pending {C} → C's share becomes 60000
        mockMvc.perform(
            post("/api/skladchinas/$id/redistribute").header("Authorization", "Bearer $organizerToken")
        ).andExpect(status().isOk)

        assertEquals(30000L, participantExpected(id, memberAId), "paid participant's share is untouched")
        assertEquals(60000L, participantExpected(id, memberCId), "pending participant absorbs the deficit")
    }

    @Test
    fun `redistribute is rejected for voluntary mode with 400`() {
        val id = createVoluntarySkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/skladchinas/$id/redistribute").header("Authorization", "Bearer $organizerToken")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `redistribute by a non-creator returns 403`() {
        val id = createSkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/skladchinas/$id/redistribute").header("Authorization", "Bearer $memberAToken")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `voluntary self mark-paid without an amount returns 400 (A-1 contract)`() {
        // A-1 moved "amount required" from the DTO @NotNull to the service (per-mode). A voluntary
        // self-mark with an empty body must still be rejected — fixed modes are the only ones that
        // may omit it.
        val id = createVoluntarySkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        )
            .andExpect(status().isBadRequest)
        assertEquals("pending", participantStatus(id, memberAId))
    }

    @Test
    fun `organizer mark-paid is idempotent and unmark is idempotent`() {
        val id = createSkladchina(listOf(memberAId, memberBId))
        // Mark twice — second call is a no-op returning the current (paid) state.
        repeat(2) {
            mockMvc.perform(
                post("/api/skladchinas/$id/participants/$memberAId/mark-paid")
                    .header("Authorization", "Bearer $organizerToken")
            ).andExpect(status().isOk)
        }
        assertEquals("paid", participantStatus(id, memberAId))
        // Unmark twice — second call is a no-op returning the current (pending) state.
        repeat(2) {
            mockMvc.perform(
                post("/api/skladchinas/$id/participants/$memberAId/unmark")
                    .header("Authorization", "Bearer $organizerToken")
            )
                .andExpect(status().isOk)
        }
        assertEquals("pending", participantStatus(id, memberAId))
    }

    @Test
    fun `organizer actions on a closed skladchina return 400`() {
        val id = createSkladchina(listOf(memberAId, memberBId))
        // Close it manually first.
        mockMvc.perform(
            post("/api/skladchinas/$id/close").header("Authorization", "Bearer $organizerToken")
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/skladchinas/$id/participants/$memberAId/mark-paid")
                .header("Authorization", "Bearer $organizerToken")
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/api/skladchinas/$id/redistribute").header("Authorization", "Bearer $organizerToken")
        ).andExpect(status().isBadRequest)
    }

    // ---- split_bill template ----

    @Test
    fun `split_bill creates from attendance with equal shares and links the event`() {
        val eventId = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        val id = createFromBody(splitBody(eventId, 90000))

        mockMvc.perform(
            get("/api/skladchinas/$id").header("Authorization", "Bearer $organizerToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.template").value("split_bill"))
            .andExpect(jsonPath("$.eventId").value(eventId.toString()))
            .andExpect(jsonPath("$.paymentMode").value("fixed_equal"))
            .andExpect(jsonPath("$.totalGoalKopecks").value(90000))
            .andExpect(jsonPath("$.participantCount").value(2))
        assertEquals(45000L, participantExpected(id, memberAId))
        assertEquals(45000L, participantExpected(id, memberBId))
    }

    @Test
    fun `split_bill voluntary mode keeps the bill as goal but assigns no per-person share`() {
        val eventId = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        val id = createFromBody(splitBodyMode(eventId, 90000, "voluntary"))

        mockMvc.perform(get("/api/skladchinas/$id").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paymentMode").value("voluntary"))
            .andExpect(jsonPath("$.totalGoalKopecks").value(90000)) // bill stays the goal the bar fills to
            .andExpect(jsonPath("$.participantCount").value(2))
        // "Каждый сам": no assigned share — each enters their own amount when paying.
        assertEquals(null, participantExpected(id, memberAId))
        assertEquals(null, participantExpected(id, memberBId))
    }

    @Test
    fun `split_bill rejects fixed_individual mode`() {
        val eventId = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(splitBodyMode(eventId, 90000, "fixed_individual"))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `split_bill includes only attended active members (absent + non-members excluded)`() {
        val memberCId = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$memberCId', 3006, 'MemberC')")
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$memberCId', '$clubId', 'active', 'member')")
        // A,B attended; outsider attended but is NOT a club member; C absent.
        val eventId = createEventWithAttendance(
            attended = listOf(memberAId, memberBId, outsiderId),
            absent = listOf(memberCId)
        )
        val id = createFromBody(splitBody(eventId, 80000))

        mockMvc.perform(get("/api/skladchinas/$id").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.participantCount").value(2)) // A and B only
        assertEquals(40000L, participantExpected(id, memberAId))
        assertEquals(40000L, participantExpected(id, memberBId))
        assertEquals(null, participantExpected(id, memberCId), "absent member excluded")
        assertEquals(null, participantExpected(id, outsiderId), "non-member excluded")
    }

    @Test
    fun `split_bill with fewer than 2 attended returns 400`() {
        val eventId = createEventWithAttendance(attended = listOf(memberAId))
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(splitBody(eventId, 90000))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `split_bill validation - missing event, unmarked attendance, missing bill`() {
        // no eventId
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "title": "Счёт", "template": "split_bill", "paymentMode": "fixed_equal",
                      "totalGoalKopecks": 90000, "paymentLink": "https://pay.me",
                      "deadline": "${OffsetDateTime.now().plusDays(2)}", "participants": []
                    }
                    """.trimIndent()
                )
        ).andExpect(status().isBadRequest)

        // attendance not marked yet
        val unmarked = createEventWithAttendance(attended = listOf(memberAId, memberBId), marked = false)
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(splitBody(unmarked, 90000))
        ).andExpect(status().isBadRequest)

        // missing bill (no totalGoalKopecks)
        val ev = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "title": "Счёт", "template": "split_bill", "eventId": "$ev",
                      "paymentMode": "fixed_equal", "paymentLink": "https://pay.me",
                      "deadline": "${OffsetDateTime.now().plusDays(2)}", "participants": []
                    }
                    """.trimIndent()
                )
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `split_bill rejects an event from another club`() {
        val otherClubId = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price)
            VALUES ('$otherClubId', '$organizerId', 'Other Club', 'desc', 'sport', 'open', 'Moscow', 20, 0)
            """.trimIndent()
        )
        val eventId = UUID.randomUUID()
        dsl.execute(
            "INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime, participant_limit, status, attendance_marked) " +
                "VALUES ('$eventId', '$otherClubId', '$organizerId', 'E', 'Court', NOW() - INTERVAL '1 day', 20, 'completed', true)"
        )
        dsl.execute("INSERT INTO event_responses (event_id, user_id, attendance) VALUES ('$eventId', '$memberAId', 'attended')")
        dsl.execute("INSERT INTO event_responses (event_id, user_id, attendance) VALUES ('$eventId', '$memberBId', 'attended')")

        // Split it from THIS club's create endpoint → event belongs to another club → 400.
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(splitBody(eventId, 90000))
        ).andExpect(status().isBadRequest)
    }

    // ---- split_bill decline-with-approval (V28) ----

    @Test
    fun `split_bill blocks instant decline — must request with a reason`() {
        val eventId = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        val id = createFromBody(splitBody(eventId, 90000))
        mockMvc.perform(post("/api/skladchinas/$id/decline").header("Authorization", "Bearer $memberAToken"))
            .andExpect(status().isBadRequest)
        assertEquals("pending", participantStatus(id, memberAId))
    }

    @Test
    fun `split_bill decline request then organizer approves leads to declined`() {
        val eventId = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        val id = createFromBody(splitBody(eventId, 90000))

        mockMvc.perform(
            post("/api/skladchinas/$id/request-decline")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"Я не ел, только смотрел"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myStatus").value("pending"))
            .andExpect(jsonPath("$.myDeclineRequested").value(true))
        assertEquals("Я не ел, только смотрел", participantDeclineNote(id, memberAId))

        mockMvc.perform(
            post("/api/skladchinas/$id/participants/$memberAId/resolve-decline")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"approve":true}""")
        ).andExpect(status().isOk)
        assertEquals("declined", participantStatus(id, memberAId))
    }

    @Test
    fun `split_bill decline rejected means must pay and cannot re-request`() {
        val eventId = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        val id = createFromBody(splitBody(eventId, 90000))

        mockMvc.perform(
            post("/api/skladchinas/$id/request-decline")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"не хочу"}""")
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/skladchinas/$id/participants/$memberAId/resolve-decline")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"approve":false}""")
        ).andExpect(status().isOk)
        assertEquals("pending", participantStatus(id, memberAId))
        assertTrue(participantDeclineRejected(id, memberAId))

        // Re-request is blocked — the decline path is closed.
        mockMvc.perform(
            post("/api/skladchinas/$id/request-decline")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"ну пожалуйста"}""")
        ).andExpect(status().isBadRequest)

        mockMvc.perform(get("/api/skladchinas/$id").header("Authorization", "Bearer $memberAToken"))
            .andExpect(jsonPath("$.myDeclineRejected").value(true))
    }

    @Test
    fun `request-decline without a reason returns 400`() {
        val eventId = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        val id = createFromBody(splitBody(eventId, 90000))
        mockMvc.perform(
            post("/api/skladchinas/$id/request-decline")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"   "}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `request-decline on a custom skladchina is rejected (free decline)`() {
        val id = createSkladchina(listOf(memberAId, memberBId)) // custom template
        mockMvc.perform(
            post("/api/skladchinas/$id/request-decline")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"x"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `resolve-decline by a non-creator returns 403`() {
        val eventId = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        val id = createFromBody(splitBody(eventId, 90000))
        mockMvc.perform(
            post("/api/skladchinas/$id/request-decline")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"x"}""")
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/api/skladchinas/$id/participants/$memberAId/resolve-decline")
                .header("Authorization", "Bearer $memberBToken") // not the creator
                .contentType(MediaType.APPLICATION_JSON).content("""{"approve":true}""")
        ).andExpect(status().isForbidden)
    }

    // ---- helpers ----

    /** Inserts a past, completed event with attendance marked and per-user attendance rows. */
    private fun createEventWithAttendance(
        attended: List<UUID>,
        absent: List<UUID> = emptyList(),
        marked: Boolean = true,
        daysAgo: Long = 1
    ): UUID {
        val eventId = UUID.randomUUID()
        dsl.execute(
            "INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime, participant_limit, status, attendance_marked) " +
                "VALUES ('$eventId', '$clubId', '$organizerId', 'Game', 'Court', NOW() - INTERVAL '$daysAgo days', 20, 'completed', $marked)"
        )
        attended.forEach {
            dsl.execute("INSERT INTO event_responses (event_id, user_id, attendance) VALUES ('$eventId', '$it', 'attended')")
        }
        absent.forEach {
            dsl.execute("INSERT INTO event_responses (event_id, user_id, attendance) VALUES ('$eventId', '$it', 'absent')")
        }
        return eventId
    }

    private fun splitBody(eventId: UUID, billKopecks: Long): String =
        splitBodyMode(eventId, billKopecks, "fixed_equal")

    private fun splitBodyMode(eventId: UUID, billKopecks: Long, mode: String): String = """
        {
          "title": "Счёт за корт",
          "template": "split_bill",
          "eventId": "$eventId",
          "paymentMode": "$mode",
          "totalGoalKopecks": $billKopecks,
          "paymentLink": "https://pay.me",
          "deadline": "${OffsetDateTime.now().plusDays(2)}",
          "participants": []
        }
    """.trimIndent()

    private fun createBodyFor(participantIds: List<UUID>): String {
        val participants = participantIds.joinToString(", ") { """{"userId": "$it"}""" }
        return """
            {
              "title": "Test",
              "paymentMode": "fixed_equal",
              "totalGoalKopecks": 100000,
              "paymentLink": "https://tinkoff.ru/pay/xyz",
              "deadline": "${OffsetDateTime.now().plusDays(2)}",
              "participants": [$participants]
            }
        """.trimIndent()
    }

    private fun createRepBodyFor(participantIds: List<UUID>): String {
        val participants = participantIds.joinToString(", ") { """{"userId": "$it"}""" }
        return """
            {
              "title": "Important",
              "paymentMode": "fixed_equal",
              "totalGoalKopecks": 100000,
              "paymentLink": "https://tinkoff.ru/pay/xyz",
              "deadline": "${OffsetDateTime.now().plusDays(2)}",
              "affectsReputation": true,
              "participants": [$participants]
            }
        """.trimIndent()
    }

    private fun createRepSkladchina(participantIds: List<UUID>): UUID =
        createFromBody(createRepBodyFor(participantIds))

    private fun createVoluntarySkladchina(participantIds: List<UUID>): UUID {
        val participants = participantIds.joinToString(", ") { """{"userId": "$it"}""" }
        return createFromBody(
            """
            {
              "title": "Voluntary",
              "paymentMode": "voluntary",
              "paymentLink": "https://x.com",
              "deadline": "${OffsetDateTime.now().plusDays(2)}",
              "participants": [$participants]
            }
            """.trimIndent()
        )
    }

    private fun createVoluntaryWithGoal(participantIds: List<UUID>, goalKopecks: Long): UUID {
        val participants = participantIds.joinToString(", ") { """{"userId": "$it"}""" }
        return createFromBody(
            """
            {
              "title": "Voluntary with goal",
              "paymentMode": "voluntary",
              "totalGoalKopecks": $goalKopecks,
              "paymentLink": "https://x.com",
              "deadline": "${OffsetDateTime.now().plusDays(2)}",
              "participants": [$participants]
            }
            """.trimIndent()
        )
    }

    private fun participantStatus(skladchinaId: UUID, userId: UUID): String =
        dsl.fetchOne(
            "SELECT status::text FROM skladchina_participants WHERE skladchina_id = ? AND user_id = ?",
            skladchinaId, userId
        )!!.get(0, String::class.java)!!

    // get(0) returns the boxed column value (Long or null). Long::class.java is the PRIMITIVE
    // `long`, which jOOQ coerces NULL → 0 — wrong for a "declared is null after unmark" check.
    private fun participantDeclared(skladchinaId: UUID, userId: UUID): Long? =
        dsl.fetchOne(
            "SELECT declared_amount_kopecks FROM skladchina_participants WHERE skladchina_id = ? AND user_id = ?",
            skladchinaId, userId
        )?.get(0) as Long?

    private fun participantExpected(skladchinaId: UUID, userId: UUID): Long? =
        dsl.fetchOne(
            "SELECT expected_amount_kopecks FROM skladchina_participants WHERE skladchina_id = ? AND user_id = ?",
            skladchinaId, userId
        )?.get(0) as Long?

    private fun participantDeclineNote(skladchinaId: UUID, userId: UUID): String? =
        dsl.fetchOne(
            "SELECT decline_note FROM skladchina_participants WHERE skladchina_id = ? AND user_id = ?",
            skladchinaId, userId
        )?.get(0) as String?

    private fun participantDeclineRejected(skladchinaId: UUID, userId: UUID): Boolean =
        (dsl.fetchOne(
            "SELECT decline_rejected FROM skladchina_participants WHERE skladchina_id = ? AND user_id = ?",
            skladchinaId, userId
        )?.get(0) as Boolean?) ?: false

    private fun skladchinaStatus(skladchinaId: UUID): String =
        dsl.fetchOne("SELECT status::text FROM skladchinas WHERE id = ?", skladchinaId)!!
            .get(0, String::class.java)!!

    private fun ledgerRows(userId: UUID, sourceId: UUID): Int =
        dsl.fetchCount(
            REPUTATION_LEDGER,
            REPUTATION_LEDGER.USER_ID.eq(userId).and(REPUTATION_LEDGER.SOURCE_ID.eq(sourceId))
        )

    private fun soleLedgerKind(userId: UUID, sourceId: UUID): ReputationKind =
        dsl.select(REPUTATION_LEDGER.KIND).from(REPUTATION_LEDGER)
            .where(REPUTATION_LEDGER.USER_ID.eq(userId).and(REPUTATION_LEDGER.SOURCE_ID.eq(sourceId)))
            .fetchOne(REPUTATION_LEDGER.KIND)!!

    private fun soleLedgerPoints(userId: UUID, sourceId: UUID): Int =
        dsl.select(REPUTATION_LEDGER.POINTS).from(REPUTATION_LEDGER)
            .where(REPUTATION_LEDGER.USER_ID.eq(userId).and(REPUTATION_LEDGER.SOURCE_ID.eq(sourceId)))
            .fetchOne(REPUTATION_LEDGER.POINTS)!!

    private fun createSkladchina(participantIds: List<UUID>): UUID =
        createFromBody(createBodyFor(participantIds))

    private fun createFromBody(body: String): UUID {
        val result = mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andReturn()
        val json = objectMapper.readTree(result.response.contentAsString)
        return UUID.fromString(json.get("id").asText())
    }
}
