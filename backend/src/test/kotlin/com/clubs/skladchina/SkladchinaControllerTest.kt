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
    @Autowired lateinit var lifecycleService: SkladchinaLifecycleService
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
        // Фильтр срабатывает до аутентификации → все MockMvc-запросы делят один bucket ip:127.0.0.1.
        // Сбрасываем перед каждым тестом, чтобы общий API-bucket 60/мин не исчерпался за весь прогон.
        rateLimitFilter.resetBuckets()
        dsl.execute("DELETE FROM reputation_ledger")
        dsl.execute("DELETE FROM skladchina_participants")
        dsl.execute("DELETE FROM skladchinas")
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM applications")
        dsl.execute("DELETE FROM transactions")
        dsl.execute("DELETE FROM membership_history")
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

        // Первый mark-paid
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

        // Идемпотентный повтор — без ошибки, возвращается текущее состояние
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

        // После decline выполнить mark-paid нельзя
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
        val id = createSkladchina(listOf(memberAId))  // memberB не участник
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
        // Цель = 100, два участника fixed_equal → по 50. Оба платят → pending не осталось → закрыт.
        // (Phase A: закрытие по «все ответили», а не по достижению цели — см. тест ниже.)
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
        // Voluntary с целью 50000; memberA декларирует 60000 (≥ цели), но memberB остаётся pending.
        // До Phase A это принудительно закрывалось по достижению цели; теперь деньги — декорация → остаётся active.
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
        // Не-создатель закрыть не может
        mockMvc.perform(
            post("/api/skladchinas/$id/close")
                .header("Authorization", "Bearer $memberAToken")
        )
            .andExpect(status().isForbidden)

        // Создатель закрывает — собрано 0, цель не достигнута → cancelled
        mockMvc.perform(
            post("/api/skladchinas/$id/close")
                .header("Authorization", "Bearer $organizerToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("cancelled"))
    }

    @Test
    fun `GET me-skladchinas returns active skladchinas only for participant`() {
        val id1 = createSkladchina(listOf(memberAId))   // memberA участник
        createSkladchina(listOf(memberBId))             // участник memberB, memberA — нет: не должна протечь в ленту A

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
        // Фидбек со staging 2026-06-12: сборам на подарки тоже нужна ориентировочная цель.
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

    // ---- валидация declared-amount (redesign § Валидации) ----

    @Test
    fun `mark-paid in fixed mode records the assigned share, ignoring the client value`() {
        // Сервер записывает сумму авторитетно (staging-баг 2026-06-12): UI округляет копейки
        // до целых рублей, и строгая проверка declared == expected отклоняла честные оплаты
        // неделимых долей. Клиентское значение не должно ни раздувать `collected`
        // (усилитель F5-02), ни блокировать оплату из-за 33 копеек разницы от округления.
        val id = createSkladchina(listOf(memberAId, memberBId))  // fixed_equal, по 50000
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

    // ---- гейты переключателя «важный сбор» ----

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
        // +2 часа проходят общий минимум в 1 час, но не проходят репутационный гейт 24 часа.
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

        // Сбор без влияния на репутацию НЕ ограничен по частоте.
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBodyFor(listOf(memberAId, memberBId)))
        )
            .andExpect(status().isCreated)
    }

    // ---- предикат released vs expired (F5-02) ----

    @Test
    fun `early close releases pending participants - no ledger row, paid keeps +10`() {
        val id = createRepSkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 50000}""")
        ).andExpect(status().isOk)

        // Ручное закрытие ДО дедлайна: memberB так и не ответил, но дедлайн
        // не наступил — released, нейтрально.
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

        // Симулируем наступление дедлайна, затем закрытие по пути шедулера.
        dsl.execute("UPDATE skladchinas SET deadline = NOW() - INTERVAL '1 hour' WHERE id = '$id'")
        lifecycleService.closeInternal(id, closedBy = null, manualClose = false)

        assertEquals("expired_no_response", participantStatus(id, memberBId))
        assertEquals(ReputationKind.skladchina_expired, soleLedgerKind(memberBId, id))
        assertEquals(-40, soleLedgerPoints(memberBId, id))
        assertEquals(10, soleLedgerPoints(memberAId, id))
    }

    // ---- F5-03: guard перехода только из pending ----

    @Test
    fun `mark-paid after concurrent expiry returns 409 and keeps the terminal status`() {
        val id = createSkladchina(listOf(memberAId, memberBId))
        // Промежуточное состояние гонки: складчина ещё active, участник уже resolved.
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

    // ---- F5-12: атомарный claim закрытия ----

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
        lifecycleService.closeInternal(id, closedBy = organizerId, manualClose = true)
        val statusAfterFirst = skladchinaStatus(id)
        // Без исключения, статус не тронут, участники не резолвятся повторно.
        lifecycleService.closeInternal(id, closedBy = null, manualClose = false)
        assertEquals(statusAfterFirst, skladchinaStatus(id))
        assertEquals("released", participantStatus(id, memberAId))
        assertEquals(0, ledgerRows(memberAId, id))
    }

    // ---- Phase A: организаторские mark-paid / unmark / redistribute ----

    @Test
    fun `organizer marks a pending participant paid in fixed mode (records the share)`() {
        val id = createSkladchina(listOf(memberAId, memberBId)) // fixed_equal 100000 → по 50000
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
                .header("Authorization", "Bearer $memberAToken") // участник, не создатель
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `organizer unmark reverts a paid participant to pending and clears the amount`() {
        val id = createSkladchina(listOf(memberAId, memberBId))
        // Сначала участник платит сам (fixed → пустое тело, сервер сам записывает долю).
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
        // Организатор отмечает ОБОИХ оплатившими (наличные) → pending не осталось → раннее автозакрытие → +10 каждому.
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
    fun `voluntary self mark-paid without an amount returns 400 (A-1 contract)`() {
        // A-1 перенёс требование «сумма обязательна» из @NotNull в DTO в сервис (по режимам). Voluntary
        // self-mark с пустым телом всё равно должен отклоняться — опускать сумму разрешено
        // только в fixed-режимах.
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
        // Отмечаем дважды — второй вызов no-op, возвращает текущее состояние (paid).
        repeat(2) {
            mockMvc.perform(
                post("/api/skladchinas/$id/participants/$memberAId/mark-paid")
                    .header("Authorization", "Bearer $organizerToken")
            ).andExpect(status().isOk)
        }
        assertEquals("paid", participantStatus(id, memberAId))
        // Снимаем отметку дважды — второй вызов no-op, возвращает текущее состояние (pending).
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
        // Сначала закрываем вручную.
        mockMvc.perform(
            post("/api/skladchinas/$id/close").header("Authorization", "Bearer $organizerToken")
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/skladchinas/$id/participants/$memberAId/mark-paid")
                .header("Authorization", "Bearer $organizerToken")
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/api/skladchinas/$id/participants/$memberAId/unmark").header("Authorization", "Bearer $organizerToken")
        ).andExpect(status().isBadRequest)
    }

    // ---- шаблон split_bill ----

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
            .andExpect(jsonPath("$.totalGoalKopecks").value(90000)) // счёт остаётся целью, к которой заполняется прогресс-бар
            .andExpect(jsonPath("$.participantCount").value(2))
        // «Каждый сам»: доля не назначена — каждый вводит свою сумму при оплате.
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
        // A и B пришли; outsider пришёл, но НЕ участник клуба; C отсутствовал.
        val eventId = createEventWithAttendance(
            attended = listOf(memberAId, memberBId, outsiderId),
            absent = listOf(memberCId)
        )
        val id = createFromBody(splitBody(eventId, 80000))

        mockMvc.perform(get("/api/skladchinas/$id").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.participantCount").value(2)) // только A и B
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
        // без eventId
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

        // посещаемость ещё не отмечена
        val unmarked = createEventWithAttendance(attended = listOf(memberAId, memberBId), marked = false)
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(splitBody(unmarked, 90000))
        ).andExpect(status().isBadRequest)

        // нет счёта (нет totalGoalKopecks)
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

        // Сплит через create-эндпоинт ЭТОГО клуба → событие принадлежит другому клубу → 400.
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(splitBody(eventId, 90000))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `split_bill excludeSelf drops the organizer and divides across the rest`() {
        // Организатор тоже присутствовал; с excludeSelf счёт 80000 делится только между A и B.
        val eventId = createEventWithAttendance(attended = listOf(organizerId, memberAId, memberBId))
        val id = createFromBody(
            """
            {
              "title": "Счёт", "template": "split_bill", "eventId": "$eventId", "excludeSelf": true,
              "paymentMode": "fixed_equal", "totalGoalKopecks": 80000, "paymentLink": "https://pay.me",
              "deadline": "${OffsetDateTime.now().plusDays(2)}", "participants": []
            }
            """.trimIndent()
        )
        mockMvc.perform(get("/api/skladchinas/$id").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.participantCount").value(2))
            .andExpect(jsonPath("$.myStatus").doesNotExist()) // организатор — не участник
        assertEquals(40000L, participantExpected(id, memberAId))
        assertEquals(40000L, participantExpected(id, memberBId))
        assertEquals(null, participantExpected(id, organizerId), "organizer excluded from the split")
    }

    @Test
    fun `split_bill always affects reputation, both modes, bypassing the important-sbor gates`() {
        // В теле нет affectsReputation, режим voluntary, дедлайн всего +2ч (для кастомного важного
        // сбора провалил бы гейт 24ч) — верифицированный якорь сплита полностью обходит гейты.
        val eventId = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        val id = createFromBody(
            """
            {
              "title": "Счёт", "template": "split_bill", "eventId": "$eventId",
              "paymentMode": "voluntary", "totalGoalKopecks": 90000, "paymentLink": "https://pay.me",
              "deadline": "${OffsetDateTime.now().plusHours(2)}", "participants": []
            }
            """.trimIndent()
        )
        mockMvc.perform(get("/api/skladchinas/$id").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.affectsReputation").value(true))
    }

    @Test
    fun `request-decline with under-48h-left pushes the deadline out to ~48h`() {
        val eventId = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        val id = createFromBody(
            """
            {
              "title": "Счёт", "template": "split_bill", "eventId": "$eventId",
              "paymentMode": "fixed_equal", "totalGoalKopecks": 90000, "paymentLink": "https://pay.me",
              "deadline": "${OffsetDateTime.now().plusHours(3)}", "participants": []
            }
            """.trimIndent()
        )
        mockMvc.perform(
            post("/api/skladchinas/$id/request-decline")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"мало времени"}""")
        ).andExpect(status().isOk)
        // У организатора теперь ~48ч (>47ч) на решение — независимо от исходного дедлайна в 3ч.
        val deadline = dsl.fetchOne("SELECT deadline FROM skladchinas WHERE id = ?", id)!!
            .get(0, OffsetDateTime::class.java)!!
        assertTrue(deadline.isAfter(OffsetDateTime.now().plusHours(47)), "deadline extended to ~48h")
    }

    @Test
    fun `split_bill blocks a second collection while one is active`() {
        val eventId = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        createFromBody(splitBody(eventId, 90000))
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(splitBody(eventId, 50000))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `split_bill blocks a new collection after a successful close`() {
        val eventId = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        val id = createFromBody(splitBody(eventId, 90000))
        // Оба платят → все ответили → автозакрытие в closed_success.
        mockMvc.perform(post("/api/skladchinas/$id/mark-paid").header("Authorization", "Bearer $memberAToken")
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk)
        mockMvc.perform(post("/api/skladchinas/$id/mark-paid").header("Authorization", "Bearer $memberBToken")
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk)
        assertEquals("closed_success", skladchinaStatus(id))

        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(splitBody(eventId, 90000))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `split_bill allows a new collection after a cancelled close`() {
        val eventId = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        val id = createFromBody(splitBody(eventId, 90000))
        // Закрытие с нулевым сбором → cancelled (не блокер — организатор может повторить).
        mockMvc.perform(post("/api/skladchinas/$id/close").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isOk)
        assertEquals("cancelled", skladchinaStatus(id))

        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(splitBody(eventId, 90000))
        ).andExpect(status().isCreated)
    }

    @Test
    fun `GET event skladchina returns null then the active split`() {
        val eventId = createEventWithAttendance(attended = listOf(memberAId, memberBId))
        mockMvc.perform(get("/api/events/$eventId/skladchina").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.skladchinaId").doesNotExist())
        val id = createFromBody(splitBody(eventId, 90000))
        mockMvc.perform(get("/api/events/$eventId/skladchina").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.skladchinaId").value(id.toString()))
            .andExpect(jsonPath("$.status").value("active"))
    }

    // ---- split_bill: отказ через одобрение организатора (V28) ----

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

        // V29: отклонение без причины не принимается.
        mockMvc.perform(
            post("/api/skladchinas/$id/participants/$memberAId/resolve-decline")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"approve":false}""")
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            post("/api/skladchinas/$id/participants/$memberAId/resolve-decline")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"approve":false,"rejectReason":"ты был, плати"}""")
        ).andExpect(status().isOk)
        assertEquals("pending", participantStatus(id, memberAId))
        assertTrue(participantDeclineRejected(id, memberAId))
        assertEquals("ты был, плати", participantDeclineRejectNote(id, memberAId))

        // Повторный запрос заблокирован — путь отказа закрыт.
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
        val id = createSkladchina(listOf(memberAId, memberBId)) // кастомный шаблон
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
                .header("Authorization", "Bearer $memberBToken") // не создатель
                .contentType(MediaType.APPLICATION_JSON).content("""{"approve":true}""")
        ).andExpect(status().isForbidden)
    }

    // ---- хелперы ----

    /** Вставляет прошедшее завершённое событие с отмеченной посещаемостью и per-user строками посещаемости. */
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

    // get(0) возвращает boxed-значение колонки (Long или null). Long::class.java — это ПРИМИТИВНЫЙ
    // `long`, для которого jOOQ приводит NULL → 0 — ломает проверку «declared равен null после unmark».
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

    private fun participantDeclineRejectNote(skladchinaId: UUID, userId: UUID): String? =
        dsl.fetchOne(
            "SELECT decline_reject_note FROM skladchina_participants WHERE skladchina_id = ? AND user_id = ?",
            skladchinaId, userId
        )?.get(0) as String?

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
