package com.clubs.skladchina

import com.clubs.auth.JwtService
import com.clubs.debt.DebtReputationService
import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.tables.references.REPUTATION_LEDGER
import com.fasterxml.jackson.databind.JsonNode
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Сборы и долги v3 через API — критерии приёмки docs/modules/skladchina-v3.md § 11 (AC-1…AC-14).
 * Клуб: владелец + три участника + посторонний. DM и чат-посты в тестовом профиле не уходят
 * (бот не стартует), проверяется состояние в ответах API и в БД.
 */
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
class SkladchinaV3IntegrationTest {

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
    @Autowired lateinit var debtReputationService: DebtReputationService
    @Autowired lateinit var rateLimitFilter: com.clubs.common.security.RateLimitFilter

    private lateinit var ownerId: UUID
    private lateinit var aliceId: UUID
    private lateinit var bobId: UUID
    private lateinit var carolId: UUID
    private lateinit var outsiderId: UUID
    private lateinit var clubId: UUID
    private lateinit var owner: String
    private lateinit var alice: String
    private lateinit var bob: String
    private lateinit var carol: String
    private lateinit var outsider: String

    @BeforeEach
    fun setUp() {
        rateLimitFilter.resetBuckets()
        listOf(
            "reputation_ledger", "debts", "debt_settlements", "skladchina_enrollments", "skladchina_chat_posts",
            "skladchinas", "event_responses", "events", "applications", "transactions", "membership_history",
            "memberships", "user_club_reputation", "clubs", "users"
        ).forEach { dsl.execute("DELETE FROM $it") }

        ownerId = newUser(4001, "Owner"); aliceId = newUser(4002, "Alice")
        bobId = newUser(4003, "Bob"); carolId = newUser(4004, "Carol"); outsiderId = newUser(4005, "Outsider")
        owner = jwtService.generateToken(ownerId, 4001L); alice = jwtService.generateToken(aliceId, 4002L)
        bob = jwtService.generateToken(bobId, 4003L); carol = jwtService.generateToken(carolId, 4004L)
        outsider = jwtService.generateToken(outsiderId, 4005L)

        clubId = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price)
            VALUES ('$clubId', '$ownerId', 'Партия', 'desc', 'sport', 'open', 'Moscow', 20, 0)
            """.trimIndent()
        )
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$ownerId', '$clubId', 'active', 'organizer')")
        listOf(aliceId, bobId, carolId).forEach {
            dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$it', '$clubId', 'active', 'member')")
        }
    }

    // --- AC-1: «Скинуться» со списком, доля создателя received ---

    @Test
    fun `shared with listed debtors splits equally and creator share is received at once`() {
        val detail = createShared(owner, listOf(aliceId, bobId, ownerId), amount = 300_000)
        assertEquals("active", detail["status"].asText())
        assertEquals(3, detail["debtCount"].asInt())
        assertEquals(1, detail["receivedCount"].asInt())
        assertEquals(100_000L, detail["receivedKopecks"].asLong())
        assertEquals(300_000L, detail["targetKopecks"].asLong())
        assertTrue(detail["isCreator"].asBoolean())
        assertTrue(detail["myDebt"].isNull, "своя доля создателя — не «мой долг»")
        assertEquals(3, detail["debts"].size())
        val ownShare = detail["debts"].first { it["debtor"]["id"].asText() == ownerId.toString() }
        assertEquals("received", ownShare["status"].asText())
    }

    @Test
    fun `any active member may create, outsider gets 403, non-creator sees no debt list`() {
        val detail = createShared(alice, listOf(bobId), amount = 1_000)
        assertEquals(aliceId.toString(), detail["creatorId"].asText())

        postJson("/api/clubs/$clubId/skladchinas", outsider, sharedBody(listOf(bobId), 1_000))
            .andExpect(status().isForbidden)

        val asBob = json(get("/api/skladchinas/${detail["id"].asText()}", bob).andExpect(status().isOk))
        assertTrue(asBob["debts"].isNull)
        assertEquals("waiting", asBob["myDebt"]["status"].asText())
        get("/api/skladchinas/${detail["id"].asText()}", outsider).andExpect(status().isForbidden)
    }

    // --- AC-2, AC-4: Отдал → Получил, сбор закрывается сам ---

    @Test
    fun `claim then confirm closes the debt and the skladchina when it was the last open one`() {
        val detail = createShared(owner, listOf(aliceId), amount = 1_000)
        val skladchinaId = detail["id"].asText()
        val debtId = myDebtId(skladchinaId, alice)

        post("/api/debts/$debtId/claim", bob).andExpect(status().isForbidden)
        val claimed = json(post("/api/debts/$debtId/claim", alice).andExpect(status().isOk))
        assertEquals("claimed", claimed["status"].asText())

        post("/api/debts/$debtId/confirm", alice).andExpect(status().isForbidden)
        val received = json(post("/api/debts/$debtId/confirm", owner).andExpect(status().isOk))
        assertEquals("received", received["status"].asText())

        val after = json(get("/api/skladchinas/$skladchinaId", owner))
        assertEquals("collected", after["status"].asText())
        assertEquals(0, json(get("/api/debts", alice))["people"].size())
    }

    // --- AC-3: Не получил, чек ---

    @Test
    fun `reject returns debt to waiting with a note and receipt is accepted only from our uploads`() {
        val skladchinaId = createShared(owner, listOf(aliceId), amount = 1_000)["id"].asText()
        val debtId = myDebtId(skladchinaId, alice)
        post("/api/debts/$debtId/claim", alice).andExpect(status().isOk)

        val rejected = json(postJson("/api/debts/$debtId/reject", owner, """{"note":"в выписке нет"}""").andExpect(status().isOk))
        assertEquals("waiting", rejected["status"].asText())
        assertEquals("в выписке нет", rejected["rejectNote"].asText())
        assertTrue(!rejected["rejectedAt"].isNull)

        postJson("/api/debts/$debtId/receipt", alice, """{"url":"https://evil.com/uploads/x.png"}""")
            .andExpect(status().isBadRequest)
        val withReceipt = json(postJson("/api/debts/$debtId/receipt", alice, """{"url":"http://localhost:9000/test-bucket/uploads/abc.png"}""").andExpect(status().isOk))
        assertEquals("http://localhost:9000/test-bucket/uploads/abc.png", withReceipt["receiptUrl"].asText())
        // Получатель видит чек в строке долга сбора.
        val creatorView = json(get("/api/skladchinas/$skladchinaId", owner))
        assertEquals("http://localhost:9000/test-bucket/uploads/abc.png", creatorView["debts"].first { it["id"].asText() == debtId }["receiptUrl"].asText())
    }

    // --- AC-5: срок не стена ---

    @Test
    fun `claim after the deadline is accepted and the row is marked overdue`() {
        val skladchinaId = createShared(owner, listOf(aliceId), amount = 1_000)["id"].asText()
        val debtId = myDebtId(skladchinaId, alice)
        dsl.execute("UPDATE skladchinas SET deadline = now() - interval '1 day' WHERE id = '$skladchinaId'")
        dsl.execute("UPDATE debts SET due_at = now() - interval '1 day' WHERE id = '$debtId'")

        val claimed = json(post("/api/debts/$debtId/claim", alice).andExpect(status().isOk))
        assertEquals("claimed", claimed["status"].asText())
        assertTrue(claimed["isOverdue"].asBoolean())
    }

    // --- AC-6: Оплачу позже ---

    @Test
    fun `promise moves debt to promised with the date and rejects past dates`() {
        val skladchinaId = createShared(owner, listOf(aliceId), amount = 1_000)["id"].asText()
        val debtId = myDebtId(skladchinaId, alice)
        val date = LocalDate.now().plusDays(3)
        val promised = json(postJson("/api/debts/$debtId/promise", alice, """{"date":"$date"}""").andExpect(status().isOk))
        assertEquals("promised", promised["status"].asText())
        assertEquals(date.toString(), promised["promisedAt"].asText())
        postJson("/api/debts/$debtId/promise", alice, """{"date":"${LocalDate.now().minusDays(1)}"}""")
            .andExpect(status().isBadRequest)
    }

    // --- AC-7: Кто в деле? ---

    @Test
    fun `enrollment stage locks into equal debts when the minimum is met and cancels on shortfall`() {
        val enrolling = json(postJson("/api/clubs/$clubId/skladchinas", owner, enrollingBody(amount = 3_000, min = 3)).andExpect(status().isCreated))
        val id = enrolling["id"].asText()
        assertTrue(enrolling["isEnrolling"].asBoolean())
        assertEquals(1, enrolling["enrolledCount"].asInt(), "создатель в деле по умолчанию")
        assertEquals(0, enrolling["debtCount"].asInt())

        post("/api/skladchinas/$id/join", alice).andExpect(status().isOk)
        val afterBob = json(post("/api/skladchinas/$id/join", bob).andExpect(status().isOk))
        assertEquals(3, afterBob["enrolledCount"].asInt())
        assertTrue(afterBob["myEnrolled"].asBoolean())

        post("/api/skladchinas/$id/lock", alice).andExpect(status().isForbidden)
        val locked = json(post("/api/skladchinas/$id/lock", owner).andExpect(status().isOk))
        assertEquals("active", locked["status"].asText())
        assertTrue(!locked["isEnrolling"].asBoolean())
        assertEquals(3, locked["debtCount"].asInt())
        assertTrue(locked["debts"].all { it["amountKopecks"].asLong() == 1_000L })
        assertEquals(1, locked["receivedCount"].asInt())

        // Недобор: только Alice отметилась при минимуме 3 → отмена без долгов.
        val short = json(postJson("/api/clubs/$clubId/skladchinas", owner, enrollingBody(amount = 3_000, min = 3)).andExpect(status().isCreated))
        val shortId = short["id"].asText()
        post("/api/skladchinas/$shortId/join", alice).andExpect(status().isOk)
        val cancelled = json(post("/api/skladchinas/$shortId/lock", owner).andExpect(status().isOk))
        assertEquals("cancelled", cancelled["status"].asText())
        assertEquals(0, cancelled["debtCount"].asInt())
    }

    // --- AC-8: Заказываю ---

    @Test
    fun `per_head take then order drops unpaid, closes when nothing open and refuses new takes`() {
        val created = json(postJson("/api/clubs/$clubId/skladchinas", owner, perHeadBody(price = 1_500)).andExpect(status().isCreated))
        val id = created["id"].asText()
        assertEquals(0, created["debtCount"].asInt())

        val aliceTake = json(postJson("/api/skladchinas/$id/join", alice, """{"note":"размер M"}""").andExpect(status().isOk))
        assertEquals("waiting", aliceTake["myDebt"]["status"].asText())
        assertEquals(1_500L, aliceTake["myDebt"]["amountKopecks"].asLong())
        assertEquals("размер M", aliceTake["myDebt"]["note"].asText())
        post("/api/skladchinas/$id/join", alice).andExpect(status().isBadRequest)

        post("/api/skladchinas/$id/join", bob).andExpect(status().isOk)
        val bobDebt = myDebtId(id, bob)
        post("/api/debts/$bobDebt/claim", bob).andExpect(status().isOk)
        post("/api/debts/$bobDebt/confirm", owner).andExpect(status().isOk)

        post("/api/skladchinas/$id/order", bob).andExpect(status().isForbidden)
        val ordered = json(post("/api/skladchinas/$id/order", owner).andExpect(status().isOk))
        assertTrue(!ordered["orderedAt"].isNull)
        assertEquals("collected", ordered["status"].asText(), "после заказа открытых нет — собран")
        val aliceRow = ordered["debts"].first { it["debtor"]["id"].asText() == aliceId.toString() }
        assertEquals("dropped", aliceRow["status"].asText())
        assertEquals(1, ordered["receivedCount"].asInt())

        post("/api/skladchinas/$id/join", carol).andExpect(status().isBadRequest)
        assertEquals(0, dsl.fetchCount(REPUTATION_LEDGER))
    }

    // --- AC-9: тихий сбор ---

    @Test
    fun `hidden voluntary is 404 for the hidden user, contributions arrive as claimed and close needs them handled`() {
        val created = json(postJson("/api/clubs/$clubId/skladchinas", owner, voluntaryBody(hiddenFrom = carolId)).andExpect(status().isCreated))
        val id = created["id"].asText()
        get("/api/skladchinas/$id", carol).andExpect(status().isNotFound)
        get("/api/skladchinas/$id", alice).andExpect(status().isOk)
        val carolFeed = json(get("/api/clubs/$clubId/activities?type=skladchina", carol).andExpect(status().isOk))
        assertEquals(0, carolFeed["upcoming"].size() + carolFeed["past"].size())
        assertEquals(1, json(get("/api/clubs/$clubId/activities?type=skladchina", alice))["upcoming"].size())

        val contributed = json(postJson("/api/skladchinas/$id/contribute", alice, """{"amountKopecks":50000}""").andExpect(status().isOk))
        assertEquals("claimed", contributed["myDebt"]["status"].asText())
        assertEquals(50_000L, contributed["claimedKopecks"].asLong())
        postJson("/api/skladchinas/$id/contribute", alice, """{"amountKopecks":100}""").andExpect(status().isBadRequest)

        post("/api/skladchinas/$id/close", owner).andExpect(status().isBadRequest)
        post("/api/debts/${myDebtId(id, alice)}/confirm", owner).andExpect(status().isOk)
        val closed = json(post("/api/skladchinas/$id/close", owner).andExpect(status().isOk))
        assertEquals("collected", closed["status"].asText())
        assertEquals(50_000L, closed["receivedKopecks"].asLong())
    }

    // --- AC-10: сальдо пары ---

    @Test
    fun `pair settlement claims all open debts at once, confirm receives them, reject reopens`() {
        val s1 = createShared(alice, listOf(bobId), amount = 1_000)["id"].asText()
        val s2 = createShared(alice, listOf(bobId), amount = 857)["id"].asText()
        val s3 = createShared(bob, listOf(aliceId), amount = 400)["id"].asText()

        val bobView = json(get("/api/debts/with/$aliceId", bob).andExpect(status().isOk))
        assertEquals(2, bobView["owe"].size()); assertEquals(1, bobView["owed"].size())
        assertEquals(-1_457L, bobView["balanceKopecks"].asLong())

        post("/api/debts/with/$bobId/settle", alice).andExpect(status().isBadRequest)
        val settled = json(post("/api/debts/with/$aliceId/settle", bob).andExpect(status().isOk))
        assertEquals(1_457L, settled["settlement"]["amountKopecks"].asLong())
        assertEquals("claimed", settled["settlement"]["status"].asText())
        assertTrue((settled["owe"] + settled["owed"]).all { it["status"].asText() == "claimed" && !it["settlementId"].isNull })
        val settlementId = settled["settlement"]["id"].asText()

        // Одиночные кнопки скрыты: долг в составе сальдо не подтверждается отдельно.
        post("/api/debts/${settled["owe"][0]["id"].asText()}/confirm", alice).andExpect(status().isBadRequest)
        val overview = json(get("/api/debts", alice))
        assertEquals(1, overview["awaitingMyConfirmation"].asInt())

        post("/api/debts/settlements/$settlementId/confirm", bob).andExpect(status().isForbidden)
        val confirmed = json(post("/api/debts/settlements/$settlementId/confirm", alice).andExpect(status().isOk))
        assertEquals(0, confirmed["owe"].size() + confirmed["owed"].size())
        listOf(s1, s2, s3).forEach { assertEquals("collected", json(get("/api/skladchinas/$it", alice))["status"].asText()) }

        // Второй раунд: «Не получил» по сальдо возвращает все долги в waiting.
        createShared(alice, listOf(bobId), amount = 500); createShared(bob, listOf(aliceId), amount = 200)
        val again = json(post("/api/debts/with/$aliceId/settle", bob).andExpect(status().isOk))
        val rejected = json(post("/api/debts/settlements/${again["settlement"]["id"].asText()}/reject", alice).andExpect(status().isOk))
        assertTrue((rejected["owe"] + rejected["owed"]).all { it["status"].asText() == "waiting" && it["settlementId"].isNull })
        assertTrue(rejected["settlement"].isNull)
    }

    // --- AC-11: репутация ---

    @Test
    fun `reputation plus for an on-time received shared debt, minus once for long overdue, none for the club owner`() {
        val onTime = createShared(owner, listOf(aliceId), amount = 1_000)["id"].asText()
        val aliceDebt = myDebtId(onTime, alice)
        post("/api/debts/$aliceDebt/claim", alice).andExpect(status().isOk)
        post("/api/debts/$aliceDebt/confirm", owner).andExpect(status().isOk)

        val overdue = createShared(owner, listOf(bobId), amount = 1_000)["id"].asText()
        dsl.execute("UPDATE debts SET due_at = now() - interval '30 days' WHERE skladchina_id = '$overdue'")

        val claimedLong = createShared(owner, listOf(carolId), amount = 1_000)["id"].asText()
        post("/api/debts/${myDebtId(claimedLong, carol)}/claim", carol).andExpect(status().isOk)
        dsl.execute("UPDATE debts SET due_at = now() - interval '30 days' WHERE skladchina_id = '$claimedLong'")

        // Владелец клуба — должник у Alice: очков не получает.
        val ownerDebtSkladchina = createShared(alice, listOf(ownerId), amount = 700)["id"].asText()
        val ownerDebt = myDebtId(ownerDebtSkladchina, owner)
        post("/api/debts/$ownerDebt/claim", owner).andExpect(status().isOk)
        post("/api/debts/$ownerDebt/confirm", alice).andExpect(status().isOk)

        val now = OffsetDateTime.now()
        assertEquals(1, debtReputationService.applyPlus(now))
        assertEquals(1, debtReputationService.applyMinus(now).size)
        assertEquals(0, debtReputationService.applyPlus(now))
        assertEquals(0, debtReputationService.applyMinus(now).size, "второй проход ничего не списывает")

        assertEquals(ReputationKind.skladchina_paid, soleKind(aliceId))
        assertEquals(ReputationKind.skladchina_expired, soleKind(bobId))
        assertEquals(0, ledgerRows(carolId), "claimed останавливает часы")
        assertEquals(0, ledgerRows(ownerId), "владелец клуба очков не получает")
    }

    // --- AC-12: чужие долги невидимы ---

    @Test
    fun `owner sees neither the pair of two other members nor the debt list of someone else's skladchina`() {
        val id = createShared(alice, listOf(bobId), amount = 1_000)["id"].asText()
        val ownerPair = json(get("/api/debts/with/$aliceId", owner).andExpect(status().isOk))
        assertEquals(0, ownerPair["owe"].size() + ownerPair["owed"].size())
        assertEquals(0, json(get("/api/debts", owner))["people"].size())
        val ownerView = json(get("/api/skladchinas/$id", owner).andExpect(status().isOk))
        assertTrue(ownerView["debts"].isNull)
        assertTrue(ownerView["canCancel"].asBoolean())
    }

    // --- AC-13: права на отмену ---

    @Test
    fun `club owner may cancel someone else's skladchina, other members get 403, open debts are forgiven`() {
        val id = createShared(alice, listOf(bobId, carolId), amount = 2_000)["id"].asText()
        val bobDebt = myDebtId(id, bob)
        post("/api/debts/$bobDebt/claim", bob).andExpect(status().isOk)
        post("/api/debts/$bobDebt/confirm", alice).andExpect(status().isOk)

        post("/api/skladchinas/$id/cancel", carol).andExpect(status().isForbidden)
        val cancelled = json(post("/api/skladchinas/$id/cancel", owner).andExpect(status().isOk))
        assertEquals("cancelled", cancelled["status"].asText())
        val statuses = dsl.fetch("SELECT debtor_id, status::text AS s FROM debts WHERE skladchina_id = ?", UUID.fromString(id))
            .associate { UUID.fromString(it.get("debtor_id").toString()) to it.get("s", String::class.java) }
        assertEquals("received", statuses[bobId])
        assertEquals("forgiven", statuses[carolId])
        post("/api/skladchinas/$id/cancel", alice).andExpect(status().isBadRequest)
    }

    // --- AC-14: сортировка пары ---

    @Test
    fun `pair rows are ordered by due date ascending with nulls last`() {
        createShared(alice, listOf(bobId), amount = 300, deadlineDays = 9)
        val voluntary = json(postJson("/api/clubs/$clubId/skladchinas", alice, voluntaryBody(hiddenFrom = null)).andExpect(status().isCreated))
        postJson("/api/skladchinas/${voluntary["id"].asText()}/contribute", bob, """{"amountKopecks":100}""").andExpect(status().isOk)
        createShared(alice, listOf(bobId), amount = 200, deadlineDays = 2)

        val pair = json(get("/api/debts/with/$aliceId", bob).andExpect(status().isOk))
        assertEquals(listOf(200L, 300L, 100L), pair["owe"].map { it["amountKopecks"].asLong() })
        assertTrue(pair["owe"][2]["dueAt"].isNull)
    }

    // --- Замена и добавление должника ---

    @Test
    fun `replace forgives the old debt and creates the same amount for the replacement, add uses the last share`() {
        val id = createShared(alice, listOf(bobId), amount = 1_000)["id"].asText()
        val bobDebt = myDebtId(id, bob)
        val replaced = json(postJson("/api/skladchinas/$id/debts/$bobDebt/replace", alice, """{"userId":"$carolId"}""").andExpect(status().isOk))
        val rows = replaced["debts"].associate { it["debtor"]["id"].asText() to it }
        assertEquals("forgiven", rows[bobId.toString()]!!["status"].asText())
        assertEquals("waiting", rows[carolId.toString()]!!["status"].asText())
        assertEquals(1_000L, rows[carolId.toString()]!!["amountKopecks"].asLong())

        val added = json(postJson("/api/skladchinas/$id/debts", alice, """{"userId":"$ownerId"}""").andExpect(status().isOk))
        val ownerRow = added["debts"].first { it["debtor"]["id"].asText() == ownerId.toString() }
        assertEquals(1_000L, ownerRow["amountKopecks"].asLong())
        assertEquals("waiting", ownerRow["status"].asText())
        assertEquals(2_000L, added["targetKopecks"].asLong(), "знаменатель — сумма живых долгов")
        postJson("/api/skladchinas/$id/debts", alice, """{"userId":"$outsiderId"}""").andExpect(status().isBadRequest)
    }

    // ---- helpers ----

    private fun newUser(telegramId: Long, name: String): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name, telegram_username) VALUES ('$id', $telegramId, '$name', '${name.lowercase()}')")
        return id
    }

    private fun sharedBody(debtors: List<UUID>, amount: Long, deadlineDays: Long = 5): String = """
        {
          "title": "Ужин после игры",
          "kind": "shared",
          "amountKopecks": $amount,
          "paymentLink": "https://pay.example/owner",
          "deadline": "${OffsetDateTime.now().plusDays(deadlineDays)}",
          "debtors": [${debtors.joinToString(",") { "{\"userId\":\"$it\"}" }}]
        }
    """.trimIndent()

    private fun enrollingBody(amount: Long, min: Int): String = """
        {
          "title": "Тренер на субботу",
          "kind": "shared",
          "amountKopecks": $amount,
          "paymentLink": "https://pay.example/owner",
          "deadline": "${OffsetDateTime.now().plusDays(5)}",
          "enrollmentUntil": "${OffsetDateTime.now().plusDays(1)}",
          "minParticipants": $min
        }
    """.trimIndent()

    private fun perHeadBody(price: Long): String = """
        {
          "title": "Билеты на матч",
          "kind": "per_head",
          "amountKopecks": $price,
          "paymentLink": "https://pay.example/owner",
          "deadline": "${OffsetDateTime.now().plusDays(3)}"
        }
    """.trimIndent()

    private fun voluntaryBody(hiddenFrom: UUID?): String = """
        {
          "title": "Подарок",
          "kind": "voluntary",
          "amountKopecks": 50000,
          "paymentLink": "https://pay.example/owner"
          ${hiddenFrom?.let { ", \"hiddenFromUserId\": \"$it\"" } ?: ""}
        }
    """.trimIndent()

    private fun createShared(token: String, debtors: List<UUID>, amount: Long, deadlineDays: Long = 5): JsonNode =
        json(postJson("/api/clubs/$clubId/skladchinas", token, sharedBody(debtors, amount, deadlineDays)).andExpect(status().isCreated))

    private fun myDebtId(skladchinaId: String, token: String): String =
        json(get("/api/skladchinas/$skladchinaId", token).andExpect(status().isOk))["myDebt"]["id"].asText()

    private fun get(path: String, token: String): ResultActions =
        mockMvc.perform(get(path).header("Authorization", "Bearer $token"))

    private fun post(path: String, token: String): ResultActions =
        mockMvc.perform(post(path).header("Authorization", "Bearer $token"))

    private fun postJson(path: String, token: String, body: String): ResultActions =
        mockMvc.perform(post(path).header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun json(result: ResultActions): JsonNode =
        objectMapper.readTree(result.andReturn().response.contentAsString)

    private fun ledgerRows(userId: UUID): Int =
        dsl.fetchCount(REPUTATION_LEDGER, REPUTATION_LEDGER.USER_ID.eq(userId))

    private fun soleKind(userId: UUID): ReputationKind? =
        dsl.select(REPUTATION_LEDGER.KIND).from(REPUTATION_LEDGER).where(REPUTATION_LEDGER.USER_ID.eq(userId)).fetchOne()?.value1()
}
