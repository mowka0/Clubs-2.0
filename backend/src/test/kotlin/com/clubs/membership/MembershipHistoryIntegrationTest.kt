package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipEvent
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.MEMBERSHIP_HISTORY
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
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Verifies the append-only membership_history log captures the right lifecycle event at each
 * transition point in [JooqMembershipRepository], against a real Postgres. Spec: V31.
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
class MembershipHistoryIntegrationTest {

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

    @Autowired lateinit var membershipRepository: MembershipRepository
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private lateinit var memberId: UUID
    private lateinit var clubId: UUID
    private var telegramSeq = 7000L

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM membership_history")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")
        telegramSeq = 7000L
        ownerId = newUser()
        memberId = newUser()
        clubId = insertClub(ownerId)
    }

    @Test
    fun `create records joined`() {
        membershipRepository.create(memberId, clubId)
        assertEvents(memberId, MembershipEvent.joined)
    }

    @Test
    fun `createOrganizer records nothing (owner lifecycle is not tracked)`() {
        membershipRepository.createOrganizer(ownerId, clubId)
        assertEvents(ownerId) // member-only log: the organizer's own membership is not recorded
    }

    @Test
    fun `cancel records left`() {
        val m = membershipRepository.create(memberId, clubId)
        membershipRepository.cancel(m.id)
        assertEvents(memberId, MembershipEvent.joined, MembershipEvent.left)
    }

    @Test
    fun `reactivateFree records rejoined`() {
        val m = membershipRepository.create(memberId, clubId)
        membershipRepository.cancel(m.id)
        membershipRepository.reactivateFree(m.id)
        assertEvents(memberId, MembershipEvent.joined, MembershipEvent.left, MembershipEvent.rejoined)
    }

    @Test
    fun `markDuesPaid from expired does NOT record (resumption, not rejoin)`() {
        // Expired-должник — всё ещё участник (статусная модель 2026-07-06): продление взноса
        // возвращает active без события в истории. `rejoined` остаётся только за cancelled → active.
        val m = expiredMembership(memberId)
        val updated = membershipRepository.markDuesPaid(m, ownerId, OffsetDateTime.now().plusDays(30))
        assertEquals(1, updated)
        assertStatus(m, MembershipStatus.active)
        assertEvents(memberId, MembershipEvent.joined) // only the original join
    }

    @Test
    fun `cancel clears a pending dues claim`() {
        val m = membershipRepository.createFrozen(memberId, clubId)
        membershipRepository.claimDues(m.id, "cash", null)
        assertClaimPresent(m.id) // sanity: the claim was recorded
        membershipRepository.cancel(m.id)
        assertClaimCleared(m.id)
    }

    @Test
    fun `reactivateFrozen drops a stale dues claim from the prior lifecycle`() {
        // Bug scenario: member claims cash → organizer rejects (cancel) → member re-joins. The fresh
        // frozen membership must NOT carry the old claim, else they reappear on «Оплата на проверке».
        val m = membershipRepository.createFrozen(memberId, clubId)
        membershipRepository.claimDues(m.id, "cash", null)
        membershipRepository.cancel(m.id)
        val revived = membershipRepository.reactivateFrozen(m.id)
        assertEquals(MembershipStatus.frozen, revived.status)
        assertClaimCleared(m.id)
    }

    @Test
    fun `expireOverdueAccess flips overdue active to expired without a churn event`() {
        val now = OffsetDateTime.now()
        // joined + активное окно уже в прошлом (репозиторный setAccessUntil не валидирует дату —
        // валидация будущего живёт в AccessGateService).
        val m = membershipRepository.create(memberId, clubId)
        membershipRepository.setAccessUntil(m.id, now.minusDays(1))
        val count = membershipRepository.expireOverdueAccess(now) // active → expired (просрочка продления)
        assertEquals(1, count)
        assertStatus(m.id, MembershipStatus.expired)
        // Лапс продления — приостановка доступа, НЕ churn: логируется только исходный join
        // (docs/modules/membership-lifecycle.md §3 — иначе club-quality штрафовал бы ранг ложным оттоком).
        assertEvents(memberId, MembershipEvent.joined)
    }

    @Test
    fun `claimDues works from expired (renewal debtor claims payment)`() {
        val m = expiredMembership(memberId)
        val updated = membershipRepository.claimDues(m, "cash", null)
        assertEquals(1, updated)
        assertClaimPresent(m)
    }

    @Test
    fun `countClaimedAwaitingDuesByOwner counts frozen AND expired members with a live dues claim`() {
        // Бейдж таб-бара «Мои клубы» (2026-07-06): без claim не считается никто — только заявившие об
        // оплате (им нужно действие организатора «Взнос получен»), и frozen, и expired.
        val claimedFrozenUser = newUser()
        val claimedExpiredUser = newUser()
        membershipRepository.createFrozen(memberId, clubId)                            // frozen БЕЗ claim — не считается
        val claimedFrozen = membershipRepository.createFrozen(claimedFrozenUser, clubId) // frozen С claim — считается
        membershipRepository.claimDues(claimedFrozen.id, "sbp", null)
        val claimedExpired = expiredMembership(claimedExpiredUser)                       // expired С claim — считается
        membershipRepository.claimDues(claimedExpired, "cash", null)

        assertEquals(2, membershipRepository.countClaimedAwaitingDuesByOwner(ownerId))

        // «Взнос получен» открывает доступ и снимает claim — счётчик гаснет по одному.
        membershipRepository.markDuesPaid(claimedFrozen.id, ownerId, OffsetDateTime.now().plusDays(30))
        assertEquals(1, membershipRepository.countClaimedAwaitingDuesByOwner(ownerId))
        membershipRepository.markDuesPaid(claimedExpired, ownerId, OffsetDateTime.now().plusDays(30))
        assertEquals(0, membershipRepository.countClaimedAwaitingDuesByOwner(ownerId))
    }

    @Test
    fun `findAwaitingDuesMembersByOwner lists frozen, expired and claimed-active members with their accessStatus`() {
        val frozenUser = newUser()
        val expiredUser = newUser()
        val renewingUser = newUser()   // active + claim (раннее продление) — в ленте
        val calmActiveUser = newUser() // active БЕЗ claim — в ленту не попадает
        membershipRepository.createFrozen(frozenUser, clubId)
        expiredMembership(expiredUser)
        val renewing = membershipRepository.create(renewingUser, clubId)
        membershipRepository.setAccessUntil(renewing.id, OffsetDateTime.now().plusDays(2))
        membershipRepository.claimDues(renewing.id, "cash", null)
        membershipRepository.create(calmActiveUser, clubId)

        val rows = membershipRepository.findAwaitingDuesMembersByOwner(ownerId)

        assertEquals(
            mapOf(frozenUser to "frozen", expiredUser to "expired", renewingUser to "active"),
            rows.associate { it.userId to it.accessStatus }
        )
        // Claimed-active (раннее продление) зажигает и бейдж таб-бара.
        assertEquals(1, membershipRepository.countClaimedAwaitingDuesByOwner(ownerId))
    }

    // ---- helpers ----

    /** Создаёт membership и проводит его через реальный путь просрочки: create → окно в прошлом →
     *  expireOverdueAccess. Возвращает id membership в статусе `expired`. */
    private fun expiredMembership(userId: UUID): UUID {
        val m = membershipRepository.create(userId, clubId)
        membershipRepository.setAccessUntil(m.id, OffsetDateTime.now().minusDays(1))
        membershipRepository.expireOverdueAccess(OffsetDateTime.now())
        assertStatus(m.id, MembershipStatus.expired) // sanity: фикстура действительно expired
        return m.id
    }

    private fun assertStatus(membershipId: UUID, expected: MembershipStatus) {
        val actual = dsl.select(MEMBERSHIPS.STATUS)
            .from(MEMBERSHIPS).where(MEMBERSHIPS.ID.eq(membershipId))
            .fetchOne(MEMBERSHIPS.STATUS)
        assertEquals(expected, actual, "membership status")
    }

    /** Asserts the multiset of logged events for (userId, clubId) equals [expected] (order-independent). */
    private fun assertEvents(userId: UUID, vararg expected: MembershipEvent) {
        val actual = dsl.select(MEMBERSHIP_HISTORY.EVENT)
            .from(MEMBERSHIP_HISTORY)
            .where(MEMBERSHIP_HISTORY.USER_ID.eq(userId).and(MEMBERSHIP_HISTORY.CLUB_ID.eq(clubId)))
            .fetch(MEMBERSHIP_HISTORY.EVENT)
            .filterNotNull()
        assertEquals(
            expected.sortedBy { it.ordinal },
            actual.sortedBy { it.ordinal },
            "membership_history events for the user"
        )
    }

    /** Asserts the member-side dues claim columns are all set (a claim is on record). */
    private fun assertClaimPresent(membershipId: UUID) {
        val r = dsl.select(MEMBERSHIPS.DUES_CLAIMED_AT, MEMBERSHIPS.DUES_CLAIM_METHOD)
            .from(MEMBERSHIPS).where(MEMBERSHIPS.ID.eq(membershipId)).fetchOne()!!
        assertEquals("cash", r.get(MEMBERSHIPS.DUES_CLAIM_METHOD), "dues_claim_method should be set")
        assert(r.get(MEMBERSHIPS.DUES_CLAIMED_AT) != null) { "dues_claimed_at should be set" }
    }

    /** Asserts the member-side dues claim columns are all NULL (no live claim). */
    private fun assertClaimCleared(membershipId: UUID) {
        val r = dsl.select(MEMBERSHIPS.DUES_CLAIMED_AT, MEMBERSHIPS.DUES_CLAIM_METHOD, MEMBERSHIPS.DUES_PROOF_URL)
            .from(MEMBERSHIPS).where(MEMBERSHIPS.ID.eq(membershipId)).fetchOne()!!
        assertEquals(null, r.get(MEMBERSHIPS.DUES_CLAIMED_AT), "dues_claimed_at must be cleared")
        assertEquals(null, r.get(MEMBERSHIPS.DUES_CLAIM_METHOD), "dues_claim_method must be cleared")
        assertEquals(null, r.get(MEMBERSHIPS.DUES_PROOF_URL), "dues_proof_url must be cleared")
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
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit)
            VALUES ('$id', '$owner', 'Club', 'desc', 'sport', 'open', 'Moscow', 20)
            """.trimIndent()
        )
        return id
    }
}
