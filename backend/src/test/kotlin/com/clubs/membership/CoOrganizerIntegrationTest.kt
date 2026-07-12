package com.clubs.membership

import com.clubs.auth.JwtService
import com.clubs.club.ClubRepository
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/**
 * Сквозные критерии co-organizers против реального Postgres + Spring Security:
 * PUT /role (owner-only, лимит, идемпотентность, промоут только active), менеджерский гейт
 * @RequiresCapability (owner/active co-org 200; member/frozen co-org/не-член 403, fail-close),
 * target-матрица через реальный HTTP, owner-only регресс (chat-link, delete), managed-скоупы
 * (awaiting-dues, pending-count), сброс роли при реактивации, инвариант «ровно один organizer».
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
class CoOrganizerIntegrationTest {

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
    @Autowired lateinit var membershipRepository: MembershipRepository
    @Autowired lateinit var clubRepository: ClubRepository

    private lateinit var clubId: UUID
    private lateinit var ownerId: UUID
    private lateinit var coOrgId: UUID
    private lateinit var memberId: UUID
    private var telegramSeq = 9000L

    private lateinit var ownerToken: String
    private lateinit var coOrgToken: String
    private lateinit var memberToken: String

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM applications")
        dsl.execute("DELETE FROM transactions")
        dsl.execute("DELETE FROM membership_history")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM user_club_reputation")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")
        telegramSeq = 9000L

        ownerId = newUser()
        coOrgId = newUser()
        memberId = newUser()
        clubId = insertClub(ownerId)

        insertMembership(ownerId, clubId, "active", "organizer")
        insertMembership(coOrgId, clubId, "active", "co_organizer")
        insertMembership(memberId, clubId, "active", "member")

        ownerToken = token(ownerId)
        coOrgToken = token(coOrgId)
        memberToken = token(memberId)
    }

    // ---- PUT /role: owner-only + бизнес-правила ----

    @Test
    fun `owner promotes an active member and the change is idempotent`() {
        mockMvc.perform(putRole(memberId, "co_organizer", ownerToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.role").value("co_organizer"))
        assertEquals(MembershipRole.co_organizer, roleOf(memberId))

        // Повторный промоут — no-op 200 (идемпотентность).
        mockMvc.perform(putRole(memberId, "co_organizer", ownerToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.role").value("co_organizer"))

        // Демоут возвращает участника.
        mockMvc.perform(putRole(memberId, "member", ownerToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.role").value("member"))
        assertEquals(MembershipRole.member, roleOf(memberId))

        assertExactlyOneOrganizer()
    }

    @Test
    fun `co-organizer cannot change roles (403), even demote himself`() {
        mockMvc.perform(putRole(memberId, "co_organizer", coOrgToken))
            .andExpect(status().isForbidden)
        mockMvc.perform(putRole(coOrgId, "member", coOrgToken))
            .andExpect(status().isForbidden)
        assertEquals(MembershipRole.co_organizer, roleOf(coOrgId))
    }

    @Test
    fun `PUT role validation matrix - self, owner target, organizer body, frozen promote, missing member`() {
        // Сам себе — 400.
        mockMvc.perform(putRole(ownerId, "co_organizer", ownerToken)).andExpect(status().isBadRequest)
        // body organizer — 400 (передача владения вне скоупа).
        mockMvc.perform(putRole(memberId, "organizer", ownerToken)).andExpect(status().isBadRequest)
        // Промоут frozen-участника — 400 (У-9).
        val frozenId = newUser()
        insertMembership(frozenId, clubId, "frozen", "member")
        mockMvc.perform(putRole(frozenId, "co_organizer", ownerToken)).andExpect(status().isBadRequest)
        // Демоут frozen со-орга — 200 (снять роль с замороженного можно).
        dsl.execute("UPDATE memberships SET status = 'frozen' WHERE user_id = '$coOrgId' AND club_id = '$clubId'")
        mockMvc.perform(putRole(coOrgId, "member", ownerToken)).andExpect(status().isOk)
        // Не участник — 404.
        mockMvc.perform(putRole(newUser(), "co_organizer", ownerToken)).andExpect(status().isNotFound)
    }

    @Test
    fun `the 6th co-organizer hits the limit (У-3)`() {
        // Уже есть один со-орг (setUp) — добавляем ещё 4 до лимита 5.
        repeat(4) {
            val u = newUser()
            insertMembership(u, clubId, "active", "co_organizer")
        }
        mockMvc.perform(putRole(memberId, "co_organizer", ownerToken))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
    }

    // ---- Менеджерский гейт (@RequiresCapability), fail-close ----

    @Test
    fun `manager gate - owner and active co-org pass, member and frozen co-org get 403`() {
        val path = "/api/clubs/$clubId/award-suggestions"
        mockMvc.perform(get(path).auth(ownerToken)).andExpect(status().isOk)
        mockMvc.perform(get(path).auth(coOrgToken)).andExpect(status().isOk)
        mockMvc.perform(get(path).auth(memberToken)).andExpect(status().isForbidden)
        mockMvc.perform(get(path).auth(token(newUser()))).andExpect(status().isForbidden)

        // Fail-close: заморозка со-орга мгновенно отбирает права; разморозка возвращает.
        dsl.execute("UPDATE memberships SET status = 'frozen' WHERE user_id = '$coOrgId' AND club_id = '$clubId'")
        mockMvc.perform(get(path).auth(coOrgToken)).andExpect(status().isForbidden)
        dsl.execute("UPDATE memberships SET status = 'active' WHERE user_id = '$coOrgId' AND club_id = '$clubId'")
        mockMvc.perform(get(path).auth(coOrgToken)).andExpect(status().isOk)
    }

    @Test
    fun `owner-only endpoints stay closed to a co-organizer - chat-link and club delete`() {
        mockMvc.perform(get("/api/clubs/$clubId/chat-link").auth(coOrgToken))
            .andExpect(status().isForbidden)
        mockMvc.perform(delete("/api/clubs/$clubId").auth(coOrgToken))
            .andExpect(status().isForbidden)
    }

    // ---- Target-матрица через реальный HTTP ----

    @Test
    fun `co-org manages a plain member but not the owner or another co-org`() {
        // member — можно (200): заморозка/разморозка.
        mockMvc.perform(post("/api/clubs/$clubId/members/$memberId/freeze").auth(coOrgToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("frozen"))
        mockMvc.perform(post("/api/clubs/$clubId/members/$memberId/unfreeze").auth(coOrgToken))
            .andExpect(status().isOk)

        // владелец — 403.
        mockMvc.perform(post("/api/clubs/$clubId/members/$ownerId/freeze").auth(coOrgToken))
            .andExpect(status().isForbidden)

        // другой со-орг — 403.
        val coOrg2 = newUser()
        insertMembership(coOrg2, clubId, "active", "co_organizer")
        mockMvc.perform(post("/api/clubs/$clubId/members/$coOrg2/freeze").auth(coOrgToken))
            .andExpect(status().isForbidden)

        // Владелец со-орга замораживает свободно.
        mockMvc.perform(post("/api/clubs/$clubId/members/$coOrg2/freeze").auth(ownerToken))
            .andExpect(status().isOk)
    }

    // ---- Managed-скоупы (У-5): «Ждут оплаты» и pending-count у со-орга ----

    @Test
    fun `awaiting-dues and pending-count are visible to an active co-org and vanish when he is frozen`() {
        val debtor = newUser()
        insertMembership(debtor, clubId, "frozen", "member")
        dsl.execute("UPDATE memberships SET dues_claimed_at = now(), dues_claim_method = 'cash' WHERE user_id = '$debtor'")

        mockMvc.perform(get("/api/users/me/organizer/awaiting-dues").auth(coOrgToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].userId").value(debtor.toString()))
        mockMvc.perform(get("/api/users/me/applications-pending-count").auth(coOrgToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.awaitingDuesCount").value(1))

        // Fail-close: замороженный со-орг теряет managed-скоуп.
        dsl.execute("UPDATE memberships SET status = 'frozen' WHERE user_id = '$coOrgId' AND club_id = '$clubId'")
        mockMvc.perform(get("/api/users/me/organizer/awaiting-dues").auth(coOrgToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty)
        mockMvc.perform(get("/api/users/me/applications-pending-count").auth(coOrgToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.awaitingDuesCount").value(0))

        // У постороннего участника скоуп пуст всегда.
        mockMvc.perform(get("/api/users/me/organizer/awaiting-dues").auth(memberToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty)
    }

    @Test
    fun `findManagedIds spans owned and co-managed clubs, strictly active`() {
        assertEquals(listOf(clubId), clubRepository.findManagedIds(coOrgId))
        assertEquals(listOf(clubId), clubRepository.findManagedIds(ownerId))
        assertEquals(emptyList(), clubRepository.findManagedIds(memberId))

        dsl.execute("UPDATE memberships SET status = 'expired' WHERE user_id = '$coOrgId' AND club_id = '$clubId'")
        assertEquals(emptyList(), clubRepository.findManagedIds(coOrgId))
    }

    // ---- Re-join: роль умирает вместе с membership ----

    @Test
    fun `reactivation resets the role to member`() {
        val m = membershipRepository.findByUserAndClub(coOrgId, clubId)!!
        membershipRepository.cancel(m.id)
        membershipRepository.reactivateFree(m.id)

        assertEquals(MembershipRole.member, roleOf(coOrgId))
        assertEquals(MembershipStatus.active, dsl.select(MEMBERSHIPS.STATUS).from(MEMBERSHIPS)
            .where(MEMBERSHIPS.ID.eq(m.id)).fetchOne(MEMBERSHIPS.STATUS))
        assertExactlyOneOrganizer()
    }

    // ---- helpers ----

    private fun putRole(targetUserId: UUID, role: String, bearer: String) =
        put("/api/clubs/$clubId/members/$targetUserId/role")
            .header("Authorization", "Bearer $bearer")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"role":"$role"}""")

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.auth(bearer: String) =
        header("Authorization", "Bearer $bearer")

    private fun token(userId: UUID): String = jwtService.generateToken(userId, telegramSeq++)

    private fun roleOf(userId: UUID): MembershipRole? =
        dsl.select(MEMBERSHIPS.ROLE).from(MEMBERSHIPS)
            .where(MEMBERSHIPS.USER_ID.eq(userId).and(MEMBERSHIPS.CLUB_ID.eq(clubId)))
            .fetchOne(MEMBERSHIPS.ROLE)

    /** Инвариант данных: в клубе ровно одна строка role=organizer (владелец). */
    private fun assertExactlyOneOrganizer() {
        val count = dsl.selectCount().from(MEMBERSHIPS)
            .where(MEMBERSHIPS.CLUB_ID.eq(clubId).and(MEMBERSHIPS.ROLE.eq(MembershipRole.organizer)))
            .fetchOne(0, Int::class.java)
        assertEquals(1, count, "exactly one organizer row per club")
    }

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', ${telegramSeq++}, 'U')")
        return id
    }

    private fun insertClub(owner: UUID): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price)
            VALUES ('$id', '$owner', 'Club', 'desc', 'sport', 'closed', 'Moscow', 30, 0)
            """.trimIndent()
        )
        return id
    }

    private fun insertMembership(userId: UUID, clubId: UUID, status: String, role: String) {
        dsl.execute(
            "INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$userId', '$clubId', '$status', '$role')"
        )
    }

    // --- Security-фиксы: СБП-реквизиты owner-only и конкурентный лимит ---

    @Test
    fun `co-org updates club settings over HTTP but cannot touch the SBP requisites`() {
        mockMvc.perform(
            put("/api/clubs/$clubId").auth(coOrgToken)
                .contentType(MediaType.APPLICATION_JSON).content("""{"name":"Renamed by co-org"}""")
        ).andExpect(status().isOk)

        mockMvc.perform(
            put("/api/clubs/$clubId").auth(coOrgToken)
                .contentType(MediaType.APPLICATION_JSON).content("""{"paymentLink":"sbp://evil"}""")
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            put("/api/clubs/$clubId").auth(ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content("""{"paymentLink":"sbp://mine"}""")
        ).andExpect(status().isOk)
    }

    @Test
    fun `concurrent promotes cannot break the co-organizer limit (anti-TOCTOU)`() {
        // 1 со-орг из setUp + 3 дополнительных = 4; лимит 5 — ровно один свободный слот.
        repeat(3) { insertMembership(newUser(), clubId, "active", "co_organizer") }
        val second = newUser()
        insertMembership(second, clubId, "active", "member")

        val pool = Executors.newFixedThreadPool(2)
        val go = CountDownLatch(1)
        val futures = listOf(memberId, second).map { target ->
            pool.submit<Int> {
                go.await()
                mockMvc.perform(putRole(target, "co_organizer", ownerToken)).andReturn().response.status
            }
        }
        go.countDown()
        val statuses = futures.map { it.get() }.sorted()
        pool.shutdown()

        // Advisory-лок сериализует промоуты: ровно один занимает последний слот, второй бьётся о лимит.
        assertEquals(listOf(200, 400), statuses)
        val coOrgCount = dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIPS.ROLE.eq(MembershipRole.co_organizer))
                    .and(MEMBERSHIPS.STATUS.ne(MembershipStatus.cancelled))
            )
            .fetchOne(0, Int::class.java)
        assertEquals(5, coOrgCount, "limit must hold under concurrency")
    }
}
